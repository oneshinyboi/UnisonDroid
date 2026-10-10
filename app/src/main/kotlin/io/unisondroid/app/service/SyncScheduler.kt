package io.unisondroid.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.unisondroid.app.data.AppSettings
import io.unisondroid.app.data.Profile
import io.unisondroid.app.sync.SyncMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Maps profiles and app settings onto [WorkManager] work. [syncNow] enqueues an interactive
 * one-shot; [reconcile] keeps one periodic unattended request per auto-sync-enabled profile.
 */
class SyncScheduler(private val context: Context) {

    fun syncNow(profileId: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_PROFILE_ID to profileId,
                    SyncWorker.KEY_MODE to SyncMode.INTERACTIVE.name,
                ),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(nowWorkName(profileId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(profileId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName(profileId))
    }

    suspend fun reconcile(profiles: List<Profile>, settings: AppSettings) {
        val workManager = WorkManager.getInstance(context)
        val desiredIds = profiles.filter { it.autoSyncEnabled }.map { it.id }.toSet()

        // Make reconcile authoritative: cancel any auto-sync work whose profile is no
        // longer enabled or no longer exists. This does not rely on every removal path
        // remembering to call cancel(). Work enqueued by a version that predates
        // AUTO_SYNC_TAG is not swept (it was cancelled on delete by that version).
        withContext(Dispatchers.IO) {
            val existing = runCatching { workManager.getWorkInfosByTag(AUTO_SYNC_TAG).get() }
                .onFailure { Log.w(TAG, "auto-sync sweep query failed; orphaned work may remain", it) }
                .getOrDefault(emptyList())
            existing.forEach { info ->
                val id = info.tags.firstNotNullOfOrNull { tag ->
                    tag.takeIf { it.startsWith(PROFILE_TAG_PREFIX) }?.removePrefix(PROFILE_TAG_PREFIX)
                }
                if (id == null || id !in desiredIds) workManager.cancelWorkById(info.id)
            }
        }

        profiles.filter { it.autoSyncEnabled }.forEach { profile ->
            val minutes = maxOf(profile.autoSyncIntervalMinutes, MIN_INTERVAL_MINUTES).toLong()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        SyncWorker.KEY_PROFILE_ID to profile.id,
                        SyncWorker.KEY_MODE to SyncMode.UNATTENDED.name,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (settings.syncOnMobileData) {
                                NetworkType.CONNECTED
                            } else {
                                NetworkType.UNMETERED
                            },
                        )
                        .build(),
                )
                .addTag(AUTO_SYNC_TAG)
                .addTag(PROFILE_TAG_PREFIX + profile.id)
                .build()
            workManager.enqueueUniquePeriodicWork(
                periodicWorkName(profile.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15

        private const val TAG = "SyncScheduler"

        /** Tags every periodic auto-sync request and, per profile, its owning profile id. */
        const val AUTO_SYNC_TAG = "auto-sync"
        const val PROFILE_TAG_PREFIX = "auto-sync-profile-"

        fun nowWorkName(profileId: String): String = "sync-$profileId-now"

        fun periodicWorkName(profileId: String): String = "sync-$profileId"
    }
}

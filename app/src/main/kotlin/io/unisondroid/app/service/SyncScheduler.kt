package io.unisondroid.app.service

import android.content.Context
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

    fun reconcile(profiles: List<Profile>, settings: AppSettings) {
        profiles.forEach { profile ->
            val name = periodicWorkName(profile.id)
            if (!profile.autoSyncEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(name)
                return@forEach
            }
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
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15

        fun nowWorkName(profileId: String): String = "sync-$profileId-now"

        fun periodicWorkName(profileId: String): String = "sync-$profileId"
    }
}

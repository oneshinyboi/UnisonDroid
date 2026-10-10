package io.unisondroid.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.unisondroid.app.sync.SyncMode
import io.unisondroid.app.sync.SyncOutcome
import kotlinx.coroutines.CancellationException

/**
 * The single host for a sync run: it promotes to a `dataSync` foreground service for the duration
 * and maps the engine's [SyncOutcome] onto a WorkManager result.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID)
        if (profileId.isNullOrBlank()) return Result.failure()
        val mode = inputData.getString(KEY_MODE)
            ?.let { runCatching { SyncMode.valueOf(it) }.getOrNull() }
            ?: SyncMode.UNATTENDED

        val notifications = SyncNotifications(applicationContext)

        // Starting a foreground service can be refused (FGS restrictions, missing notification
        // permission). The sync itself must still run, so a failure here is not fatal.
        runCatching { setForeground(notifications.foregroundInfo(TEXT_RUNNING)) }

        val outcome = try {
            ServiceLocator.engine(applicationContext).requestSync(profileId, mode)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return Result.retry()
        }

        return when (outcome) {
            SyncOutcome.COMPLETED -> Result.success()
            SyncOutcome.SKIPPED_UNTRUSTED -> {
                notifications.notifyNeedsApproval(profileId)
                Result.success()
            }

            SyncOutcome.FAILED -> {
                if (mode == SyncMode.UNATTENDED) notifications.notifyFailure(profileId)
                Result.failure()
            }

            SyncOutcome.BUSY ->
                if (mode == SyncMode.UNATTENDED) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_PROFILE_ID = "profileId"
        const val KEY_MODE = "mode"
        private const val TEXT_RUNNING = "Syncing…"
    }
}

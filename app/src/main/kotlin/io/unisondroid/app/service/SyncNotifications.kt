package io.unisondroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.unisondroid.app.MainActivity

/**
 * Builds the notifications the sync worker shows: the ongoing foreground indicator plus the two
 * terminal notifications an unattended run may post.
 */
class SyncNotifications(private val context: Context) {

    init {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun foregroundInfo(text: String): ForegroundInfo {
        val notification = notification(text, ongoing = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    fun notifyFailure(profileId: String) {
        post(TERMINAL_NOTIFICATION_ID, "Sync failed: $profileId")
    }

    fun notifyNeedsApproval(profileId: String) {
        post(
            TERMINAL_NOTIFICATION_ID,
            "Host key for $profileId needs approval; open the app to approve it",
        )
    }

    private fun post(id: Int, text: String) {
        notificationManager().notify(id, notification(text, ongoing = false))
    }

    private fun notification(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        const val CHANNEL_ID = "unison_sync"
        const val NOTIFICATION_ID = 1

        /**
         * Terminal notifications use a separate id because WorkManager removes the foreground
         * notification ([NOTIFICATION_ID]) when the worker finishes; posting failures or
         * needs-approval updates to the foreground id would be wiped out before the user sees them.
         */
        const val TERMINAL_NOTIFICATION_ID = 2

        private const val CHANNEL_NAME = "Sync"
        private const val NOTIFICATION_TITLE = "Unison sync"
    }
}

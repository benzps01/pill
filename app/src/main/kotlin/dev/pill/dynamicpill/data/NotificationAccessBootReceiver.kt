package dev.pill.dynamicpill.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.pill.dynamicpill.R

/**
 * CLAUDE.md rule 11: Notification Access can silently drop after a system
 * update, and a revoked permission must be surfaced, never fail silently.
 * BOOT_COMPLETED is a one-shot check, not a poll (rule 1) — no periodic
 * re-checking after this.
 */
class NotificationAccessBootReceiver : BroadcastReceiver() {

    private companion object {
        private const val CHANNEL_ID = "notification_access_alert"
        private const val NOTIFICATION_ID = 1
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (NotificationAccess.isGranted(context)) return

        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_access_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_access_revoked_title))
            .setContentText(context.getString(R.string.notification_access_revoked_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}

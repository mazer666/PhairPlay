package com.phairplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phairplay.MainActivity
import com.phairplay.R

/**
 * ServiceNotificationManager — Builds and updates the foreground-service notification.
 *
 * WHAT: Creates the notification channel, builds the persistent notification of the
 * foreground service and updates its text (running / stopped / streaming from …).
 *
 * WHY: The notification is what keeps the service alive on Android and what tells the
 * user that PhairPlay is active. It also carries the "Stop" and "Restart" actions. All of
 * that is Android plumbing that has nothing to do with the protocol receivers, so it
 * lives in its own class instead of bloating [PhairPlayService].
 *
 * HOW: The service creates one manager with the application context and calls
 * [createNotificationChannel] once in `onCreate()`. Afterwards it shows the notification
 * via [buildNotification] and refreshes the text via [updateNotification].
 *
 * Example:
 *   val notifications = ServiceNotificationManager(applicationContext)
 *   notifications.createNotificationChannel()
 *   notifications.updateNotification(isRunning = true, streamingSenderName = "MacBook Pro")
 */
internal class ServiceNotificationManager(private val context: Context) {

    /**
     * Creates the low-importance notification channel (Android 8 and newer).
     *
     * LOW importance: no sound, minimal visual interruption — the service is expected to
     * run for hours.
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PhairPlayService.CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, PhairPlayService::class.java).apply { action = PhairPlayService.ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            context, 2,
            Intent(context, PhairPlayService::class.java).apply { action = PhairPlayService.ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(context, PhairPlayService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(notificationContentText ?: context.getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    context.getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, context.getString(R.string.action_restart), restartIntent)
            .build()
    }

    /**
     * Shows the notification with an updated status line.
     *
     * @param isRunning           True if receivers are active; false if stopped/restarting.
     * @param streamingSenderName Name of the connected sender; null keeps the status text.
     */
    fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            context.getString(R.string.notification_status_streaming, it)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PhairPlayService.NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }
}

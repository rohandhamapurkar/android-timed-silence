package com.example.timedsilence.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.timedsilence.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The ongoing "silence active" notification.
 *
 * Built in one place so that the notification posted when a session starts is the
 * same one the receiver re-posts after an extend - previously the initial
 * notification carried no actions at all, leaving Stop and Extend unreachable.
 */
object SilenceNotifications {

    const val CHANNEL_ID = "timed_silence_channel"
    const val NOTIFICATION_ID = 1001
    const val EXTENSION_MINUTES = 15

    private const val TAG = "SilenceNotifications"
    private const val REQUEST_CONTENT = 0
    private const val REQUEST_STOP = 1
    private const val REQUEST_EXTEND = 2

    fun createChannel(context: Context) {
        val manager = notificationManager(context) ?: return
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Timed Silence Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays active silence periods"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }.onFailure { Log.w(TAG, "Could not create the notification channel", it) }
    }

    /** Posts (or refreshes) the ongoing notification for a session ending at [endTimeMillis]. */
    fun showOngoing(context: Context, endTimeMillis: Long) {
        val manager = notificationManager(context) ?: return
        runCatching {
            val endTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endTimeMillis))
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentTitle("Timed Silence Active")
                .setContentText("Ringer restores at $endTimeStr")
                .setOngoing(true)
                .setShowWhen(true)
                .setWhen(endTimeMillis)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(contentIntent(context))
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Restore now",
                    broadcast(context, REQUEST_STOP, TimedSilenceReceiver.ACTION_STOP)
                )
                .addAction(
                    android.R.drawable.ic_input_add,
                    "Extend +${EXTENSION_MINUTES}m",
                    broadcast(context, REQUEST_EXTEND, TimedSilenceReceiver.ACTION_EXTEND)
                )
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Could not post the ongoing notification", it) }
    }

    fun cancel(context: Context) {
        runCatching { notificationManager(context)?.cancel(NOTIFICATION_ID) }
            .onFailure { Log.w(TAG, "Could not cancel the ongoing notification", it) }
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun contentIntent(context: Context): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent? {
        val intent = Intent(context, TimedSilenceReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

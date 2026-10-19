package com.example.timedsilence.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

data class ReadinessStatus(val soundAccess: Boolean, val exactAlarms: Boolean, val notifications: Boolean) {
    val canStartTimer: Boolean get() = soundAccess && exactAlarms
    val fullyReady: Boolean get() = canStartTimer && notifications
}

object PermissionUtils {
    fun readiness(context: Context): ReadinessStatus = ReadinessStatus(
        soundAccess = hasNotificationPolicyAccess(context),
        exactAlarms = canScheduleExactAlarms(context),
        notifications = notificationsEnabled(context)
    )

    fun notificationsEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return@runCatching false
        if (!manager.areNotificationsEnabled()) return@runCatching false
        val channel = manager.getNotificationChannel(SilenceNotifications.CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return@runCatching false
        val group = channel?.group?.let { manager.getNotificationChannelGroup(it) }
        group?.isBlocked != true
    }.getOrDefault(false)

    fun requestNotificationSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        })
    }

    fun hasNotificationPolicyAccess(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    fun requestNotificationPolicyAccess(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        context.startActivity(intent)
    }

    /**
     * Whether the restoration alarm can be scheduled for an exact time. Without
     * this the alarm falls back to an inexact one, which the system is free to
     * defer by several minutes while the device is idle.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun requestExactAlarmAccess(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", context.packageName, null)
        )
        context.startActivity(intent)
    }
}

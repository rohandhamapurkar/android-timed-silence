package com.example.timedsilence

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import com.example.timedsilence.util.PermissionUtils
import com.example.timedsilence.util.ReadinessStatus
import com.example.timedsilence.util.SilenceNotifications
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class ReadinessTest {
    private val context = mock(Context::class.java)
    private val manager = mock(NotificationManager::class.java)
    private val alarm = mock(AlarmManager::class.java)

    @Before fun setup() {
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(manager)
        `when`(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarm)
        `when`(manager.areNotificationsEnabled()).thenReturn(true)
        `when`(manager.isNotificationPolicyAccessGranted).thenReturn(true)
        `when`(alarm.canScheduleExactAlarms()).thenReturn(true)
    }

    @Test fun `only sound and alarms are mandatory while notifications complete readiness`() {
        for (sound in listOf(false, true)) for (exact in listOf(false, true)) for (notifications in listOf(false, true)) {
            val readiness = ReadinessStatus(sound, exact, notifications)
            assertEquals(sound && exact, readiness.canStartTimer)
            assertEquals(sound && exact && notifications, readiness.fullyReady)
        }
    }

    @Test fun `readiness refresh picks up permission changes`() {
        assertTrue(PermissionUtils.readiness(context).fullyReady)
        `when`(manager.isNotificationPolicyAccessGranted).thenReturn(false)
        `when`(alarm.canScheduleExactAlarms()).thenReturn(false)
        `when`(manager.areNotificationsEnabled()).thenReturn(false)
        assertEquals(ReadinessStatus(false, false, false), PermissionUtils.readiness(context))
    }

    @Test fun `blocked notification channel is reported even if app notifications are enabled`() {
        val channel = mock(NotificationChannel::class.java)
        `when`(manager.getNotificationChannel(SilenceNotifications.CHANNEL_ID)).thenReturn(channel)
        `when`(channel.importance).thenReturn(NotificationManager.IMPORTANCE_NONE)
        assertFalse(PermissionUtils.notificationsEnabled(context))
    }

    @Test fun `blocked channel group is reported`() {
        val channel = mock(NotificationChannel::class.java)
        val group = mock(NotificationChannelGroup::class.java)
        `when`(manager.getNotificationChannel(SilenceNotifications.CHANNEL_ID)).thenReturn(channel)
        `when`(channel.importance).thenReturn(NotificationManager.IMPORTANCE_LOW)
        `when`(channel.group).thenReturn("status")
        `when`(manager.getNotificationChannelGroup("status")).thenReturn(group)
        `when`(group.isBlocked).thenReturn(true)
        assertFalse(PermissionUtils.notificationsEnabled(context))
        `when`(group.isBlocked).thenReturn(false)
        assertTrue(PermissionUtils.notificationsEnabled(context))
    }

    @Test fun `notification service failure does not report readiness or crash`() {
        `when`(manager.areNotificationsEnabled()).thenThrow(SecurityException("Unavailable"))
        assertFalse(PermissionUtils.notificationsEnabled(context))
    }
}

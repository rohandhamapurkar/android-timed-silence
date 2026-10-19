package com.example.timedsilence

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.example.timedsilence.util.RingerRestorer
import com.example.timedsilence.util.SilenceNotifications
import com.example.timedsilence.util.SilenceStore
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class RestorationConfirmationTest {
    private val context = mock(Context::class.java)
    private val manager = mock(NotificationManager::class.java)
    private val audio = mock(AudioManager::class.java)
    private val prefs = mock(SharedPreferences::class.java)
    private val editor = mock(SharedPreferences.Editor::class.java)
    private val wm = mock(WorkManager::class.java)
    private val notification = mock(Notification::class.java)

    private fun setupSession(expired: Boolean) {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(manager)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
        `when`(context.getSharedPreferences(SilenceStore.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.getInt(SilenceStore.KEY_CAPTURED_MODE, -1)).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(prefs.getInt(SilenceStore.KEY_CAPTURED_VOLUME, -1)).thenReturn(4)
        `when`(prefs.getInt(SilenceStore.KEY_END_VOLUME, 100)).thenReturn(100)
        `when`(prefs.getLong(SilenceStore.KEY_END_TIME, 0L)).thenReturn(System.currentTimeMillis() + if (expired) -60000 else 60000)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.clear()).thenAnswer {
            `when`(prefs.getLong(SilenceStore.KEY_END_TIME, 0L)).thenReturn(0L)
            editor
        }
        `when`(audio.ringerMode).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(audio.getStreamMaxVolume(AudioManager.STREAM_RING)).thenReturn(4)
        `when`(audio.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(4)
    }

    private fun builders() = mockConstruction(NotificationCompat.Builder::class.java,
        withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
        `when`(builder.build()).thenReturn(notification)
    }

    @Test fun `successful expiry posts a quiet dismissible confirmation exactly once`() {
        setupSession(expired = true)
        builders().use { mocked ->
            assertTrue(RingerRestorer.restore(context, wm))
            assertFalse(RingerRestorer.restore(context, wm))
            verify(manager, times(1)).notify(SilenceNotifications.CONFIRMATION_ID, notification)
            val builder = mocked.constructed().single()
            verify(builder).setContentTitle("Ringer turned on")
            verify(builder).setSilent(true)
            verify(builder).setAutoCancel(true)
            verify(builder).setContentText(contains("Ringer turned on at"))
            verify(manager, never()).cancel(SilenceNotifications.CONFIRMATION_ID)
        }
    }

    @Test fun `early cancellation does not claim the expiry ringer turned on`() {
        setupSession(expired = false)
        builders().use {
            assertTrue(RingerRestorer.restore(context, wm))
            verify(manager, never()).notify(eq(SilenceNotifications.CONFIRMATION_ID), any(Notification::class.java))
        }
    }

    @Test fun `failed restoration never posts success and keeps recovery settings`() {
        setupSession(expired = true)
        doThrow(SecurityException("Access revoked")).`when`(audio).ringerMode = anyInt()
        builders().use {
            assertFalse(RingerRestorer.restore(context, wm))
            verify(manager, never()).notify(eq(SilenceNotifications.CONFIRMATION_ID), any(Notification::class.java))
            verify(editor, never()).clear()
        }
    }
}

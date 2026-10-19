package com.example.timedsilence

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.media.AudioManager
import androidx.work.WorkManager
import com.example.timedsilence.util.RingerRestorer
import com.example.timedsilence.util.SilenceStore
import com.example.timedsilence.util.TimedSilenceReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class RingerRestorerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        context = mock(Context::class.java)
        audioManager = mock(AudioManager::class.java)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(context.applicationContext).thenReturn(context)
        var currentMode = AudioManager.RINGER_MODE_NORMAL
        `when`(audioManager.ringerMode).thenAnswer { currentMode }
        doAnswer { currentMode = it.getArgument(0); null }.`when`(audioManager).ringerMode = anyInt()
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)).thenReturn(15)
        var currentVolume = 0
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_RING)).thenAnswer { currentVolume }
        doAnswer { currentVolume = it.getArgument(1); null }.`when`(audioManager)
            .setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test
    fun `restores the ring volume for a ringing mode`() {
        val restored = RingerRestorer.applyRingerState(context, AudioManager.RINGER_MODE_NORMAL, 6)

        assertTrue(restored)
        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
        verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, 6, 0)
    }

    @Test
    fun `keeps vibrate instead of writing a zero ring volume back`() {
        // Replaying a captured volume of 0 would push the device from vibrate
        // straight back into silent, undoing the mode restored on the line before.
        val restored = RingerRestorer.applyRingerState(context, AudioManager.RINGER_MODE_VIBRATE, 0)

        assertTrue(restored)
        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        verify(audioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test
    fun `keeps silent without touching the ring volume`() {
        val restored = RingerRestorer.applyRingerState(context, AudioManager.RINGER_MODE_SILENT, 4)

        assertTrue(restored)
        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_SILENT
        verify(audioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test
    fun `reports failure when the platform refuses the ringer change`() {
        doThrow(SecurityException("DND access revoked")).`when`(audioManager).ringerMode = anyInt()

        assertFalse(RingerRestorer.applyRingerState(context, AudioManager.RINGER_MODE_NORMAL, 6))
    }

    @Test
    fun `does not report success when the ringer setter silently does nothing`() {
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_SILENT)
        doNothing().`when`(audioManager).ringerMode = anyInt()

        assertFalse(RingerRestorer.applyRingerState(context, AudioManager.RINGER_MODE_NORMAL, 6))
    }

    @Test
    fun `does not report success when ring volume stays muted`() {
        doNothing().`when`(audioManager).setStreamVolume(anyInt(), anyInt(), anyInt())

        assertFalse(RingerRestorer.applyRingerState(context, AudioManager.RINGER_MODE_NORMAL, 6))
    }

    @Test
    fun `failed restoration keeps original state and pending retry then clears on success`() {
        val (editor, workManager) = stubSession(AudioManager.RINGER_MODE_NORMAL, 6, System.currentTimeMillis() - 60_000)
        val prefs = context.getSharedPreferences(SilenceStore.PREFS_NAME, Context.MODE_PRIVATE)
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_SILENT)
        doNothing().`when`(audioManager).ringerMode = anyInt()

        assertFalse(RingerRestorer.restore(context, workManager))
        verify(prefs, never()).edit()
        verify(workManager, never()).cancelUniqueWork(org.mockito.ArgumentMatchers.anyString())

        // A later retry can still use the captured settings.
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        assertTrue(RingerRestorer.restore(context, workManager))
        verify(editor).clear()
        verify(workManager).cancelUniqueWork("restoration_work")
    }

    @Test
    fun `expiry always enables audible ringing regardless of starting mode`() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_NORMAL)) {
            setup()
            val (editor, workManager) = stubSession(mode, 0, System.currentTimeMillis() - 60_000)

            assertTrue(RingerRestorer.restore(context, workManager))
            verify(audioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
            verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, 15, 0)
            verify(editor).clear()
        }
    }

    @Test
    fun `early cancel preserves original vibrate mode`() {
        val (_, workManager) = stubSession(AudioManager.RINGER_MODE_VIBRATE, 0, System.currentTimeMillis() + 60_000)

        assertTrue(RingerRestorer.restore(context, workManager))
        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        verify(audioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test
    fun `expiry uses full volume by default instead of captured volume`() {
        val (_, workManager) = stubSession(AudioManager.RINGER_MODE_SILENT, 6, System.currentTimeMillis() - 60_000)

        assertTrue(RingerRestorer.restore(context, workManager))
        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
        verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, 15, 0)
    }

    @Test
    fun `alarm delivered within due tolerance also enables ringing`() {
        stubSession(AudioManager.RINGER_MODE_SILENT, 0, System.currentTimeMillis() + 1_000)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(TimedSilenceReceiver.ACTION_RESTORE)

        TimedSilenceReceiver().onReceive(context, intent)

        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
        verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, 15, 0)
    }

    @Test
    fun `expiry uses the session percentage even if preferences change`() {
        val (_, wm) = stubSession(AudioManager.RINGER_MODE_SILENT, 3, System.currentTimeMillis() - 1000)
        val prefs = context.getSharedPreferences(SilenceStore.PREFS_NAME, Context.MODE_PRIVATE)
        `when`(prefs.getInt(SilenceStore.KEY_END_VOLUME, 100)).thenReturn(50)
        assertTrue(RingerRestorer.restore(context, wm))
        verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, 8, 0)
    }

    @Test
    fun `early cancel restores captured ringing volume instead of ending percentage`() {
        val (_, wm) = stubSession(AudioManager.RINGER_MODE_NORMAL, 3, System.currentTimeMillis() + 60000)
        assertTrue(RingerRestorer.restore(context, wm))
        verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, 3, 0)
    }

    private fun stubSession(mode: Int, volume: Int, endTime: Long): Pair<SharedPreferences.Editor, WorkManager> {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val workManager = mock(WorkManager::class.java)
        `when`(context.getSharedPreferences(SilenceStore.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.getInt(SilenceStore.KEY_CAPTURED_MODE, SilenceStore.NO_MODE))
            .thenReturn(mode)
        `when`(prefs.getInt(SilenceStore.KEY_CAPTURED_VOLUME, SilenceStore.NO_VOLUME)).thenReturn(volume)
        `when`(prefs.getLong(SilenceStore.KEY_END_TIME, 0L)).thenReturn(endTime)
        `when`(prefs.getInt(SilenceStore.KEY_END_VOLUME, 100)).thenReturn(100)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.clear()).thenReturn(editor)
        return editor to workManager
    }
}

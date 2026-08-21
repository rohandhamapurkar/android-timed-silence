package com.example.timedsilence

import android.content.Context
import android.media.AudioManager
import com.example.timedsilence.util.RingerRestorer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
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
}

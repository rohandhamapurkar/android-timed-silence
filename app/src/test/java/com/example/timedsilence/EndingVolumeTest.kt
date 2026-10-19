package com.example.timedsilence

import android.content.Context
import android.content.SharedPreferences
import com.example.timedsilence.util.EndingVolume
import com.example.timedsilence.util.SettingsStore
import com.example.timedsilence.util.SilenceStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class EndingVolumeTest {
    @Test fun `full volume is default and fractional steps round up to audible level`() {
        assertEquals(100, EndingVolume.DEFAULT_PERCENT)
        assertEquals(15, EndingVolume.level(15, 100))
        assertEquals(8, EndingVolume.level(15, 50))
        assertEquals(1, EndingVolume.level(15, 1))
        assertEquals(1, EndingVolume.level(1, 50))
    }

    @Test fun `invalid percentages stay within audible limits`() {
        assertEquals(1, EndingVolume.level(15, -10))
        assertEquals(15, EndingVolume.level(15, 150))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `devices without audible volume steps cannot report successful restoration`() {
        EndingVolume.level(0, 100)
    }

    @Test fun `existing settings and sessions default to full volume`() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(prefs)
        `when`(prefs.getInt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer { it.getArgument<Int>(1) }
        `when`(prefs.getInt(SilenceStore.KEY_CAPTURED_MODE, -1)).thenReturn(2)
        `when`(prefs.getLong(SilenceStore.KEY_END_TIME, 0L)).thenReturn(1000L)
        assertEquals(100, SettingsStore.endingVolumePercent(context))
        assertEquals(100, SilenceStore.read(context)!!.endVolumePercent)
    }
}

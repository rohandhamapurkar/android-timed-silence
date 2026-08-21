package com.example.timedsilence.util

import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit

/**
 * Remembers the duration and mode last used, so a repeat session is one tap.
 *
 * Deliberately a separate file from [SilenceStore]: ending a session clears the
 * session state wholesale, and these choices must survive that.
 */
object SettingsStore {

    const val PREFS_NAME = "timed_silence_settings"
    const val KEY_LAST_DURATION = "last_duration_minutes"
    const val KEY_LAST_MODE = "last_target_mode"

    const val DEFAULT_DURATION_MINUTES = 30
    const val MIN_DURATION_MINUTES = 1
    const val MAX_DURATION_MINUTES = 120

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastDurationMinutes(context: Context): Int =
        prefs(context).getInt(KEY_LAST_DURATION, DEFAULT_DURATION_MINUTES)
            .coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)

    fun lastTargetMode(context: Context): Int {
        val mode = prefs(context).getInt(KEY_LAST_MODE, AudioManager.RINGER_MODE_VIBRATE)
        return if (mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE) {
            mode
        } else {
            AudioManager.RINGER_MODE_VIBRATE
        }
    }

    fun remember(context: Context, durationMinutes: Int, targetMode: Int) {
        prefs(context).edit {
            putInt(KEY_LAST_DURATION, durationMinutes)
            putInt(KEY_LAST_MODE, targetMode)
        }
    }
}

package com.example.timedsilence.util

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.core.content.edit

/**
 * The ringer state captured when a silence session started, plus the moment the
 * session is due to end.
 */
data class SilenceSession(
    val originalMode: Int,
    val originalVolume: Int,
    val endTimeMillis: Long,
    /** The mode the device was put into, for display only. [SilenceStore.NO_MODE] when unknown. */
    val targetMode: Int = SilenceStore.NO_MODE
) {
    /** True once [endTimeMillis] has passed. [nowMillis] may be nudged forward to allow a trigger to fire slightly early. */
    fun isDue(nowMillis: Long = System.currentTimeMillis()): Boolean = endTimeMillis <= nowMillis
}

/**
 * Disk backed state for the session currently in progress.
 *
 * Restoration has to happen even when the app is closed, swapped out of memory
 * or the device is locked, so no component keeps its own copy of the captured
 * ringer state: the alarm, the WorkManager backstop, the notification actions
 * and the UI all read the session from here. That also means an "extend" made
 * from the notification is honoured by whichever trigger ends up restoring.
 *
 * Last-used UI choices live in [SettingsStore] instead, so ending a session
 * cannot wipe them.
 */
object SilenceStore {

    const val PREFS_NAME = "timed_silence_prefs"
    const val KEY_CAPTURED_MODE = "captured_mode"
    const val KEY_CAPTURED_VOLUME = "captured_volume"
    const val KEY_END_TIME = "end_time_millis"
    const val KEY_TARGET_MODE = "target_mode"

    /** Placeholder for a ringer mode that was not recorded. */
    const val NO_MODE = -1

    /** Captured volume placeholder used when the ring stream could not be read. */
    const val NO_VOLUME = -1

    private val VALID_MODES = setOf(
        AudioManager.RINGER_MODE_SILENT,
        AudioManager.RINGER_MODE_VIBRATE,
        AudioManager.RINGER_MODE_NORMAL
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, session: SilenceSession) {
        prefs(context).edit {
            putInt(KEY_CAPTURED_MODE, session.originalMode)
            putInt(KEY_CAPTURED_VOLUME, session.originalVolume)
            putLong(KEY_END_TIME, session.endTimeMillis)
            putInt(KEY_TARGET_MODE, session.targetMode)
        }
    }

    /**
     * The session still awaiting restoration, or null when there is nothing to
     * restore. Partially written state (a mode without a deadline, or vice
     * versa) is treated as "no session" rather than restored blindly.
     */
    fun read(context: Context): SilenceSession? {
        val prefs = prefs(context)
        val mode = prefs.getInt(KEY_CAPTURED_MODE, NO_MODE)
        val endTime = prefs.getLong(KEY_END_TIME, 0L)
        if (mode !in VALID_MODES || endTime <= 0L) return null
        return SilenceSession(
            originalMode = mode,
            originalVolume = prefs.getInt(KEY_CAPTURED_VOLUME, NO_VOLUME),
            endTimeMillis = endTime,
            targetMode = prefs.getInt(KEY_TARGET_MODE, NO_MODE)
        )
    }

    fun updateEndTime(context: Context, endTimeMillis: Long) {
        prefs(context).edit { putLong(KEY_END_TIME, endTimeMillis) }
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }
}

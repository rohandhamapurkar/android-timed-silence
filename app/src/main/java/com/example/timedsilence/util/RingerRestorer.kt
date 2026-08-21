package com.example.timedsilence.util

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.work.WorkManager

/**
 * The single implementation of "put the ringer back the way we found it".
 *
 * Every trigger - the exact alarm, the WorkManager backstop, the notification's
 * Stop action and the in-app Cancel button - funnels through here so they can
 * never disagree, and so a session can only be torn down once.
 */
object RingerRestorer {

    private const val TAG = "RingerRestorer"

    /**
     * Restores the captured ringer state and tears the session down.
     *
     * Idempotent: once the stored session is gone this is a no-op, so it does not
     * matter whether the alarm or the backstop worker gets there first.
     *
     * @param tearDownOnFailure clears the session even when the ringer could not be
     * written. Used by the explicit user cancel; the background triggers leave the
     * session armed instead so a later attempt can still recover.
     * @return true when the ringer was actually restored.
     */
    fun restore(
        context: Context,
        workManager: WorkManager? = null,
        tearDownOnFailure: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext
        val session = SilenceStore.read(appContext)
        if (session == null) {
            // Nothing outstanding - just make sure no stale notification is left behind.
            SilenceNotifications.cancel(appContext)
            return false
        }

        val applied = applyRingerState(appContext, session.originalMode, session.originalVolume)
        if (!applied && !tearDownOnFailure) {
            Log.w(TAG, "Ringer restore failed, leaving the session armed for another attempt")
            return false
        }

        // Clear the session first: from here on any other trigger that fires is a no-op.
        SilenceStore.clear(appContext)
        SilenceNotifications.cancel(appContext)
        RestorationScheduler.cancel(appContext, workManager)
        return applied
    }

    /** Writes [mode] (and, where meaningful, [volume]) back to the ring stream. */
    fun applyRingerState(context: Context, mode: Int, volume: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.e(TAG, "No AudioManager available, cannot restore the ringer")
            return false
        }
        return try {
            audioManager.ringerMode = mode
            // Only replay a ring volume for a mode that actually rings, and only when
            // it is audible: writing 0 to STREAM_RING pushes the device straight back
            // into vibrate/silent and undoes the ringer mode that was just set, which
            // left the phone silent after any session started from vibrate or silent.
            if (mode == AudioManager.RINGER_MODE_NORMAL && volume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
            }
            Log.d(TAG, "Restored ringer mode=$mode volume=$volume")
            true
        } catch (e: SecurityException) {
            // Happens when Notification Policy (DND) access was revoked mid-session.
            Log.e(TAG, "Not allowed to change the ringer, is DND access still granted?", e)
            false
        }
    }
}

package com.example.timedsilence.util

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.work.WorkManager

/**
 * Ends silence: expiry enables ringing; an early stop restores the captured state.
 *
 * Every trigger - the exact alarm, the WorkManager backstop, the notification's
 * Stop action and the in-app Cancel button - funnels through here so they can
 * never disagree, and so a session can only be torn down once.
 */
object RingerRestorer {

    private const val TAG = "RingerRestorer"

    /**
     * Enables the ringer at expiry, or restores the captured state on an early stop.
     *
     * Idempotent: once the stored session is gone this is a no-op, so it does not
     * matter whether the alarm or the backstop worker gets there first.
     *
     * @param forceRinging used by expiry triggers delivered within their early tolerance.
     * @return true when the ringer was actually restored.
     */
    @Synchronized
    fun restore(
        context: Context,
        workManager: WorkManager? = null,
        forceRinging: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext
        val session = SilenceStore.read(appContext)
        if (session == null) {
            // Nothing outstanding - just make sure no stale notification is left behind.
            SilenceNotifications.cancel(appContext)
            return false
        }

        val expired = forceRinging || session.isDue()
        val mode = if (expired) AudioManager.RINGER_MODE_NORMAL else session.originalMode
        val applied = applyRingerState(appContext, mode, session.originalVolume,
            endingVolumePercent = if (expired) session.endVolumePercent else null)
        if (!applied) {
            Log.w(TAG, "Ringer restore failed, leaving the session armed for another attempt")
            SilenceNotifications.showRestorationFailed(appContext)
            return false
        }

        // Clear the session first: from here on any other trigger that fires is a no-op.
        SilenceStore.clear(appContext)
        SilenceNotifications.cancel(appContext)
        RestorationScheduler.cancel(appContext, workManager)
        if (expired) SilenceNotifications.showRestored(appContext)
        return applied
    }

    /** Writes [mode] (and, where meaningful, [volume]) back to the ring stream. */
    fun applyRingerState(context: Context, mode: Int, volume: Int, endingVolumePercent: Int? = null): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.e(TAG, "No AudioManager available, cannot restore the ringer")
            return false
        }
        return try {
            var expectedVolume: Int? = null
            audioManager.ringerMode = mode
            // Sessions can start with ring volume zero. Expiry must still be
            // audible, so keep an existing positive volume or use the lowest
            // audible level instead of writing zero and re-entering silent mode.
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                val audibleVolume = if (endingVolumePercent != null)
                    EndingVolume.level(audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), endingVolumePercent)
                else if (volume > 0) volume else
                    audioManager.getStreamVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, audibleVolume, 0)
                expectedVolume = audibleVolume
            }
            // A setter can return without changing the mode (for example when
            // the device's audio policy rejects it). Keep the recovery record
            // until a read-back confirms that the requested mode took effect.
            if (audioManager.ringerMode != mode ||
                (expectedVolume != null && audioManager.getStreamVolume(AudioManager.STREAM_RING) != expectedVolume)) {
                Log.w(TAG, "Ringer did not reach the requested audible state for mode=$mode")
                return false
            }
            Log.d(TAG, "Restored ringer mode=$mode volume=$volume")
            true
        } catch (e: RuntimeException) {
            // Includes revoked DND access and failures from the device audio service.
            Log.e(TAG, "Could not change the ringer", e)
            false
        }
    }
}

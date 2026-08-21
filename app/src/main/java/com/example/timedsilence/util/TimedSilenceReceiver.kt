package com.example.timedsilence.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Handles the exact restoration alarm and the ongoing notification's actions.
 *
 * This runs without the app being open: the alarm is delivered to the receiver
 * even when the process has been killed and the device is locked.
 */
class TimedSilenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESTORE -> onRestoreAlarm(context)
            ACTION_STOP -> {
                Log.d(TAG, "Restoring early from the notification action")
                RingerRestorer.restore(context, tearDownOnFailure = true)
            }
            ACTION_EXTEND -> onExtend(context)
            else -> Log.w(TAG, "Ignoring unexpected action ${intent.action}")
        }
    }

    private fun onRestoreAlarm(context: Context) {
        val session = SilenceStore.read(context)
        if (session == null) {
            // Already restored by another trigger.
            SilenceNotifications.cancel(context)
            return
        }
        if (!session.isDue(System.currentTimeMillis() + DUE_TOLERANCE_MILLIS)) {
            // The session was extended after this alarm was armed. Re-arm for the
            // new deadline instead of ending the session early.
            Log.d(TAG, "Alarm fired early, re-arming for ${session.endTimeMillis}")
            RestorationScheduler.schedule(context, session.endTimeMillis)
            SilenceNotifications.showOngoing(context, session.endTimeMillis)
            return
        }
        Log.d(TAG, "Restoration alarm fired, restoring ringer")
        RingerRestorer.restore(context)
    }

    private fun onExtend(context: Context) {
        val session = SilenceStore.read(context) ?: return
        // Extend from the current deadline, or from now if it has already passed.
        val base = maxOf(session.endTimeMillis, System.currentTimeMillis())
        val newEndTime = base + TimeUnit.MINUTES.toMillis(SilenceNotifications.EXTENSION_MINUTES.toLong())

        SilenceStore.updateEndTime(context, newEndTime)
        RestorationScheduler.schedule(context, newEndTime)
        SilenceNotifications.showOngoing(context, newEndTime)
        Log.d(TAG, "Extended silence to $newEndTime")
    }

    companion object {
        const val ACTION_RESTORE = "com.example.timedsilence.ACTION_RESTORE"
        const val ACTION_STOP = "com.example.timedsilence.ACTION_STOP"
        const val ACTION_EXTEND = "com.example.timedsilence.ACTION_EXTEND"

        /** Alarms may be delivered a moment early; treat that as "due". */
        private const val DUE_TOLERANCE_MILLIS = 2_000L
        private const val TAG = "TimedSilenceReceiver"
    }
}

package com.example.timedsilence.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms an in-flight session after events that drop pending alarms.
 *
 * Alarms do not survive a reboot (or an app update), and a wall-clock change
 * moves the deadline relative to the alarm that was set for it. The ringer mode
 * itself does survive a reboot, so without this the phone would stay silent for
 * good after restarting mid-session.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val session = SilenceStore.read(context) ?: return
        if (session.isDue()) {
            Log.d(TAG, "Session already expired during ${intent.action}, restoring now")
            RingerRestorer.restore(context)
        } else {
            Log.d(TAG, "Re-arming restoration for ${session.endTimeMillis} after ${intent.action}")
            RestorationScheduler.schedule(context, session.endTimeMillis)
            SilenceNotifications.createChannel(context)
            SilenceNotifications.showOngoing(context, session.endTimeMillis)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

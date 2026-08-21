package com.example.timedsilence.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.timedsilence.util.RestorationScheduler
import com.example.timedsilence.util.RingerRestorer
import com.example.timedsilence.util.SilenceNotifications
import com.example.timedsilence.util.SilenceStore

/**
 * Backstop for the exact restoration alarm.
 *
 * The alarm in [RestorationScheduler] is what makes restoration land on time;
 * this worker exists so the ringer still comes back if that alarm is dropped, and
 * because WorkManager re-queues it automatically after a reboot. It reads the
 * session from [SilenceStore] rather than from its input data so that an extend
 * made after the job was queued is honoured.
 */
class RingerRestorationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = SilenceStore.read(applicationContext)
        if (session == null) {
            // The alarm already restored the ringer and cleared the session.
            SilenceNotifications.cancel(applicationContext)
            return Result.success()
        }

        // WorkManager's delay is only a lower bound, and the session may have been
        // extended since this job was queued - re-arm rather than restoring early.
        if (!session.isDue(System.currentTimeMillis() + DUE_TOLERANCE_MILLIS)) {
            Log.d(TAG, "Ran before the session was due, re-arming for ${session.endTimeMillis}")
            RestorationScheduler.schedule(applicationContext, session.endTimeMillis)
            return Result.success()
        }

        // Deliberately no Notification Policy check here: bailing out when DND
        // access had been revoked left the phone silent forever. Attempt the
        // restore and let WorkManager retry if the platform refuses.
        return if (RingerRestorer.restore(applicationContext)) {
            Result.success()
        } else {
            Log.w(TAG, "Could not restore the ringer, scheduling a retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RingerRestorationWorker"
        private const val DUE_TOLERANCE_MILLIS = 2_000L
    }
}

package com.example.timedsilence.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.timedsilence.worker.RingerRestorationWorker
import java.util.concurrent.TimeUnit

/**
 * Arms the restoration triggers for a session.
 *
 * Two independent triggers are used because neither is sufficient on its own:
 *
 *  - An exact [AlarmManager] alarm is the primary trigger. It fires at the
 *    requested minute even when the screen is locked, the device is dozing or the
 *    app process has been killed. WorkManager's initial delay only promises "no
 *    earlier than": the system defers a deferrable job for many minutes while the
 *    device is idle, which is exactly the state a silenced phone sits in, so on
 *    its own it let the ringer come back long after the timer had expired.
 *  - A WorkManager job as a backstop. It is restored by WorkManager's own boot
 *    handling and covers the case where the alarm is dropped.
 *
 * Both funnel into [RingerRestorer], which is idempotent.
 */
object RestorationScheduler {

    const val UNIQUE_WORK_NAME = "restoration_work"
    const val TAG_RESTORATION = "ringer_restoration"

    private const val TAG = "RestorationScheduler"
    private const val ALARM_REQUEST_CODE = 2001

    /** Arms both triggers for [endTimeMillis], replacing anything already scheduled. */
    fun schedule(context: Context, endTimeMillis: Long, workManager: WorkManager? = null) {
        val appContext = context.applicationContext
        scheduleAlarm(appContext, endTimeMillis)
        scheduleBackstopWork(appContext, endTimeMillis, workManager)
    }

    /** Disarms both triggers. Safe to call when nothing is scheduled. */
    fun cancel(context: Context, workManager: WorkManager? = null) {
        val appContext = context.applicationContext
        cancelAlarm(appContext)
        runCatching {
            (workManager ?: WorkManager.getInstance(appContext)).cancelUniqueWork(UNIQUE_WORK_NAME)
        }.onFailure { Log.w(TAG, "Could not cancel the backstop work", it) }
    }

    private fun scheduleAlarm(context: Context, endTimeMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = restorePendingIntent(context) ?: return
        val canBeExact = runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
            } else {
                // Without the exact alarm permission an idle-friendly inexact alarm is
                // the best available option; it can land a few minutes late, and the
                // WorkManager backstop still applies.
                Log.w(TAG, "Exact alarms are not permitted, restoration may be late")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
            }
        }.onFailure { error ->
            Log.e(TAG, "Could not schedule the exact restoration alarm", error)
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
            }.onFailure { Log.e(TAG, "Could not schedule any restoration alarm", it) }
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = restorePendingIntent(context) ?: return
        runCatching {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }.onFailure { Log.w(TAG, "Could not cancel the restoration alarm", it) }
    }

    private fun scheduleBackstopWork(context: Context, endTimeMillis: Long, workManager: WorkManager?) {
        val delayMillis = (endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<RingerRestorationWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(TAG_RESTORATION)
            .build()
        runCatching {
            (workManager ?: WorkManager.getInstance(context))
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "Could not enqueue the backstop work", it) }
    }

    private fun restorePendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, TimedSilenceReceiver::class.java).apply {
            action = TimedSilenceReceiver.ACTION_RESTORE
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

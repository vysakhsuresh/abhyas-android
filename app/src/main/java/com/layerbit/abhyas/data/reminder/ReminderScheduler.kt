package com.layerbit.abhyas.data.reminder

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Starts and stops the daily reminder.
 *
 * Deliberately a chain of one-shot jobs rather than a PeriodicWorkRequest. A periodic request
 * cannot be anchored to a wall-clock time - it starts its period whenever it was enqueued and
 * drifts from there - so "remind me at 7pm" would slowly become "remind me at some point in the
 * evening, eventually". Each run schedules the next one for the following day's chosen time,
 * which keeps it pinned however long the device sleeps in between.
 */
object ReminderScheduler {

    private const val WORK_NAME = "abhyas-daily-reminder"

    /**
     * Schedule the next reminder. Safe to call repeatedly - REPLACE means changing the time
     * cancels the pending job rather than leaving two.
     *
     * Callers must have checked consent first. This does not look at [ReminderPreferences.enabled]
     * on purpose: a scheduler that silently ignores its own instructions is very hard to debug,
     * so the decision lives with the one caller that owns it.
     */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val delay = millisUntilNext(hour, minute)

        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stop reminding. Called the moment the user switches them off. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * How long until the next occurrence of [hour]:[minute] in the device's own time zone.
     *
     * If that time has already passed today, it is tomorrow - which is also what makes the
     * chain self-correcting: a worker that ran late still schedules the next one for the right
     * time rather than compounding the drift.
     */
    fun millisUntilNext(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now
    }
}

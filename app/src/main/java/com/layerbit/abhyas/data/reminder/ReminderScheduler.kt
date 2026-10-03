package com.layerbit.abhyas.data.reminder

import android.content.Context
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
 *
 * The chain is identified by a **tag** rather than by unique work, and that distinction is the
 * whole reason there are two scheduling methods. Unique work cancels whatever already holds the
 * name, and the worker that re-arms the chain is itself that holder - so re-arming under the same
 * name killed the very run that was doing it, usually before it had posted anything. See
 * [scheduleNext].
 */
object ReminderScheduler {

    private const val WORK_NAME = "abhyas-daily-reminder"

    /**
     * Arm the chain for the next occurrence of [hour]:[minute], discarding any job already pending.
     *
     * For callers outside the worker: the settings toggle, a time change, and app launch. Safe to
     * call repeatedly - it leaves exactly one pending job, re-anchored to the wall clock, which is
     * also what corrects the chain after a timezone or DST change.
     *
     * Callers must have checked consent first. This does not look at [ReminderPreferences.enabled]
     * on purpose: a scheduler that silently ignores its own instructions is very hard to debug,
     * so the decision lives with the one caller that owns it.
     */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val manager = WorkManager.getInstance(context)
        // Operations are serialised on WorkManager's own task executor, so the cancel is applied
        // before the enqueue below rather than racing it.
        manager.cancelAllWorkByTag(WORK_NAME)
        manager.enqueue(request(hour, minute))
    }

    /**
     * Arm tomorrow's run from inside the currently running worker.
     *
     * Identical to [schedule] except that it cancels nothing. The running worker carries the
     * chain's tag, so cancelling first would cancel *itself*: WorkManager would interrupt the
     * coroutine mid-`doWork`, the notification would never be posted, and the reminder would look
     * scheduled forever while never arriving. There is nothing else pending to replace anyway -
     * the job that was pending is the one now executing.
     */
    fun scheduleNext(context: Context, hour: Int, minute: Int) {
        WorkManager.getInstance(context).enqueue(request(hour, minute))
    }

    /** Stop reminding. Called the moment the user switches them off. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
    }

    private fun request(hour: Int, minute: Int) =
        OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(millisUntilNext(hour, minute), TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()

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

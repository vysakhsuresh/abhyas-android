package com.layerbit.abhyas.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.layerbit.abhyas.MainActivity
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.repo.AbhyasRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Posts the daily reminder, then schedules tomorrow's.
 *
 * Three things this refuses to do, all of them the reason reminders get switched off for good:
 *
 *  - **Fire when there is nothing to study.** A notification that leads to an empty deck teaches
 *    people to dismiss the next one without reading it.
 *  - **Fire when the work is already done.** Somebody who studied this morning does not need
 *    telling at seven in the evening.
 *  - **Fire twice.** WorkManager promises a job runs at least once, not exactly once, so a
 *    device waking from a long sleep can run a deferred job immediately.
 *
 * It re-checks consent on every run too. Permission can be revoked from system settings without
 * the app ever being opened again, and the honest response to that is to stop.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = ReminderPreferences(applicationContext)

        // Consent can disappear between scheduling and firing - the user revoked the permission,
        // or turned reminders off while a job was already queued. Simply declining to re-arm ends
        // the chain; cancelling here would cancel this very run, which is a confusing way to
        // achieve nothing extra.
        if (!prefs.enabled || !hasNotificationPermission()) return Result.success()

        // Chain tomorrow's run first, so an early return below cannot break the chain and
        // silently end the reminders the user asked for. scheduleNext rather than schedule: this
        // worker carries the chain's tag, and replacing the chain from inside it would interrupt
        // the coroutine before the notification below was ever posted.
        ReminderScheduler.scheduleNext(applicationContext, prefs.hour, prefs.minute)

        val today = TODAY.format(Date())
        if (prefs.lastFiredOn == today) return Result.success()

        val repository = AbhyasRepository(applicationContext)
        val waiting = repository.totalWaitingNow()
        if (waiting <= 0) return Result.success()

        if (repository.reviewedToday() > 0) return Result.success()

        notify(waiting)
        prefs.lastFiredOn = today
        return Result.success()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun notify(waiting: Int) {
        val manager = NotificationManagerCompat.from(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Daily reminder",
                    // Low: it belongs in the shade, not over whatever the user is doing.
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "A nudge when you have cards waiting."
                }
            )
        }

        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (waiting == 1) "1 card is waiting" else "$waiting cards are waiting")
            .setContentText("A few minutes now is worth an hour later.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        // Belt and braces: the permission was checked above, but NotificationManagerCompat can
        // still throw if it was revoked in between, and a crash inside a background worker is
        // both invisible and fatal to the chain.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "daily-reminder"
        const val NOTIFICATION_ID = 1

        val TODAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

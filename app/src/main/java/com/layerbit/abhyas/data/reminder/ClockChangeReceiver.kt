package com.layerbit.abhyas.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-anchors the daily reminder when the device's clock moves under it.
 *
 * The reminder is a one-shot job carrying a fixed millisecond delay, computed from the time zone in
 * force when it was scheduled. That is correct across a reboot - WorkManager persists the enqueue
 * time and reschedules the remainder - but not across a change to the clock itself. Fly from Delhi
 * to London with a 7pm reminder pending and it arrives at half past two in the afternoon; a DST
 * boundary shifts it by an hour.
 *
 * Re-computing the delay from the stored hour and minute puts it back on the wall clock the user
 * actually chose. Nothing is scheduled for someone who has reminders switched off.
 */
class ClockChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = ReminderPreferences(context)
        if (!prefs.enabled) return

        // schedule, not scheduleNext: no worker of ours is running in a receiver, so replacing the
        // pending job is both safe and the point - there must be exactly one, at the new instant.
        ReminderScheduler.schedule(context, prefs.hour, prefs.minute)
    }
}

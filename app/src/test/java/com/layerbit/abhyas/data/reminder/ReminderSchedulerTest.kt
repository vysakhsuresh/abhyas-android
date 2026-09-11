package com.layerbit.abhyas.data.reminder

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The delay arithmetic behind the daily reminder.
 *
 * Worth pinning down because the failure is invisible in the worst way: a reminder scheduled to
 * the wrong time still fires, just not when the user asked, and nobody reports that as a bug -
 * they just turn reminders off.
 */
class ReminderSchedulerTest {

    /**
     * Pinned to UTC. The scheduler deliberately works in the device's own zone, but a test
     * asserting "exactly ten hours" would fail on a machine whose clocks happened to change that
     * night - a red build caused by the calendar rather than the code.
     */
    @Before
    fun useFixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(null)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `a time later today is scheduled for today`() {
        val now = at(2026, 3, 15, 9, 0)
        val delay = ReminderScheduler.millisUntilNext(19, 0, now)

        assertEquals(TimeUnit.HOURS.toMillis(10), delay)
    }

    @Test
    fun `a time already passed today is scheduled for tomorrow`() {
        val now = at(2026, 3, 15, 21, 0)
        val delay = ReminderScheduler.millisUntilNext(19, 0, now)

        assertEquals(TimeUnit.HOURS.toMillis(22), delay)
    }

    @Test
    fun `the exact reminder minute schedules the next day, never zero`() {
        // A zero delay would fire the moment the chain is re-armed, and the worker re-arms itself
        // immediately after firing - so this is what stops a reminder loop.
        val now = at(2026, 3, 15, 19, 0)
        val delay = ReminderScheduler.millisUntilNext(19, 0, now)

        assertEquals(TimeUnit.DAYS.toMillis(1), delay)
    }

    @Test
    fun `the delay is always positive and within a day`() {
        val now = at(2026, 3, 15, 13, 37)

        for (hour in 0..23) {
            for (minute in listOf(0, 30, 59)) {
                val delay = ReminderScheduler.millisUntilNext(hour, minute, now)
                assertTrue("delay must be positive for $hour:$minute", delay > 0)
                assertTrue(
                    "delay must never exceed a day for $hour:$minute",
                    delay <= TimeUnit.DAYS.toMillis(1)
                )
            }
        }
    }

    @Test
    fun `a midnight reminder just after midnight waits nearly a full day`() {
        val now = at(2026, 3, 15, 0, 1)
        val delay = ReminderScheduler.millisUntilNext(0, 0, now)

        assertEquals(TimeUnit.DAYS.toMillis(1) - TimeUnit.MINUTES.toMillis(1), delay)
    }
}

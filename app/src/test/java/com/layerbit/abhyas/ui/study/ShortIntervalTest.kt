package com.layerbit.abhyas.ui.study

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four words under the grade buttons.
 *
 * This is the one number a user weighs before every single answer, and it had shipped reading
 * "0d" under three of the four buttons: the preview was formatted from a whole-day interval, and
 * a card in learning is due back in minutes. Three identical, meaningless labels on the reviews a
 * new card spends most of its life in - and nothing failed, because nothing watched this.
 */
class ShortIntervalTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `a card in learning reads in minutes, never as zero days`() {
        assertEquals("1m", shortInterval(minute))
        assertEquals("10m", shortInterval(10 * minute))
        assertEquals("59m", shortInterval(59 * minute))
    }

    @Test
    fun `an interval too short to name still says something`() {
        // Rounds to nothing, but a blank label under a button is worse than an honest "<1m".
        assertEquals("<1m", shortInterval(0))
        assertEquals("<1m", shortInterval(30_000))
    }

    @Test
    fun `an hour or more reads in hours`() {
        assertEquals("1h", shortInterval(hour))
        assertEquals("4h", shortInterval(4 * hour))
        // Floored, so the row never prints "24h" - a unit that means a day and is used nowhere else.
        assertEquals("23h", shortInterval(23 * hour + 30 * minute))
    }

    @Test
    fun `a day or more reads in days`() {
        assertEquals("1d", shortInterval(day))
        assertEquals("6d", shortInterval(6 * day))
    }

    @Test
    fun `a week or more reads in weeks`() {
        assertEquals("1w", shortInterval(7 * day))
        assertEquals("3w", shortInterval(21 * day))
    }

    @Test
    fun `a month or more reads in months`() {
        assertEquals("1mo", shortInterval(30 * day))
        assertEquals("4mo", shortInterval(120 * day))
    }

    @Test
    fun `a year or more reads in years`() {
        assertEquals("1.0y", shortInterval(365 * day))
        assertEquals("2.5y", shortInterval(912 * day))
    }

    @Test
    fun `the unit boundaries do not fall through a gap`() {
        // Every branch hands over to the next without a value landing between them.
        assertEquals("59m", shortInterval(hour - minute))
        assertEquals("1h", shortInterval(hour))
        assertEquals("23h", shortInterval(day - hour))
        assertEquals("1d", shortInterval(day))
        assertEquals("6d", shortInterval(7 * day - minute))
        assertEquals("1w", shortInterval(7 * day))
        assertEquals("4w", shortInterval(30 * day - minute))
        assertEquals("1mo", shortInterval(30 * day))
    }

    @Test
    fun `the longest interval stays short enough to fit under a button`() {
        // Scheduler.MAXIMUM_INTERVAL_DAYS is 1825, so this is the widest label the row ever holds.
        assertEquals("5.0y", shortInterval(1825 * day))
    }
}

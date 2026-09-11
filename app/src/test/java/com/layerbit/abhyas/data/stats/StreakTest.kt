package com.layerbit.abhyas.data.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakTest {

    private val today = LocalDate.of(2026, 3, 15)
    private fun days(vararg offsets: Long) = offsets.map { today.minusDays(it) }

    @Test
    fun `no history is no streak`() {
        assertEquals(0, Streak.current(emptyList(), today))
    }

    @Test
    fun `consecutive days ending today count`() {
        assertEquals(3, Streak.current(days(0, 1, 2), today))
    }

    @Test
    fun `a streak survives until a full day is missed`() {
        // The behaviour this whole class exists for. At 9am you have not studied today yet, and
        // showing a zero at exactly the hour someone decides whether to bother is how a streak
        // counter talks people out of their own habit.
        assertEquals(3, Streak.current(days(1, 2, 3), today))
    }

    @Test
    fun `missing two days ends it`() {
        assertEquals(0, Streak.current(days(2, 3, 4), today))
    }

    @Test
    fun `a gap stops the count rather than being skipped over`() {
        // Studied today, yesterday, then nothing, then three days before that.
        assertEquals(2, Streak.current(days(0, 1, 3, 4, 5), today))
    }

    @Test
    fun `a single day today is a streak of one`() {
        assertEquals(1, Streak.current(days(0), today))
    }

    @Test
    fun `duplicate days do not inflate the count`() {
        val withDupes = days(0, 0, 1, 1, 1, 2)
        assertEquals(3, Streak.current(withDupes, today))
    }

    @Test
    fun `order does not matter`() {
        assertEquals(4, Streak.current(days(3, 0, 2, 1), today))
    }

    @Test
    fun `studiedToday reports whether the day's work is done`() {
        assertTrue(Streak.studiedToday(days(0, 1), today))
        assertFalse(Streak.studiedToday(days(1, 2), today))
    }

    @Test
    fun `a streak spanning a month boundary is counted correctly`() {
        val firstOfMarch = LocalDate.of(2026, 3, 1)
        val spanning = listOf(
            firstOfMarch,
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 2, 27)
        )
        assertEquals(3, Streak.current(spanning, firstOfMarch))
    }

    @Test
    fun `unparseable dates are dropped rather than thrown`() {
        val parsed = Streak.parseDays(listOf("2026-03-15", "not-a-date", "", "2026-03-14"))

        assertEquals(2, parsed.size)
        assertEquals(2, Streak.current(parsed, today))
    }
}

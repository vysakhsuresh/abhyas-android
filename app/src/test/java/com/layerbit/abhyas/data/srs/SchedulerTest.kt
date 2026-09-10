package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler decides when a user sees a card again, and a mistake here is close to invisible:
 * nobody notices an interval that is 30% too long until months of studying have been wasted on
 * it. So the arithmetic is pinned down here rather than trusted.
 */
class SchedulerTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 86_400_000L

    private fun newCard() = Scheduling()

    // ------------------------------------------------------------------------------- learning

    @Test
    fun `a new card answered Good moves to the second learning step, not to days`() {
        val result = Scheduler.next(newCard(), Grade.GOOD, now)

        assertEquals(CardState.LEARNING, result.state)
        assertEquals(1, result.learningStep)
        assertEquals(now + 10 * minute, result.dueAt)
    }

    @Test
    fun `Good twice graduates the card to a one day interval`() {
        val first = Scheduler.next(newCard(), Grade.GOOD, now)
        val second = Scheduler.next(first, Grade.GOOD, now)

        assertEquals(CardState.REVIEW, second.state)
        assertEquals(Scheduler.GRADUATING_INTERVAL_DAYS, second.intervalDays)
        assertEquals(now + day, second.dueAt)
    }

    @Test
    fun `Easy on a new card skips the remaining steps`() {
        val result = Scheduler.next(newCard(), Grade.EASY, now)

        assertEquals(CardState.REVIEW, result.state)
        assertEquals(Scheduler.EASY_INTERVAL_DAYS, result.intervalDays)
    }

    @Test
    fun `Again during learning returns to the first step`() {
        val advanced = Scheduler.next(newCard(), Grade.GOOD, now)
        val lapsed = Scheduler.next(advanced, Grade.AGAIN, now)

        assertEquals(CardState.LEARNING, lapsed.state)
        assertEquals(0, lapsed.learningStep)
        assertEquals(now + minute, lapsed.dueAt)
    }

    // --------------------------------------------------------------------------------- review

    @Test
    fun `a review card answered Good grows by its ease factor`() {
        val card = Scheduling(
            state = CardState.REVIEW,
            intervalDays = 10,
            easeFactor = 2.5,
            repetitions = 3
        )

        val result = Scheduler.next(card, Grade.GOOD, now)

        // 10 * 2.5 = 25. Good is SM-2's q=4, whose delta works out to exactly zero, so ease is
        // deliberately unchanged - only Hard and Easy move it.
        assertEquals(25, result.intervalDays)
        assertEquals(2.5, result.easeFactor, 0.001)
        assertEquals(4, result.repetitions)
    }

    @Test
    fun `Hard grows slowly and independently of ease`() {
        val card = Scheduling(state = CardState.REVIEW, intervalDays = 10, easeFactor = 2.5)

        val result = Scheduler.next(card, Grade.HARD, now)

        assertEquals(12, result.intervalDays)
        assertTrue("Hard must reduce ease", result.easeFactor < 2.5)
    }

    @Test
    fun `Easy earns a bonus over Good`() {
        val card = Scheduling(state = CardState.REVIEW, intervalDays = 10, easeFactor = 2.5)

        val good = Scheduler.next(card, Grade.GOOD, now)
        val easy = Scheduler.next(card, Grade.EASY, now)

        assertTrue("Easy must schedule further out than Good", easy.intervalDays > good.intervalDays)
        assertTrue("Easy must raise ease", easy.easeFactor > 2.5)
    }

    // ---------------------------------------------------------------------------------- lapses

    @Test
    fun `Again on a review card halves the interval and starts relearning`() {
        val card = Scheduling(
            state = CardState.REVIEW,
            intervalDays = 20,
            easeFactor = 2.5,
            repetitions = 5,
            lapses = 1
        )

        val result = Scheduler.next(card, Grade.AGAIN, now)

        assertEquals(CardState.RELEARNING, result.state)
        assertEquals(10, result.intervalDays)
        assertEquals(2, result.lapses)
        assertEquals(0, result.repetitions)
        // Comes back in minutes, in this same session - not tomorrow.
        assertEquals(now + 10 * minute, result.dueAt)
    }

    @Test
    fun `relearning returns to review at the reduced interval, not the graduating one`() {
        val lapsed = Scheduler.next(
            Scheduling(state = CardState.REVIEW, intervalDays = 20, easeFactor = 2.5),
            Grade.AGAIN,
            now
        )

        val recovered = Scheduler.next(lapsed, Grade.GOOD, now)

        assertEquals(CardState.REVIEW, recovered.state)
        assertEquals("a lapse must not reset months of progress to one day", 10, recovered.intervalDays)
    }

    // ----------------------------------------------------------------------------------- bounds

    @Test
    fun `ease never falls below the SuperMemo floor`() {
        var card = Scheduling(state = CardState.REVIEW, intervalDays = 5, easeFactor = 1.4)
        repeat(10) { card = Scheduler.next(card, Grade.HARD, now) }

        assertTrue(card.easeFactor >= Scheduler.MINIMUM_EASE)
    }

    @Test
    fun `intervals are capped so a card is never scheduled out of existence`() {
        var card = Scheduling(state = CardState.REVIEW, intervalDays = 1000, easeFactor = 2.5)
        repeat(10) { card = Scheduler.next(card, Grade.EASY, now) }

        assertEquals(Scheduler.MAXIMUM_INTERVAL_DAYS, card.intervalDays)
    }

    @Test
    fun `a lapsed card never drops below the minimum review interval`() {
        val card = Scheduling(state = CardState.REVIEW, intervalDays = 1, easeFactor = 1.3)

        val result = Scheduler.next(card, Grade.AGAIN, now)

        assertTrue(result.intervalDays >= Scheduler.MINIMUM_REVIEW_INTERVAL_DAYS)
    }
}

package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler decides when a user sees a card again, and a mistake here is close to invisible:
 * nobody notices an interval that is 30% too long until months of studying have been wasted on
 * it. So the arithmetic is pinned down here rather than trusted.
 *
 * Expected values come from the published FSRS-5 formulas and default weights, not from running
 * this implementation and writing down what it said - a test built that way would have passed
 * just as happily on the SM-2 version it replaced.
 */
class SchedulerTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 86_400_000L

    private fun newCard() = Scheduling()

    /** A card with established memory state, last answered [daysAgo] days before [now]. */
    private fun mature(stability: Double, difficulty: Double, daysAgo: Int) = Scheduling(
        state = CardState.REVIEW,
        stability = stability,
        difficulty = difficulty,
        intervalDays = stability.toInt(),
        lastReviewedAt = now - daysAgo * day,
        repetitions = 5
    )

    // --------------------------------------------------------------- the model's own arithmetic

    @Test
    fun `retrievability is exactly 90 percent when elapsed time equals stability`() {
        // The definition of stability. If this drifts, every interval in the app is wrong.
        assertEquals(0.90, Fsrs.retrievability(10.0, 10.0), 0.0001)
        assertEquals(0.90, Fsrs.retrievability(200.0, 200.0), 0.0001)
    }

    @Test
    fun `the interval for 90 percent retention is the stability itself`() {
        assertEquals(10.0, Fsrs.intervalFor(10.0, 0.90), 0.0001)
        assertEquals(0.2345679, Fsrs.FACTOR, 0.0000001)
    }

    @Test
    fun `retention is a dial, and asking for more shortens the schedule`() {
        // What SM-2 could not do at all: there, retention was whatever fell out.
        val relaxed = Fsrs.intervalFor(30.0, 0.80)
        val standard = Fsrs.intervalFor(30.0, 0.90)
        val strict = Fsrs.intervalFor(30.0, 0.95)

        assertEquals(72.0, relaxed, 0.5)
        assertEquals(30.0, standard, 0.5)
        assertEquals(14.0, strict, 0.5)
        assertTrue(relaxed > standard && standard > strict)
    }

    // --------------------------------------------------------------------------------- learning

    @Test
    fun `a new card answered Good moves to the second learning step, not to days`() {
        val result = Scheduler.next(newCard(), Grade.GOOD, now)

        assertEquals(CardState.LEARNING, result.state)
        assertEquals(1, result.learningStep)
        assertEquals(now + 10 * minute, result.dueAt)
        assertEquals("memory starts tracking on the first answer", 3.173, result.stability, 0.001)
        assertEquals(5.282, result.difficulty, 0.001)
    }

    @Test
    fun `Good twice graduates the card onto the day scale`() {
        val first = Scheduler.next(newCard(), Grade.GOOD, now)
        val second = Scheduler.next(first, Grade.GOOD, now)

        assertEquals(CardState.REVIEW, second.state)
        assertEquals(4, second.intervalDays)
        assertEquals(now + 4 * day, second.dueAt)
    }

    @Test
    fun `a second answer on the same day barely moves stability`() {
        // Seeing a card twice in ten minutes proves nothing about next week. Running the full
        // recall formula on a one-minute gap would schedule it months out.
        val first = Scheduler.next(newCard(), Grade.GOOD, now)
        val second = Scheduler.next(first, Grade.GOOD, now + 10 * minute)

        assertEquals(4.467, second.stability, 0.001)
    }

    @Test
    fun `Easy on a new card skips the steps and schedules a fortnight out`() {
        val result = Scheduler.next(newCard(), Grade.EASY, now)

        assertEquals(CardState.REVIEW, result.state)
        assertEquals(16, result.intervalDays)
    }

    @Test
    fun `Again during learning returns to the first step`() {
        val advanced = Scheduler.next(newCard(), Grade.GOOD, now)
        val lapsed = Scheduler.next(advanced, Grade.AGAIN, now)

        assertEquals(CardState.LEARNING, lapsed.state)
        assertEquals(0, lapsed.learningStep)
        assertEquals(now + minute, lapsed.dueAt)
    }

    // ----------------------------------------------------------------------------------- review

    @Test
    fun `a review card answered on time grows by the model`() {
        val result = Scheduler.next(mature(30.0, 5.0, daysAgo = 30), Grade.GOOD, now)

        assertEquals(CardState.REVIEW, result.state)
        assertEquals(90, result.intervalDays)
        assertEquals(90.41, result.stability, 0.05)
        assertEquals(6, result.repetitions)
    }

    @Test
    fun `answering late is rewarded, not punished`() {
        // The headline difference from SM-2, which gave the identical answer either way.
        // Recalling a card 120 days after it was due proves far more than recalling it on time,
        // and anyone who studies in bursts around exams lives in this case.
        val onTime = Scheduler.next(mature(30.0, 5.0, daysAgo = 30), Grade.GOOD, now)
        val late = Scheduler.next(mature(30.0, 5.0, daysAgo = 120), Grade.GOOD, now)

        assertEquals(90, onTime.intervalDays)
        assertEquals(217, late.intervalDays)
        assertTrue(
            "a late success must schedule further out",
            late.intervalDays > onTime.intervalDays
        )
    }

    @Test
    fun `Hard grows slowly, Good normally, Easy fastest`() {
        val card = mature(30.0, 5.0, daysAgo = 30)

        val hard = Scheduler.next(card, Grade.HARD, now).intervalDays
        val good = Scheduler.next(card, Grade.GOOD, now).intervalDays
        val easy = Scheduler.next(card, Grade.EASY, now).intervalDays

        assertTrue("Hard must be shortest of the three successes", hard < good)
        assertTrue("Easy must be longest", easy > good)
    }

    @Test
    fun `an answer moves difficulty in the direction you would expect`() {
        val card = mature(30.0, 5.0, daysAgo = 30)

        assertTrue(
            "forgetting makes a card harder",
            Scheduler.next(card, Grade.AGAIN, now).difficulty > 5.0
        )
        assertTrue(
            "Easy makes it easier",
            Scheduler.next(card, Grade.EASY, now).difficulty < 5.0
        )
    }

    // ----------------------------------------------------------------------------------- lapses

    @Test
    fun `Again on a review card collapses stability and starts relearning`() {
        val result = Scheduler.next(mature(30.0, 5.0, daysAgo = 30), Grade.AGAIN, now)

        assertEquals(CardState.RELEARNING, result.state)
        assertEquals(3.60, result.stability, 0.05)
        assertEquals(1, result.lapses)
        assertEquals(0, result.repetitions)
        // Comes back in minutes, in this same session - not tomorrow.
        assertEquals(now + 10 * minute, result.dueAt)
    }

    @Test
    fun `a lapse can never raise stability`() {
        // The formula alone can do this for a card with very low stability, and "I forgot it, so
        // show it to me less often" is wrong however the arithmetic arrives there.
        for (stability in listOf(0.5, 1.0, 2.0, 5.0, 30.0, 400.0)) {
            val card = mature(stability, 5.0, daysAgo = stability.toInt().coerceAtLeast(1))
            val lapsed = Scheduler.next(card, Grade.AGAIN, now)

            assertTrue(
                "stability rose from $stability to ${lapsed.stability} on a lapse",
                lapsed.stability <= stability
            )
        }
    }

    @Test
    fun `relearning returns to review without resetting the card to new`() {
        val lapsed = Scheduler.next(mature(30.0, 5.0, daysAgo = 30), Grade.AGAIN, now)
        val recovered = Scheduler.next(lapsed, Grade.GOOD, now)

        assertEquals(CardState.REVIEW, recovered.state)
        assertEquals("a lapse must not throw away the lapse count", 1, recovered.lapses)
        assertTrue(recovered.stability > 0.0)
    }

    @Test
    fun `a card forgotten enough times is flagged as a leech`() {
        assertTrue(Scheduler.isLeech(Scheduler.LEECH_THRESHOLD))
        assertTrue(Scheduler.isLeech(Scheduler.LEECH_THRESHOLD + 5))
        assertFalse(Scheduler.isLeech(Scheduler.LEECH_THRESHOLD - 1))
    }

    // ----------------------------------------------------------------------------------- bounds

    @Test
    fun `difficulty stays inside one to ten however it is driven`() {
        var punished = mature(30.0, 5.0, daysAgo = 30)
        repeat(20) {
            punished = Scheduler.next(punished.copy(state = CardState.REVIEW), Grade.AGAIN, now)
        }
        assertTrue(punished.difficulty in Fsrs.MIN_DIFFICULTY..Fsrs.MAX_DIFFICULTY)

        var rewarded = mature(30.0, 5.0, daysAgo = 30)
        repeat(20) { rewarded = Scheduler.next(rewarded, Grade.EASY, now + 400 * day) }
        assertTrue(rewarded.difficulty in Fsrs.MIN_DIFFICULTY..Fsrs.MAX_DIFFICULTY)
    }

    @Test
    fun `intervals are capped so a card is never scheduled out of existence`() {
        var card = mature(2000.0, 1.0, daysAgo = 2000)
        repeat(10) { card = Scheduler.next(card, Grade.EASY, now) }

        assertEquals(Scheduler.MAXIMUM_INTERVAL_DAYS, card.intervalDays)
    }

    @Test
    fun `no interval is ever shorter than a day once on the day scale`() {
        val barely = Scheduler.next(Scheduling(), Grade.AGAIN, now)
        val graduated = Scheduler.next(barely.copy(state = CardState.REVIEW), Grade.HARD, now + day)

        assertTrue(graduated.intervalDays >= Scheduler.MINIMUM_REVIEW_INTERVAL_DAYS)
    }

    // -------------------------------------------------------------------------------- migration

    @Test
    fun `an SM-2 card converts to something sensible rather than resetting`() {
        // Collections created before FSRS must keep their history. The mapping is approximate and
        // allowed to be; what it must never do is throw the card back to new.
        val (stability, difficulty) = Fsrs.fromSuperMemo(intervalDays = 45, easeFactor = 2.5)

        assertEquals("the old interval is the best estimate of stability", 45.0, stability, 0.001)
        assertTrue(difficulty in Fsrs.MIN_DIFFICULTY..Fsrs.MAX_DIFFICULTY)
    }

    @Test
    fun `a low SM-2 ease converts to a harder card than a high one`() {
        val (_, hard) = Fsrs.fromSuperMemo(intervalDays = 10, easeFactor = 1.3)
        val (_, easy) = Fsrs.fromSuperMemo(intervalDays = 10, easeFactor = 2.8)

        assertTrue("low ease meant the user kept struggling with it", hard > easy)
        assertTrue(hard in Fsrs.MIN_DIFFICULTY..Fsrs.MAX_DIFFICULTY)
        assertTrue(easy in Fsrs.MIN_DIFFICULTY..Fsrs.MAX_DIFFICULTY)
    }

    @Test
    fun `a converted card schedules from its old interval without a timestamp`() {
        // Migrated cards have no lastReviewedAt - SM-2 never stored one - so the scheduler falls
        // back to the interval. Without that fallback every migrated card would look overdue by
        // its entire lifetime and be thrown years into the future on its next answer.
        val migrated = Scheduling(
            state = CardState.REVIEW,
            stability = 45.0,
            difficulty = 5.0,
            intervalDays = 45,
            lastReviewedAt = 0L
        )

        val result = Scheduler.next(migrated, Grade.GOOD, now)

        assertTrue("a converted card must not explode to years", result.intervalDays in 46..250)
    }
}

package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The optimiser is judged on synthetic learners whose memory is known exactly.
 *
 * Each simulated learner forgets according to a chosen weight vector: reviews are scheduled at
 * that model's own interval with jitter, and every answer is drawn from the model's true recall
 * probability at that moment. A fit on such a history has a right answer, so the tests can ask
 * the questions that matter - does fitting help when the learner really differs from the
 * defaults, and does it stay honest when they do not - rather than whether some number came out
 * as it did last time.
 */
class FsrsOptimizerTest {

    private val day = 86_400_000L
    private val minute = 60_000L
    private val epoch = 1_700_000_000_000L

    /**
     * A learner clearly unlike the average one: shorter first intervals, slower stability growth,
     * harsher lapses, a bigger Easy bonus. Every value inside the bounds.
     */
    private val unusual = doubleArrayOf(
        0.2, 0.7, 1.4, 6.0, 6.0, 0.8, 1.0, 0.02, 0.9,
        0.25, 0.7, 1.2, 0.15, 0.4, 1.4, 0.5, 2.2, 0.4, 0.5
    )

    /**
     * Simulate [cards] cards on [truth], with reviews until the history holds about [reviews]
     * events. Same-day learning and relearning repeats are included, as the real log has them.
     */
    private fun simulate(
        truth: DoubleArray,
        cards: Int,
        reviews: Int,
        seed: Int
    ): List<ReviewEvent> {
        require(Fsrs.isValidWeights(truth))
        val random = Random(seed)
        val events = ArrayList<ReviewEvent>(reviews + cards)
        val perCard = reviews / cards

        for (id in 1L..cards.toLong()) {
            var t = epoch + random.nextLong(30) * day + random.nextLong(day)
            val firstGrade = pick(random, 0.2, 0.1, 0.55, 0.15)
            events += ReviewEvent(id, t, firstGrade)
            var s = Fsrs.initialStability(firstGrade, truth)
            var d = Fsrs.initialDifficulty(firstGrade, truth)
            var count = 1

            fun sameDay(grade: Grade) {
                t += 10 * minute
                events += ReviewEvent(id, t, grade)
                d = Fsrs.nextDifficulty(d, grade, truth)
                s = Fsrs.stabilityAfterSameDay(s, grade, truth)
                count++
            }

            if (firstGrade != Grade.EASY) sameDay(Grade.GOOD)

            while (count < perCard) {
                val scheduled = Fsrs.intervalFor(s, 0.9).coerceIn(1.0, 1825.0)
                val elapsed = (scheduled * (0.6 + 1.2 * random.nextDouble())).coerceAtLeast(1.0)
                t += (elapsed * day).toLong()

                val r = Fsrs.retrievability(elapsed, s)
                val grade = if (random.nextDouble() < r) pick(random, 0.0, 0.15, 0.7, 0.15) else Grade.AGAIN
                events += ReviewEvent(id, t, grade)
                count++

                val nextD = Fsrs.nextDifficulty(d, grade, truth)
                s = if (grade == Grade.AGAIN) {
                    Fsrs.stabilityAfterLapse(d, s, r, truth)
                } else {
                    Fsrs.stabilityAfterRecall(d, s, r, grade, truth)
                }
                d = nextD
                if (grade == Grade.AGAIN) sameDay(Grade.GOOD)
            }
        }
        // The real log is not grouped by card; neither should the input be.
        events.shuffle(Random(seed + 1))
        return events
    }

    private fun pick(random: Random, again: Double, hard: Double, good: Double, easy: Double): Grade {
        val x = random.nextDouble() * (again + hard + good + easy)
        return when {
            x < again -> Grade.AGAIN
            x < again + hard -> Grade.HARD
            x < again + hard + good -> Grade.GOOD
            else -> Grade.EASY
        }
    }

    @Test
    fun `a learner unlike the defaults gets a fit that beats them on cards it never saw`() {
        val history = simulate(unusual, cards = 1000, reviews = 10_000, seed = 7)

        val result = FsrsOptimizer.fit(history)

        assertNotNull(result)
        result!!
        println("unusual learner: $result, improvement ${"%.2f".format(result.improvementPercent)}%")
        assertTrue(
            "held-out loss must fall: default ${result.defaultLoss}, fitted ${result.fittedLoss}",
            result.fittedLoss < result.defaultLoss
        )
        assertTrue("the fit must claim the improvement it found", result.improved)
        assertTrue(Fsrs.isValidWeights(result.weights))
    }

    @Test
    fun `a learner who matches the defaults is not told the fit helps much`() {
        val history = simulate(Fsrs.WEIGHTS, cards = 1000, reviews = 10_000, seed = 11)

        val result = FsrsOptimizer.fit(history)

        assertNotNull(result)
        result!!
        println("default learner: $result, improvement ${"%.2f".format(result.improvementPercent)}%")
        assertTrue(
            "defaults are the truth here, so a large gain would be overfitting: " +
                "${result.improvementPercent}%",
            result.improvementPercent < 2.0
        )
        assertTrue(Fsrs.isValidWeights(result.weights))
    }

    @Test
    fun `too little history returns null rather than a guess`() {
        val history = simulate(unusual, cards = 30, reviews = 300, seed = 3)

        assertNull(FsrsOptimizer.fit(history))
        assertNull(FsrsOptimizer.fit(emptyList()))
    }

    @Test
    fun `same-day repeats do not count towards the minimum`() {
        // 500 cards, each answered three times in one sitting: plenty of rows, nothing to learn
        // from, since none of those answers tests memory across a day.
        val history = (1L..500L).flatMap { id ->
            (0 until 3).map { ReviewEvent(id, epoch + it * 10 * minute, Grade.GOOD) }
        }

        assertNull(FsrsOptimizer.fit(history))
    }

    @Test
    fun `a cancelled fit gives up promptly`() {
        val history = simulate(unusual, cards = 2000, reviews = 20_000, seed = 5)

        val started = System.nanoTime()
        var calls = 0
        val result = FsrsOptimizer.fit(history) { ++calls > 3 }
        val millis = (System.nanoTime() - started) / 1_000_000

        assertNull(result)
        assertTrue("cancellation took ${millis}ms", millis < 2_000)
    }

    @Test
    fun `twenty thousand reviews fit in well under ten seconds`() {
        val history = simulate(unusual, cards = 2000, reviews = 20_000, seed = 13)

        val started = System.nanoTime()
        val result = FsrsOptimizer.fit(history)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertNotNull(result)
        println("20k reviews: fit in ${millis}ms, $result, improvement ${"%.2f".format(result!!.improvementPercent)}%")
        assertTrue("fit took ${millis}ms", millis < 10_000)
        assertTrue(result.improved)
    }

    @Test
    fun `the same history always fits to the same weights`() {
        // Split and shuffle are seeded from the data, not the clock: re-running the fit must not
        // move a user's intervals.
        val history = simulate(unusual, cards = 300, reviews = 3_000, seed = 17)

        assertEquals(FsrsOptimizer.fit(history), FsrsOptimizer.fit(history.reversed()))
    }

    // ------------------------------------------------------------------------------ the bounds

    @Test
    fun `the defaults are valid weights`() {
        assertTrue(Fsrs.isValidWeights(Fsrs.WEIGHTS))
    }

    @Test
    fun `corrupt weight vectors are rejected`() {
        assertFalse("wrong length", Fsrs.isValidWeights(DoubleArray(17) { 1.0 }))
        assertFalse("NaN", Fsrs.isValidWeights(Fsrs.WEIGHTS.copyOf().also { it[8] = Double.NaN }))
        assertFalse(
            "infinite",
            Fsrs.isValidWeights(Fsrs.WEIGHTS.copyOf().also { it[3] = Double.POSITIVE_INFINITY })
        )
        assertFalse("out of range", Fsrs.isValidWeights(Fsrs.WEIGHTS.copyOf().also { it[16] = 0.5 }))
        assertFalse(
            "Again starting further out than Easy",
            Fsrs.isValidWeights(Fsrs.WEIGHTS.copyOf().also { it[0] = 20.0 })
        )
    }

    @Test
    fun `clamping repairs any vector into a valid one`() {
        val wild = DoubleArray(Fsrs.WEIGHT_COUNT) { if (it % 2 == 0) 1e9 else -1e9 }
        wild[5] = Double.NaN
        FsrsOptimizer.clamp(wild)
        assertTrue(wild.contentToString(), Fsrs.isValidWeights(wild))
    }

    @Test
    fun `the scheduler ignores a corrupt vector instead of scheduling on it`() {
        val card = Scheduling(
            state = com.layerbit.abhyas.data.model.CardState.REVIEW,
            stability = 30.0,
            difficulty = 5.0,
            intervalDays = 30,
            lastReviewedAt = epoch - 30 * day
        )
        val corrupt = Fsrs.WEIGHTS.copyOf().also { it[8] = Double.NaN }

        assertEquals(
            Scheduler.next(card, Grade.GOOD, epoch),
            Scheduler.next(card, Grade.GOOD, epoch, weights = corrupt)
        )
    }

    @Test
    fun `fitted weights change what the scheduler does`() {
        val card = Scheduling(
            state = com.layerbit.abhyas.data.model.CardState.REVIEW,
            stability = 30.0,
            difficulty = 5.0,
            intervalDays = 30,
            lastReviewedAt = epoch - 30 * day
        )
        val default = Scheduler.next(card, Grade.GOOD, epoch)
        val custom = Scheduler.next(card, Grade.GOOD, epoch, weights = unusual)

        assertTrue(
            "slower growth must give a shorter interval: ${custom.intervalDays} vs ${default.intervalDays}",
            custom.intervalDays < default.intervalDays
        )
    }
}

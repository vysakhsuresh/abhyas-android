package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.Grade
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-5: the Free Spaced Repetition Scheduler.
 *
 * This replaces SM-2, which Abhyas shipped first and which is forty years old. The difference is
 * not cosmetic. SM-2 tracks one number per card - an "ease factor" - and multiplies the interval
 * by it, which means it cannot tell the difference between a card you answered on the day it was
 * due and the same card answered three months late. FSRS models memory with two numbers instead:
 *
 *  - **Stability (S)** - how many days until your chance of recalling the card falls to 90%.
 *  - **Difficulty (D)** - how hard this particular card is for you, 1 to 10.
 *
 * and a third derived at review time:
 *
 *  - **Retrievability (R)** - the probability you still know it, right now, given how long it has
 *    been. This is what SM-2 has no concept of.
 *
 * The practical consequences, in order of how much they matter to a student:
 *
 *  1. **Answering late is rewarded, not punished.** Recalling a card after 120 days when it was
 *     due at 30 proves far more about your memory than recalling it on time, and the next
 *     interval reflects that. SM-2 gives the identical answer either way. For anyone who studies
 *     in bursts around exams - which is most people - this alone is the difference between a
 *     schedule that adapts and one that nags.
 *  2. **Fewer reviews for the same retention.** Because the model is fitted to real review data
 *     rather than assumed, it stops showing you cards you demonstrably still know.
 *  3. **Retention is a dial, not a consequence.** [intervalFor] takes the retention you want and
 *     returns the interval that achieves it. Under SM-2 retention is whatever falls out.
 *
 * The weights below are the published FSRS-5 defaults, fitted over millions of real reviews. They
 * are deliberately not tuned here: per-user optimisation needs a review history to fit against,
 * and the defaults are already better than anything hand-chosen.
 *
 * Pure, with no clock and no database, so every one of these claims is testable.
 */
object Fsrs {

    /**
     * The 19 FSRS-5 parameters, in the order the algorithm indexes them:
     *
     * ```
     * 0..3    initial stability after Again / Hard / Good / Easy
     * 4,5     initial difficulty
     * 6,7     difficulty change and its mean reversion
     * 8..10   stability growth on a successful recall
     * 11..14  stability after a lapse
     * 15,16   the Hard penalty and the Easy bonus
     * 17,18   same-day (short-term) review handling
     * ```
     */
    val WEIGHTS = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575,
        0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621
    )

    /**
     * The forgetting curve's exponent. Memory decays as a power function rather than the
     * exponential SuperMemo assumed - which is why SM-2 is too aggressive at long intervals.
     */
    const val DECAY = -0.5

    /** Chosen so that retrievability is exactly 0.9 when elapsed time equals stability. */
    val FACTOR = 0.9.pow(1.0 / DECAY) - 1.0

    /** Difficulty is defined on 1..10 and every path has to stay inside it. */
    const val MIN_DIFFICULTY = 1.0
    const val MAX_DIFFICULTY = 10.0

    /** A card can never be scheduled closer than this, whatever the model says. */
    const val MIN_STABILITY = 0.01

    /**
     * The probability of recalling a card [elapsedDays] after its last review, given [stability].
     *
     * R(t) = (1 + FACTOR * t/S) ^ DECAY
     */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    /**
     * How long until retrievability falls to [desiredRetention].
     *
     * This is the inverse of [retrievability], and it is what makes retention a setting rather
     * than an outcome: ask for 0.9 and you get the interval that leaves you a 90% chance of
     * recall; ask for 0.95 and the intervals shorten accordingly.
     */
    fun intervalFor(stability: Double, desiredRetention: Double): Double {
        val retention = desiredRetention.coerceIn(0.70, 0.99)
        return (stability / FACTOR) * (retention.pow(1.0 / DECAY) - 1.0)
    }

    /** Stability of a card being answered for the very first time. */
    fun initialStability(grade: Grade): Double =
        max(MIN_STABILITY, WEIGHTS[grade.fsrsRating - 1])

    /** Difficulty of a card being answered for the very first time. */
    fun initialDifficulty(grade: Grade): Double =
        clampDifficulty(WEIGHTS[4] - exp(WEIGHTS[5] * (grade.fsrsRating - 1)) + 1.0)

    /**
     * Difficulty after an answer.
     *
     * Two steps. The change itself is damped by how much room is left towards 10, so a card that
     * is already hard cannot run away; then it is pulled back towards the difficulty of an
     * easily-learned card, which stops a single bad day permanently marking a card as impossible.
     */
    fun nextDifficulty(difficulty: Double, grade: Grade): Double {
        val delta = -WEIGHTS[6] * (grade.fsrsRating - 3)
        val damped = difficulty + delta * (10.0 - difficulty) / 9.0
        val reverted = WEIGHTS[7] * initialDifficulty(Grade.EASY) + (1.0 - WEIGHTS[7]) * damped
        return clampDifficulty(reverted)
    }

    /**
     * Stability after a successful recall.
     *
     * The `exp(w10 * (1 - R))` term is the one that matters most: the lower your retrievability
     * was when you got it right, the more that success proves, and the further the card is
     * pushed out. Recalling something you were about to forget is worth far more than recalling
     * something you saw yesterday, and this is where that shows up.
     */
    fun stabilityAfterRecall(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        grade: Grade
    ): Double {
        val hardPenalty = if (grade == Grade.HARD) WEIGHTS[15] else 1.0
        val easyBonus = if (grade == Grade.EASY) WEIGHTS[16] else 1.0

        val growth = exp(WEIGHTS[8]) *
            (11.0 - difficulty) *
            stability.pow(-WEIGHTS[9]) *
            (exp(WEIGHTS[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus

        return max(MIN_STABILITY, stability * (1.0 + growth))
    }

    /**
     * Stability after forgetting.
     *
     * Capped so that a lapse can never *raise* stability - the formula alone can do that for a
     * card with very low stability, and "I forgot it, so show it to me less often" is obviously
     * wrong however the arithmetic arrives there.
     */
    fun stabilityAfterLapse(
        difficulty: Double,
        stability: Double,
        retrievability: Double
    ): Double {
        val lapsed = WEIGHTS[11] *
            difficulty.pow(-WEIGHTS[12]) *
            ((stability + 1.0).pow(WEIGHTS[13]) - 1.0) *
            exp(WEIGHTS[14] * (1.0 - retrievability))

        val ceiling = stability / exp(WEIGHTS[17] * WEIGHTS[18])
        return max(MIN_STABILITY, min(lapsed, ceiling))
    }

    /**
     * Stability after a review on the same day as the last one.
     *
     * Seeing a card twice in ten minutes says almost nothing about long-term memory, so these
     * move stability only slightly rather than running the full recall formula - which would
     * treat a one-minute gap as a triumph of retention and schedule the card months out.
     */
    fun stabilityAfterSameDay(stability: Double, grade: Grade): Double =
        max(MIN_STABILITY, stability * exp(WEIGHTS[17] * (grade.fsrsRating - 3 + WEIGHTS[18])))

    /**
     * Convert an SM-2 card to FSRS state, for collections built before the change.
     *
     * The old interval is the best available estimate of stability: under SM-2 at its assumed
     * retention the two mean roughly the same thing. Difficulty is read off the ease factor,
     * mapping SM-2's 1.3..2.5+ range onto FSRS's 10..1 - low ease meant a hard card.
     *
     * It is an approximation and it is allowed to be. The alternative was resetting every card in
     * every collection to new, which would throw away exactly the history this is reconstructing.
     */
    fun fromSuperMemo(intervalDays: Int, easeFactor: Double): Pair<Double, Double> {
        val stability = max(MIN_STABILITY, intervalDays.toDouble())
        // 2.5 ease (the SM-2 default) lands near the middle of the difficulty range; 1.3, its
        // floor, lands near the hard end.
        val normalised = ((2.6 - easeFactor) / 1.3).coerceIn(0.0, 1.0)
        val difficulty = clampDifficulty(1.0 + normalised * 8.0)
        return stability to difficulty
    }

    /** Natural log of stability, used by the statistics screen to bucket card maturity. */
    fun logStability(stability: Double): Double = ln(max(MIN_STABILITY, stability))

    private fun clampDifficulty(value: Double): Double =
        value.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
}

/**
 * FSRS rates answers 1..4. Mapping it here rather than in the algorithm keeps [Grade] the single
 * place the four buttons are defined, however the scheduler underneath changes.
 */
val Grade.fsrsRating: Int
    get() = when (this) {
        Grade.AGAIN -> 1
        Grade.HARD -> 2
        Grade.GOOD -> 3
        Grade.EASY -> 4
    }

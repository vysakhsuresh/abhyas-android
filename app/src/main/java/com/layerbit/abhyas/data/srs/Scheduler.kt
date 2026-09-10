package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Everything about a card that scheduling reads or writes, lifted out of the Room entity.
 *
 * Keeping it separate is what makes [Scheduler] a pure function of its inputs: the whole
 * algorithm can be unit-tested by calling it with a [Scheduling] and a [Grade], with no database,
 * no clock and no Android. Given how easy an off-by-one in interval growth is to introduce and
 * how long it would take a user to notice, that testability is the point.
 */
data class Scheduling(
    val state: CardState = CardState.NEW,
    /** Epoch millis at which the card should next be shown. */
    val dueAt: Long = 0L,
    /** Current spacing in days. Meaningless while in LEARNING/RELEARNING, which work in minutes. */
    val intervalDays: Int = 0,
    /** SM-2's E-Factor: how fast this particular card's interval grows. */
    val easeFactor: Double = Scheduler.STARTING_EASE,
    /** Successful reviews in a row at day scale. Reset by a lapse. */
    val repetitions: Int = 0,
    /** Lifetime count of times this card was forgotten after having been learned. */
    val lapses: Int = 0,
    /** Index into [Scheduler.LEARNING_STEPS_MINUTES] while learning or relearning. */
    val learningStep: Int = 0
)

/**
 * SM-2, with the short in-session learning steps that make it usable.
 *
 * The ease-factor arithmetic is SuperMemo 2 exactly as published; the learning/relearning phases
 * around it follow the approach Anki popularised. Both are decades old and well understood, which
 * is the reason to use them rather than invent a curve.
 */
object Scheduler {

    /** SM-2's default E-Factor for an unseen card. */
    const val STARTING_EASE = 2.5

    /**
     * SM-2 lets the E-Factor fall without bound, which drives a hard card's interval towards zero
     * and buries the queue under it forever. 1.3 is SuperMemo's own floor.
     */
    const val MINIMUM_EASE = 1.3

    /**
     * How long a new card waits between its first showings, in minutes. Answer Good twice and it
     * graduates; answer Again at any point and it drops back to the first step.
     */
    val LEARNING_STEPS_MINUTES = intArrayOf(1, 10)

    /** A card that lapsed out of REVIEW gets one short step before it is trusted again. */
    val RELEARNING_STEPS_MINUTES = intArrayOf(10)

    /** Interval given to a card the first time it leaves the learning steps. */
    const val GRADUATING_INTERVAL_DAYS = 1

    /** Interval for a card the user marks Easy while still learning - straight past the steps. */
    const val EASY_INTERVAL_DAYS = 4

    /** A lapse multiplies the old interval by this rather than throwing the progress away. */
    const val LAPSE_INTERVAL_MULTIPLIER = 0.5

    /** Even a badly lapsed card should not come back sooner than this once it re-graduates. */
    const val MINIMUM_REVIEW_INTERVAL_DAYS = 1

    /**
     * Runaway intervals are worse than useless - a card scheduled 40 years out is a card you have
     * silently deleted. Cap it at roughly five years.
     */
    const val MAXIMUM_INTERVAL_DAYS = 1825

    private const val MINUTE_MILLIS = 60_000L
    private const val DAY_MILLIS = 86_400_000L

    /**
     * Apply [grade] to [current] and return what the card's scheduling becomes.
     *
     * [now] is passed in rather than read from the clock so tests can drive it, and so every card
     * answered in one batch shares a single consistent "now".
     */
    fun next(current: Scheduling, grade: Grade, now: Long): Scheduling = when (current.state) {
        CardState.NEW, CardState.LEARNING -> learningStep(current, grade, now)
        CardState.RELEARNING -> relearningStep(current, grade, now)
        CardState.REVIEW -> reviewStep(current, grade, now)
    }

    /** A card being seen for the first time, working through [LEARNING_STEPS_MINUTES]. */
    private fun learningStep(current: Scheduling, grade: Grade, now: Long): Scheduling = when (grade) {
        // Easy skips the remaining steps entirely - the user is telling us they already know it.
        Grade.EASY -> graduate(current, EASY_INTERVAL_DAYS, now)

        Grade.GOOD -> {
            val nextStep = current.learningStep + 1
            if (nextStep >= LEARNING_STEPS_MINUTES.size) {
                graduate(current, GRADUATING_INTERVAL_DAYS, now)
            } else {
                current.copy(
                    state = CardState.LEARNING,
                    learningStep = nextStep,
                    dueAt = now + LEARNING_STEPS_MINUTES[nextStep] * MINUTE_MILLIS
                )
            }
        }

        // Hard repeats the current step rather than advancing, so a shaky card gets another look
        // without being sent back to the very beginning.
        Grade.HARD -> current.copy(
            state = CardState.LEARNING,
            dueAt = now + LEARNING_STEPS_MINUTES[current.learningStep] * MINUTE_MILLIS
        )

        Grade.AGAIN -> current.copy(
            state = CardState.LEARNING,
            learningStep = 0,
            dueAt = now + LEARNING_STEPS_MINUTES[0] * MINUTE_MILLIS
        )
    }

    /** A card that lapsed out of REVIEW and is earning its place back. */
    private fun relearningStep(current: Scheduling, grade: Grade, now: Long): Scheduling = when (grade) {
        Grade.EASY, Grade.GOOD -> {
            val nextStep = current.learningStep + 1
            if (grade == Grade.EASY || nextStep >= RELEARNING_STEPS_MINUTES.size) {
                // Re-enter REVIEW at the reduced interval the lapse already computed, not at the
                // graduating interval - the card is not new, it just slipped.
                current.copy(
                    state = CardState.REVIEW,
                    learningStep = 0,
                    intervalDays = current.intervalDays,
                    dueAt = now + current.intervalDays * DAY_MILLIS
                )
            } else {
                current.copy(
                    learningStep = nextStep,
                    dueAt = now + RELEARNING_STEPS_MINUTES[nextStep] * MINUTE_MILLIS
                )
            }
        }

        Grade.HARD, Grade.AGAIN -> current.copy(
            learningStep = 0,
            dueAt = now + RELEARNING_STEPS_MINUTES[0] * MINUTE_MILLIS
        )
    }

    /** A graduated card. This is where SM-2's interval growth actually applies. */
    private fun reviewStep(current: Scheduling, grade: Grade, now: Long): Scheduling {
        val ease = adjustEase(current.easeFactor, grade)

        if (grade == Grade.AGAIN) {
            // A lapse. Halve the interval, count it, and put the card on the relearning steps.
            val reduced = max(
                MINIMUM_REVIEW_INTERVAL_DAYS,
                (current.intervalDays * LAPSE_INTERVAL_MULTIPLIER).roundToLong().toInt()
            )
            return current.copy(
                state = CardState.RELEARNING,
                easeFactor = ease,
                intervalDays = reduced,
                repetitions = 0,
                lapses = current.lapses + 1,
                learningStep = 0,
                dueAt = now + RELEARNING_STEPS_MINUTES[0] * MINUTE_MILLIS
            )
        }

        val previous = max(1, current.intervalDays)
        val grown = when (grade) {
            // Hard grows slowly and independently of ease, which is what stops a card the user
            // keeps finding hard from drifting out to the same spacing as one they find easy.
            Grade.HARD -> previous * 1.2
            Grade.GOOD -> previous * ease
            // Easy earns a bonus on top of the normal growth.
            Grade.EASY -> previous * ease * 1.3
            Grade.AGAIN -> previous.toDouble() // unreachable, handled above
        }

        val capped = grown.roundToLong().toInt().coerceIn(
            MINIMUM_REVIEW_INTERVAL_DAYS,
            MAXIMUM_INTERVAL_DAYS
        )

        return current.copy(
            state = CardState.REVIEW,
            easeFactor = ease,
            intervalDays = capped,
            repetitions = current.repetitions + 1,
            learningStep = 0,
            dueAt = now + capped * DAY_MILLIS
        )
    }

    /** Leave the learning steps for day-scale scheduling. */
    private fun graduate(current: Scheduling, intervalDays: Int, now: Long): Scheduling =
        current.copy(
            state = CardState.REVIEW,
            intervalDays = intervalDays,
            repetitions = current.repetitions + 1,
            learningStep = 0,
            dueAt = now + intervalDays * DAY_MILLIS
        )

    /**
     * SuperMemo 2's E-Factor update, verbatim:
     *
     *     EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
     *
     * The deltas this works out to are worth knowing: Easy (q=5) is +0.1, Good (q=4) is exactly
     * 0.0, Hard (q=3) is -0.14 and Again (q=0) is -0.8. So Good is the neutral answer - a card
     * answered correctly with normal effort keeps the ease it had - and only Hard and Easy move
     * it. Clamped at [MINIMUM_EASE].
     */
    private fun adjustEase(ease: Double, grade: Grade): Double {
        val q = grade.sm2Quality
        val delta = 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)
        return max(MINIMUM_EASE, ease + delta)
    }
}

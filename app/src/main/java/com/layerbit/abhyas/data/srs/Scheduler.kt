package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import kotlin.math.max
import kotlin.math.roundToInt

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
    /** Current spacing in days. Derived from stability; kept for display and for the queue. */
    val intervalDays: Int = 0,

    // --- FSRS memory model. See [Fsrs]. ---
    /** Days until recall probability falls to 90%. Zero for a card never answered. */
    val stability: Double = 0.0,
    /** How hard this card is for this user, 1..10. Zero for a card never answered. */
    val difficulty: Double = 0.0,
    /** When this card was last answered, epoch millis. Zero for a card never answered. */
    val lastReviewedAt: Long = 0L,

    /** Successful reviews in a row at day scale. Reset by a lapse. */
    val repetitions: Int = 0,
    /** Lifetime count of times this card was forgotten after having been learned. */
    val lapses: Int = 0,
    /** Index into [Scheduler.LEARNING_STEPS_MINUTES] while learning or relearning. */
    val learningStep: Int = 0,

    /**
     * SM-2's ease factor. Dead weight now, kept only so a collection created before FSRS can be
     * converted once, and so nothing is silently thrown away in the migration.
     */
    val easeFactor: Double = Scheduler.LEGACY_STARTING_EASE
) {
    /** A card whose memory state has been established, as opposed to one never answered. */
    val isTracked: Boolean get() = stability > 0.0 && difficulty > 0.0
}

/**
 * FSRS-5 scheduling, with the short in-session learning steps that make it usable.
 *
 * [Fsrs] is the memory model; this is the state machine around it. The split matters: the model
 * answers "when should this card next be seen", and the machine answers "what happens when
 * somebody taps Again at 11pm", which are different questions with different failure modes.
 *
 * The learning steps are kept deliberately. FSRS on its own would send a brand-new card answered
 * Good straight out to three days, which reads as broken to anyone learning something for the
 * first time - you see a card once, say Good, and it vanishes. Minutes first, days once it has
 * actually been recalled. Anki does the same thing for the same reason.
 */
object Scheduler {

    /**
     * The retention the schedule aims for.
     *
     * 0.9 is FSRS's own default and the right trade: higher means many more reviews for
     * diminishing returns, lower means forgetting things you meant to keep. A constant rather
     * than a buried literal, so it can become a setting without hunting for it.
     */
    const val DEFAULT_RETENTION = 0.90

    /**
     * How long a new card waits between its first showings, in minutes. Answer Good twice and it
     * graduates; answer Again at any point and it drops back to the first step.
     */
    val LEARNING_STEPS_MINUTES = intArrayOf(1, 10)

    /** A card that lapsed out of REVIEW gets one short step before it is trusted again. */
    val RELEARNING_STEPS_MINUTES = intArrayOf(10)

    /** Nothing is ever scheduled sooner than this once it is on day scale. */
    const val MINIMUM_REVIEW_INTERVAL_DAYS = 1

    /**
     * Runaway intervals are worse than useless - a card scheduled 40 years out is a card you have
     * silently deleted. Capped at roughly five years.
     */
    const val MAXIMUM_INTERVAL_DAYS = 1825

    /**
     * Forget a card this many times and it is not a memory problem any more - it is a card that
     * is written badly or is trying to hold too much at once.
     *
     * Anki calls these leeches. Left alone, one comes back every few days forever, soaking up
     * review time and teaching the user that the app wastes it. Reaching the threshold suspends
     * the card and surfaces it for rewriting rather than quietly continuing.
     */
    const val LEECH_THRESHOLD = 8

    /** The SM-2 default, retained only to convert old collections. */
    const val LEGACY_STARTING_EASE = 2.5

    private const val MINUTE_MILLIS = 60_000L
    private const val DAY_MILLIS = 86_400_000L

    /**
     * Apply [grade] to [current] and return what the card's scheduling becomes.
     *
     * [now] is passed in rather than read from the clock so tests can drive it, and so every card
     * answered in one batch shares a single consistent "now".
     */
    fun next(
        current: Scheduling,
        grade: Grade,
        now: Long,
        retention: Double = DEFAULT_RETENTION
    ): Scheduling {
        val memory = updateMemory(current, grade, now)

        return when (current.state) {
            CardState.NEW, CardState.LEARNING -> learningStep(current, memory, grade, now, retention)
            CardState.RELEARNING -> relearningStep(current, memory, grade, now, retention)
            CardState.REVIEW -> reviewStep(current, memory, grade, now, retention)
        }
    }

    /** Whether this card has lapsed often enough to be worth rewriting rather than repeating. */
    fun isLeech(lapses: Int): Boolean = lapses >= LEECH_THRESHOLD

    /** The card's memory state after an answer, before any state-machine decisions. */
    private data class Memory(val stability: Double, val difficulty: Double)

    /**
     * Advance the FSRS model.
     *
     * Three cases, and they are genuinely different. A card never answered gets its starting
     * values. A card answered again within the same day moves only slightly, because seeing
     * something twice in ten minutes proves nothing about next week. Otherwise the full model
     * runs, with retrievability computed from how long it has actually been - which is the whole
     * reason FSRS beats what came before.
     */
    private fun updateMemory(current: Scheduling, grade: Grade, now: Long): Memory {
        if (!current.isTracked) {
            return Memory(Fsrs.initialStability(grade), Fsrs.initialDifficulty(grade))
        }

        val difficulty = Fsrs.nextDifficulty(current.difficulty, grade)
        val elapsed = elapsedDays(current, now)

        if (elapsed < 1.0) {
            return Memory(Fsrs.stabilityAfterSameDay(current.stability, grade), difficulty)
        }

        val retrievability = Fsrs.retrievability(elapsed, current.stability)
        val stability = if (grade == Grade.AGAIN) {
            Fsrs.stabilityAfterLapse(current.difficulty, current.stability, retrievability)
        } else {
            Fsrs.stabilityAfterRecall(current.difficulty, current.stability, retrievability, grade)
        }
        return Memory(stability, difficulty)
    }

    /**
     * Days since the last answer.
     *
     * Falls back to the scheduled interval when a card has no recorded review - which is every
     * card in a collection migrated from SM-2, since that version never stored the timestamp.
     */
    private fun elapsedDays(current: Scheduling, now: Long): Double {
        if (current.lastReviewedAt <= 0L) return current.intervalDays.toDouble()
        return max(0.0, (now - current.lastReviewedAt).toDouble() / DAY_MILLIS)
    }

    /** A card being seen for the first time, working through [LEARNING_STEPS_MINUTES]. */
    private fun learningStep(
        current: Scheduling,
        memory: Memory,
        grade: Grade,
        now: Long,
        retention: Double
    ): Scheduling = when (grade) {
        // Easy skips the remaining steps entirely - the user is telling us they already know it.
        Grade.EASY -> current.inDays(memory, now, retention)

        Grade.GOOD -> {
            val nextStep = current.learningStep + 1
            if (nextStep >= LEARNING_STEPS_MINUTES.size) {
                current.inDays(memory, now, retention)
            } else {
                current.inMinutes(memory, CardState.LEARNING, nextStep, LEARNING_STEPS_MINUTES, now)
            }
        }

        // Hard repeats the current step rather than advancing, so a shaky card gets another look
        // without being sent back to the very beginning.
        Grade.HARD -> current.inMinutes(
            memory, CardState.LEARNING, current.learningStep, LEARNING_STEPS_MINUTES, now
        )

        Grade.AGAIN -> current.inMinutes(
            memory, CardState.LEARNING, 0, LEARNING_STEPS_MINUTES, now
        )
    }

    /** A card that lapsed out of REVIEW and is earning its place back. */
    private fun relearningStep(
        current: Scheduling,
        memory: Memory,
        grade: Grade,
        now: Long,
        retention: Double
    ): Scheduling = when (grade) {
        Grade.EASY, Grade.GOOD -> {
            val nextStep = current.learningStep + 1
            if (grade == Grade.EASY || nextStep >= RELEARNING_STEPS_MINUTES.size) {
                // Back to REVIEW on the stability the lapse left behind, not on a fresh
                // graduating interval - the card is not new, it slipped.
                current.inDays(memory, now, retention)
            } else {
                current.inMinutes(
                    memory, CardState.RELEARNING, nextStep, RELEARNING_STEPS_MINUTES, now
                )
            }
        }

        Grade.HARD, Grade.AGAIN -> current.inMinutes(
            memory, CardState.RELEARNING, 0, RELEARNING_STEPS_MINUTES, now
        )
    }

    /** A graduated card. This is where FSRS's interval growth actually applies. */
    private fun reviewStep(
        current: Scheduling,
        memory: Memory,
        grade: Grade,
        now: Long,
        retention: Double
    ): Scheduling {
        if (grade == Grade.AGAIN) {
            // A lapse. Count it and put the card on the relearning steps, keeping the reduced
            // stability the model just computed.
            return current.copy(
                state = CardState.RELEARNING,
                stability = memory.stability,
                difficulty = memory.difficulty,
                lastReviewedAt = now,
                intervalDays = intervalDays(memory.stability, retention),
                repetitions = 0,
                lapses = current.lapses + 1,
                learningStep = 0,
                dueAt = now + RELEARNING_STEPS_MINUTES[0] * MINUTE_MILLIS
            )
        }
        return current.inDays(memory, now, retention)
    }

    /** Schedule on the minute scale, during learning or relearning. */
    private fun Scheduling.inMinutes(
        memory: Memory,
        state: CardState,
        step: Int,
        steps: IntArray,
        now: Long
    ): Scheduling = copy(
        state = state,
        stability = memory.stability,
        difficulty = memory.difficulty,
        lastReviewedAt = now,
        learningStep = step,
        dueAt = now + steps[step] * MINUTE_MILLIS
    )

    /** Schedule on the day scale, as a reviewing card. */
    private fun Scheduling.inDays(
        memory: Memory,
        now: Long,
        retention: Double
    ): Scheduling {
        val days = intervalDays(memory.stability, retention)
        return copy(
            state = CardState.REVIEW,
            stability = memory.stability,
            difficulty = memory.difficulty,
            lastReviewedAt = now,
            intervalDays = days,
            repetitions = repetitions + 1,
            learningStep = 0,
            dueAt = now + days * DAY_MILLIS
        )
    }

    /** Whole days from a stability, clamped to something a human schedule can contain. */
    private fun intervalDays(stability: Double, retention: Double): Int =
        Fsrs.intervalFor(stability, retention)
            .roundToInt()
            .coerceIn(MINIMUM_REVIEW_INTERVAL_DAYS, MAXIMUM_INTERVAL_DAYS)
}

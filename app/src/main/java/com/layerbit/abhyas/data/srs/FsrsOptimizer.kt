package com.layerbit.abhyas.data.srs

import com.layerbit.abhyas.data.model.Grade
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One row of the review log, as the optimiser needs it: which card, when, and what was pressed.
 *
 * Deliberately no state column. The log does not record one, and the optimiser does not need it:
 * replaying a card's answers in time order through the model reconstructs its memory state at
 * every step, which is more trustworthy than a stored state would be anyway.
 */
data class ReviewEvent(val cardId: Long, val reviewedAt: Long, val grade: Grade)

/**
 * The outcome of fitting FSRS to one user's history.
 *
 * [defaultLoss] and [fittedLoss] are both measured on cards the fit never saw (see
 * [FsrsOptimizer.fit]), so the comparison between them is honest: a fit that merely memorised the
 * training cards scores no better here than the defaults do. [improved] is the only thing a caller
 * should act on - when it is false, keep the defaults, whatever [weights] says.
 */
class FitResult(
    /** The fitted vector. Always passes [Fsrs.isValidWeights]. */
    val weights: DoubleArray,
    /** Mean log loss of the published defaults on the held-out cards. Lower is better. */
    val defaultLoss: Double,
    /** Mean log loss of [weights] on the same held-out cards. */
    val fittedLoss: Double,
    /** Reviews that produced a prediction to score, across both splits. Same-day repeats excluded. */
    val reviewsUsed: Int,
    /** Whether [weights] beat the defaults on held-out cards by enough to be worth switching. */
    val improved: Boolean
) {
    /** How much lower the held-out loss is than the defaults', in percent. Negative if worse. */
    val improvementPercent: Double
        get() = if (defaultLoss > 0.0) (defaultLoss - fittedLoss) / defaultLoss * 100.0 else 0.0

    // Written out because a data class compares a DoubleArray by identity, which makes two equal
    // results unequal and is exactly the kind of thing a test or a StateFlow trips over.
    override fun equals(other: Any?): Boolean =
        other is FitResult &&
            weights.contentEquals(other.weights) &&
            defaultLoss == other.defaultLoss &&
            fittedLoss == other.fittedLoss &&
            reviewsUsed == other.reviewsUsed &&
            improved == other.improved

    override fun hashCode(): Int {
        var h = weights.contentHashCode()
        h = 31 * h + defaultLoss.hashCode()
        h = 31 * h + fittedLoss.hashCode()
        h = 31 * h + reviewsUsed
        return 31 * h + improved.hashCode()
    }

    override fun toString(): String =
        "FitResult(weights=${weights.contentToString()}, defaultLoss=$defaultLoss, " +
            "fittedLoss=$fittedLoss, reviewsUsed=$reviewsUsed, improved=$improved)"
}

/**
 * Fits the 19 FSRS-5 weights to one user's own review history, on the device.
 *
 * The published defaults describe the average of millions of learners. Any one learner differs -
 * some forget faster, some press Hard where others press Good, some study material that simply
 * sticks better - and FSRS's real advantage over SM-2 is that it can learn which. This is that
 * step, done locally: the review log never leaves the phone.
 *
 * **What is fitted.** For every review that is not a same-day repeat, the model predicts the
 * probability of recall from the card's reconstructed memory state and the time since it was last
 * seen; the label is whether the answer was anything but Again. The loss is the mean binary cross
 * entropy of those predictions, the same objective the reference optimisers use. The replay calls
 * [Fsrs] itself and mirrors `Scheduler.updateMemory` step for step - first answer initialises,
 * a sub-day gap takes the same-day formula and is not scored, anything longer runs the full
 * recall or lapse update - so the model being fitted is exactly the model the app schedules with,
 * not a reimplementation that could drift from it.
 *
 * **How.** Adam over mini-batches of training cards, with gradients from central finite
 * differences. Nineteen parameters make that 38 batch evaluations a step, which on a phone is far
 * cheaper than it sounds once the history is packed into primitive arrays and the replay loop
 * allocates nothing. A fixed iteration budget, a cosine learning-rate decay and an early stop on a
 * plateaued training loss keep the cost bounded: well under a few seconds for 20k reviews.
 *
 * **Why the held-out split.** With a few hundred reviews, nineteen parameters can fit noise. Cards
 * - not reviews, since a card's reviews are anything but independent - are split by a hash of
 * their id into a training set and a held-out fifth, and the defaults and the fit are both scored
 * on the held-out fifth. The split is deterministic, so running the fit twice on the same history
 * gives the same answer, and a card never moves between sets as the history grows.
 *
 * Pure: no Android, no clock, no I/O. The caller runs it off the main thread.
 */
object FsrsOptimizer {

    /**
     * Scored reviews needed before a fit is attempted at all.
     *
     * Counted in usable samples, not raw log rows: a day of new cards studied through the learning
     * steps produces plenty of rows and no samples, and it is samples the fit learns from. Below
     * this, nineteen parameters are underdetermined and the held-out set too small to tell a real
     * improvement from luck.
     */
    const val MIN_REVIEWS = 400

    /**
     * The held-out set must hold at least this many scored reviews to judge a fit on. Hashing
     * splits cards, so a history concentrated on a few heavily-reviewed cards can clear
     * [MIN_REVIEWS] and still leave almost nothing to evaluate against.
     */
    const val MIN_HELD_OUT_REVIEWS = 50

    /**
     * A fit must beat the defaults' held-out loss by at least this fraction to count as better.
     * Smaller differences are within the noise of the held-out sample, and switching weights for
     * a change the user cannot feel only makes their intervals move for no reason.
     */
    const val MIN_RELATIVE_IMPROVEMENT = 0.005

    /** Upper bound on optimiser steps; early stopping usually ends well before it. */
    private const val MAX_ITERATIONS = 200

    /** Scored reviews per mini-batch, approximately - batches are built from whole cards. */
    private const val BATCH_SAMPLES = 512

    /** Starting step size, relative to each weight's [scale]. Decays to a tenth by the end. */
    private const val LEARNING_RATE = 0.05

    /** Adam's usual constants. */
    private const val BETA1 = 0.9
    private const val BETA2 = 0.999
    private const val ADAM_EPSILON = 1e-8

    /** Relative finite-difference step. Small against every weight's range, large against noise. */
    private const val FD_STEP = 1e-4

    /** The full training loss is measured this often, to keep the best vector and to stop early. */
    private const val EVAL_EVERY = 10

    /** Stop after this many evaluations without a meaningful improvement in training loss. */
    private const val PATIENCE = 3
    private const val PLATEAU_TOLERANCE = 1e-4

    /** Predictions are clamped this far from 0 and 1, so one confident miss cannot dominate. */
    private const val PROBABILITY_EPSILON = 1e-6

    private const val HELD_OUT_BUCKETS = 5
    private const val DAY_MILLIS = 86_400_000.0
    private const val SHUFFLE_SEED = 0x5EED_F5A5L

    private val GRADES = Grade.entries.toTypedArray()
    private val AGAIN_ORDINAL = Grade.AGAIN.ordinal

    /**
     * The magnitude each weight moves on, so that one learning rate suits all nineteen: w3 lives
     * around 15 and w7 around 0.005, and a shared absolute step would either crawl on one or throw
     * the other across its whole range.
     */
    private val scale = DoubleArray(Fsrs.WEIGHT_COUNT) { Fsrs.WEIGHTS[it].coerceIn(0.1, 10.0) }

    /**
     * Fit the weights to [events] - the whole review log, in any order.
     *
     * Returns null when there is not enough history to fit honestly (fewer than [MIN_REVIEWS]
     * scored reviews, or a held-out set below [MIN_HELD_OUT_REVIEWS]), or when [isCancelled]
     * returns true. It is checked once per optimiser step, so cancellation lands within
     * milliseconds; pass something like `{ !coroutineContext.isActive }` from a coroutine on
     * Dispatchers.Default.
     */
    fun fit(events: List<ReviewEvent>, isCancelled: () -> Boolean = { false }): FitResult? {
        val data = History.of(events)
        val train = data.cardsWhere(heldOut = false)
        val test = data.cardsWhere(heldOut = true)
        val trainSamples = data.samplesIn(train)
        val testSamples = data.samplesIn(test)

        if (trainSamples + testSamples < MIN_REVIEWS) return null
        if (testSamples < MIN_HELD_OUT_REVIEWS || trainSamples == 0) return null

        val fitted = optimise(data, train, trainSamples, isCancelled) ?: return null

        val defaultLoss = data.loss(Fsrs.WEIGHTS, test, 0, test.size) / testSamples
        val fittedLoss = data.loss(fitted, test, 0, test.size) / testSamples
        val improved = fittedLoss <= defaultLoss * (1.0 - MIN_RELATIVE_IMPROVEMENT)

        return FitResult(fitted, defaultLoss, fittedLoss, trainSamples + testSamples, improved)
    }

    /** Adam on [train]; returns the best vector seen by full training loss, or null if cancelled. */
    private fun optimise(
        data: History,
        train: IntArray,
        trainSamples: Int,
        isCancelled: () -> Boolean
    ): DoubleArray? {
        val n = Fsrs.WEIGHT_COUNT
        val w = Fsrs.WEIGHTS.copyOf()
        val probe = DoubleArray(n)
        val grad = DoubleArray(n)
        val m = DoubleArray(n)
        val v = DoubleArray(n)

        val best = w.copyOf()
        var bestLoss = data.loss(w, train, 0, train.size) / trainSamples
        var stale = 0

        // Batches are runs of consecutive cards in a shuffled order, re-shuffled every epoch.
        val order = train.copyOf()
        val batchEnds = IntArray(order.size + 1)
        var batchCount = 0
        var batch = 0
        val random = Random(SHUFFLE_SEED)

        for (iteration in 0 until MAX_ITERATIONS) {
            if (isCancelled()) return null

            if (batch == batchCount) {
                shuffle(order, random)
                batchCount = data.partition(order, batchEnds)
                batch = 0
            }
            val from = if (batch == 0) 0 else batchEnds[batch - 1]
            val to = batchEnds[batch]
            val samples = data.samplesIn(order, from, to)
            batch++
            if (samples == 0) continue

            // Central differences, one weight at a time, on the same batch.
            w.copyInto(probe)
            for (i in 0 until n) {
                val h = FD_STEP * max(1.0, abs(w[i]))
                probe[i] = w[i] + h
                val up = data.loss(probe, order, from, to)
                probe[i] = w[i] - h
                val down = data.loss(probe, order, from, to)
                probe[i] = w[i]
                grad[i] = (up - down) / (2.0 * h * samples)
            }

            val progress = iteration.toDouble() / MAX_ITERATIONS
            val lr = LEARNING_RATE * (0.1 + 0.9 * 0.5 * (1.0 + cos(Math.PI * progress)))
            val t = iteration + 1
            val correction1 = 1.0 - Math.pow(BETA1, t.toDouble())
            val correction2 = 1.0 - Math.pow(BETA2, t.toDouble())
            for (i in 0 until n) {
                m[i] = BETA1 * m[i] + (1.0 - BETA1) * grad[i]
                v[i] = BETA2 * v[i] + (1.0 - BETA2) * grad[i] * grad[i]
                val step = (m[i] / correction1) / (sqrt(v[i] / correction2) + ADAM_EPSILON)
                w[i] -= lr * scale[i] * step
            }
            clamp(w)

            if ((iteration + 1) % EVAL_EVERY == 0) {
                val loss = data.loss(w, train, 0, train.size) / trainSamples
                if (loss < bestLoss * (1.0 - PLATEAU_TOLERANCE)) {
                    stale = 0
                } else {
                    stale++
                }
                if (loss < bestLoss) {
                    bestLoss = loss
                    w.copyInto(best)
                }
                if (stale >= PATIENCE) break
            }
        }

        // The last steps since the final evaluation may have been the best ones.
        val last = data.loss(w, train, 0, train.size) / trainSamples
        if (last < bestLoss) w.copyInto(best)
        return best
    }

    /**
     * Force [w] back inside the weight bounds, with initial stabilities in order.
     *
     * After every step rather than once at the end, so the optimiser never evaluates - and never
     * follows a gradient from - a region where the formulas stop meaning what they say.
     */
    internal fun clamp(w: DoubleArray) {
        for (i in 0 until Fsrs.WEIGHT_COUNT) {
            val v = w[i]
            w[i] = if (v.isFinite()) v.coerceIn(Fsrs.WEIGHT_MIN[i], Fsrs.WEIGHT_MAX[i]) else Fsrs.WEIGHTS[i]
        }
        // Again must never start a card further out than Hard, Hard than Good, Good than Easy.
        for (i in 1..3) if (w[i] < w[i - 1]) w[i] = w[i - 1]
    }

    private fun shuffle(a: IntArray, random: Random) {
        for (i in a.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = a[i]
            a[i] = a[j]
            a[j] = tmp
        }
    }

    /** Which split a card belongs to: a mixed hash of its id, so neighbouring ids scatter. */
    private fun isHeldOut(cardId: Long): Boolean {
        // SplitMix64's finaliser. Row ids are sequential; a plain modulo would hold out every
        // fifth card created, which correlates with whatever deck was being built at the time.
        var z = cardId + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 1) % HELD_OUT_BUCKETS == 0L
    }

    /**
     * The review log packed for replay: every card's answers contiguous and in time order, as
     * primitive arrays, so the loss loop - which runs tens of thousands of times a fit - touches no
     * boxed values and allocates nothing.
     */
    private class History(
        /** Grade ordinal of each event. */
        private val grades: IntArray,
        /** Days since the card's previous event; unused for a card's first. */
        private val elapsed: DoubleArray,
        /** Card c's events are `cardStart[c] until cardStart[c + 1]`. */
        private val cardStart: IntArray,
        /** Scored reviews each card contributes. */
        private val cardSamples: IntArray,
        private val heldOut: BooleanArray
    ) {
        val cardCount: Int get() = cardSamples.size

        fun cardsWhere(heldOut: Boolean): IntArray {
            var count = 0
            for (c in 0 until cardCount) if (this.heldOut[c] == heldOut) count++
            val out = IntArray(count)
            var k = 0
            for (c in 0 until cardCount) if (this.heldOut[c] == heldOut) out[k++] = c
            return out
        }

        fun samplesIn(cards: IntArray, from: Int = 0, to: Int = cards.size): Int {
            var total = 0
            for (k in from until to) total += cardSamples[cards[k]]
            return total
        }

        /** Split [order] into runs of about [BATCH_SAMPLES] samples; returns the run count. */
        fun partition(order: IntArray, ends: IntArray): Int {
            var count = 0
            var acc = 0
            for (k in order.indices) {
                acc += cardSamples[order[k]]
                if (acc >= BATCH_SAMPLES) {
                    ends[count++] = k + 1
                    acc = 0
                }
            }
            if (acc > 0 || count == 0) ends[count++] = order.size
            return count
        }

        /**
         * Summed log loss of [w] over `cards[from until to]`.
         *
         * Mirrors `Scheduler.updateMemory` exactly - including that a lapse is computed from the
         * difficulty *before* this answer, while the new difficulty is computed regardless and
         * carried forward. Change one and the other has to change with it.
         */
        fun loss(w: DoubleArray, cards: IntArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (k in from until to) {
                val c = cards[k]
                val start = cardStart[c]
                val end = cardStart[c + 1]

                val first = GRADES[grades[start]]
                var s = Fsrs.initialStability(first, w)
                var d = Fsrs.initialDifficulty(first, w)

                for (e in start + 1 until end) {
                    val grade = GRADES[grades[e]]
                    val nextD = Fsrs.nextDifficulty(d, grade, w)
                    val days = elapsed[e]
                    if (days < 1.0) {
                        s = Fsrs.stabilityAfterSameDay(s, grade, w)
                    } else {
                        val r = Fsrs.retrievability(days, s)
                        val p = r.coerceIn(PROBABILITY_EPSILON, 1.0 - PROBABILITY_EPSILON)
                        if (grades[e] == AGAIN_ORDINAL) {
                            sum -= ln(1.0 - p)
                            s = Fsrs.stabilityAfterLapse(d, s, r, w)
                        } else {
                            sum -= ln(p)
                            s = Fsrs.stabilityAfterRecall(d, s, r, grade, w)
                        }
                    }
                    d = nextD
                }
            }
            return sum
        }

        companion object {
            fun of(events: List<ReviewEvent>): History {
                val sorted = events.sortedWith(compareBy<ReviewEvent>({ it.cardId }, { it.reviewedAt }))
                val n = sorted.size
                val grades = IntArray(n)
                val elapsed = DoubleArray(n)

                var cards = 0
                for (i in 0 until n) {
                    if (i == 0 || sorted[i].cardId != sorted[i - 1].cardId) cards++
                }
                val cardStart = IntArray(cards + 1)
                val cardSamples = IntArray(cards)
                val heldOut = BooleanArray(cards)

                var c = -1
                for (i in 0 until n) {
                    val event = sorted[i]
                    grades[i] = event.grade.ordinal
                    if (i == 0 || event.cardId != sorted[i - 1].cardId) {
                        c++
                        cardStart[c] = i
                        heldOut[c] = isHeldOut(event.cardId)
                    } else {
                        val days = (event.reviewedAt - sorted[i - 1].reviewedAt) / DAY_MILLIS
                        elapsed[i] = days
                        if (days >= 1.0) cardSamples[c]++
                    }
                }
                cardStart[cards] = n
                return History(grades, elapsed, cardStart, cardSamples, heldOut)
            }
        }
    }
}

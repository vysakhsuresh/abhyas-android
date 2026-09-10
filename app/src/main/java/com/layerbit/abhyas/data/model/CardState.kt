package com.layerbit.abhyas.data.model

/**
 * Where a card sits in its life cycle.
 *
 * Plain SM-2 only has "new" and "reviewing", and sends a brand-new card straight to a one-day
 * interval the first time you answer it. That reads as broken to anyone learning something for
 * the first time: you see a card once, say Good, and it vanishes for a day.
 *
 * So new cards go through short in-session steps first (minutes, not days) and only [graduate]
 * into day-scale scheduling once they have actually been recalled. A lapse drops a mature card
 * back into the same short loop rather than resetting it to new, which keeps its history.
 */
enum class CardState {
    /** Never answered. */
    NEW,

    /** Being learned for the first time, moving through the minute-scale steps. */
    LEARNING,

    /** Graduated. Intervals are in days and grow by the ease factor. */
    REVIEW,

    /** Was in REVIEW and got an Again. Back on the short steps until recalled. */
    RELEARNING
}

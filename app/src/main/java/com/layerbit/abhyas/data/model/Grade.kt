package com.layerbit.abhyas.data.model

/**
 * What the user says after seeing the answer. Four buttons rather than SuperMemo's 0-5 scale,
 * because a self-grade is a gut reaction and nobody can honestly distinguish six shades of it.
 *
 * [sm2Quality] is how each answer maps back onto that original scale, which is what the ease
 * factor arithmetic in [com.layerbit.abhyas.data.srs.Scheduler] is defined in terms of.
 */
enum class Grade(val sm2Quality: Int, val label: String) {
    /** Did not know it. The card comes back in this same session. */
    AGAIN(0, "Again"),

    /** Got there, but it was a struggle. Schedules shorter than last time's growth. */
    HARD(3, "Hard"),

    /** Knew it with normal effort. The expected answer. */
    GOOD(4, "Good"),

    /** Instant and certain. Pushes the card further out than Good. */
    EASY(5, "Easy")
}

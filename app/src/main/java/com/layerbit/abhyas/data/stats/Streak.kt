package com.layerbit.abhyas.data.stats

import java.time.LocalDate

/**
 * How many days in a row the user has studied.
 *
 * Pure, and separate from the database, because the interesting part is a judgement call rather
 * than a query: **a streak does not break the moment midnight passes.** If you studied yesterday
 * and it is now 9am, your streak is intact - you simply have not done today's yet. Counting from
 * today alone would show every user a zero every morning, which is precisely the hour they are
 * deciding whether to bother.
 *
 * So the streak is allowed to start at yesterday, and only a full missed day ends it.
 */
object Streak {

    /**
     * @param activeDays every local date on which at least one card was answered, in any order.
     * @param today the device's current local date.
     */
    fun current(activeDays: Collection<LocalDate>, today: LocalDate): Int {
        if (activeDays.isEmpty()) return 0
        val days = activeDays.toHashSet()

        // Start from today if it has been studied, otherwise yesterday - see above.
        var cursor = when {
            today in days -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> return 0
        }

        var length = 0
        while (cursor in days) {
            length++
            cursor = cursor.minusDays(1)
        }
        return length
    }

    /** Whether today's studying is still outstanding, for wording the streak in the UI. */
    fun studiedToday(activeDays: Collection<LocalDate>, today: LocalDate): Boolean =
        today in activeDays

    /**
     * Parse the `YYYY-MM-DD` strings the review-log query groups by.
     *
     * Anything unparseable is dropped rather than thrown. A malformed row should cost the user a
     * day on a counter, not the ability to open the screen.
     */
    fun parseDays(days: List<String>): List<LocalDate> =
        days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
}

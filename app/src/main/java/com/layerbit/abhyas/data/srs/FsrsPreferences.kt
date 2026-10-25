package com.layerbit.abhyas.data.srs

import android.content.Context

/**
 * The FSRS weights this user's schedule runs on: the published defaults, or a set fitted to their
 * own review history from Insights.
 *
 * SharedPreferences, read once per process and cached. The study screen asks for the weights on
 * every card, and a disk read per card would be the one slow thing in the loop.
 */
class FsrsPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("fsrs", Context.MODE_PRIVATE)

    @Volatile
    private var cached: DoubleArray? = null

    /** The weights in force. Falls back to the defaults for anything missing or malformed. */
    val weights: DoubleArray
        get() = cached ?: load().also { cached = it }

    /** Whether the user is on a fitted schedule rather than the defaults. */
    val isPersonal: Boolean get() = prefs.contains(KEY_WEIGHTS) && !weights.contentEquals(Fsrs.WEIGHTS)

    /** What the last fit measured, for Insights to show next time it opens. */
    val lastImprovementPercent: Double?
        get() = if (prefs.contains(KEY_IMPROVEMENT)) {
            prefs.getFloat(KEY_IMPROVEMENT, 0f).toDouble()
        } else {
            null
        }

    val lastFittedAt: Long get() = prefs.getLong(KEY_FITTED_AT, 0L)

    fun save(weights: DoubleArray, improvementPercent: Double, at: Long) {
        if (!Fsrs.isValidWeights(weights)) return
        prefs.edit()
            .putString(KEY_WEIGHTS, weights.joinToString(","))
            .putFloat(KEY_IMPROVEMENT, improvementPercent.toFloat())
            .putLong(KEY_FITTED_AT, at)
            .apply()
        cached = weights.copyOf()
    }

    fun reset() {
        prefs.edit().remove(KEY_WEIGHTS).remove(KEY_IMPROVEMENT).remove(KEY_FITTED_AT).apply()
        cached = Fsrs.WEIGHTS
    }

    private fun load(): DoubleArray {
        val stored = prefs.getString(KEY_WEIGHTS, null) ?: return Fsrs.WEIGHTS
        val parsed = stored.split(',').mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
        // A value from a corrupt write, or from a future build with a different parameter count,
        // must not reach the scheduler: one NaN there and every interval in the deck is NaN.
        return if (Fsrs.isValidWeights(parsed)) parsed else Fsrs.WEIGHTS
    }

    private companion object {
        const val KEY_WEIGHTS = "weights"
        const val KEY_IMPROVEMENT = "improvement_percent"
        const val KEY_FITTED_AT = "fitted_at"
    }
}

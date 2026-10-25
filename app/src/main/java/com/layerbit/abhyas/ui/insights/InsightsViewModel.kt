package com.layerbit.abhyas.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.data.srs.FsrsOptimizer
import com.layerbit.abhyas.data.srs.FsrsPreferences
import com.layerbit.abhyas.data.srs.ReviewEvent
import com.layerbit.abhyas.data.stats.Streak
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where re-fitting the schedule stands, for the card on the Insights screen. */
sealed interface FitState {
    /** Not run this visit. [personal] says whether a fitted set is already in force. */
    data class Idle(val personal: Boolean, val lastImprovement: Double?) : FitState
    data object Running : FitState

    /** Fitted, measurably better on reviews it was not fitted to, and now in use. */
    data class Applied(val improvementPercent: Double, val reviews: Int) : FitState

    /** Fitted, but no better than the defaults - so the defaults stay. */
    data class NoGain(val reviews: Int) : FitState

    /** Not enough history to fit honestly. */
    data class NotEnough(val have: Int, val need: Int) : FitState
}

class InsightsViewModel(
    private val repository: AbhyasRepository,
    private val fsrs: FsrsPreferences
) : ViewModel() {

    /**
     * Thirty days rather than all time.
     *
     * Retention is meant to answer "is the schedule pitched right *for me, now*". A lifetime
     * average is dominated by the weeks when the user was still learning how to grade honestly,
     * and stops moving at all once there is enough history - which makes it useless as a signal.
     */
    val retention = repository.retentionInLast(days = 30)
        .map { it.rate }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val maturity = repository.maturity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val forecast = repository.forecast(days = 14)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val leeches = repository.leeches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streak = repository
        .dailyCountsInLast(days = 90)
        .map { counts -> Streak.current(Streak.parseDays(counts.map { it.day }), LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _fit = MutableStateFlow<FitState>(idle())
    val fit: StateFlow<FitState> = _fit.asStateFlow()

    private var fitting: Job? = null

    private fun idle() = FitState.Idle(fsrs.isPersonal, fsrs.lastImprovementPercent)

    /**
     * Fit the 19 FSRS weights to this user's own answers, on the phone.
     *
     * The fitter judges itself on cards it was not fitted to and only claims an improvement it
     * measured there, so a set that merely memorised the history is never applied. When it is not
     * better, the defaults stay and the user is told so - a re-fit that always "succeeds" would be
     * a button that does nothing with a progress bar on it.
     */
    fun refit() {
        if (fitting?.isActive == true) return
        _fit.value = FitState.Running
        fitting = viewModelScope.launch {
            val events = repository.allAnswers().map { ReviewEvent(it.cardId, it.reviewedAt, it.grade) }
            val result = withContext(Dispatchers.Default) {
                FsrsOptimizer.fit(events) { !isActive }
            }
            _fit.value = when {
                result == null -> FitState.NotEnough(have = events.size, need = FsrsOptimizer.MIN_REVIEWS)
                !result.improved -> FitState.NoGain(result.reviewsUsed)
                else -> {
                    fsrs.save(result.weights, result.improvementPercent, System.currentTimeMillis())
                    FitState.Applied(result.improvementPercent, result.reviewsUsed)
                }
            }
        }
    }

    /** Back to the published defaults. */
    fun resetSchedule() {
        fsrs.reset()
        _fit.value = idle()
    }

    /**
     * Put a set-aside card back in rotation.
     *
     * The lapse count is deliberately left alone. Resetting it would mean the card quietly
     * becomes a leech again in another eight failures with no memory that it already was one,
     * and the user would have no way to tell the difference.
     */
    fun unsuspend(card: CardEntity) {
        viewModelScope.launch { repository.setSuspended(card, false) }
    }
}

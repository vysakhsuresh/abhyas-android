package com.layerbit.abhyas.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.data.stats.Streak
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InsightsViewModel(private val repository: AbhyasRepository) : ViewModel() {

    private val monthAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)

    /**
     * Thirty days rather than all time.
     *
     * Retention is meant to answer "is the schedule pitched right *for me, now*". A lifetime
     * average is dominated by the weeks when the user was still learning how to grade honestly,
     * and stops moving at all once there is enough history - which makes it useless as a signal.
     */
    val retention = repository.retention(monthAgo)
        .map { it.rate }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val maturity = repository.maturity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val forecast = repository.forecast(days = 14)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val leeches = repository.leeches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streak = repository
        .dailyCounts(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90))
        .map { counts -> Streak.current(Streak.parseDays(counts.map { it.day }), LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

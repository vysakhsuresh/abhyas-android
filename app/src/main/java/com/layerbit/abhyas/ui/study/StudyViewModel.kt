package com.layerbit.abhyas.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.repo.AbhyasRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudyState(
    val loading: Boolean = true,
    val card: CardEntity? = null,
    val answerShown: Boolean = false,
    /** Cards left in this sitting, including the one on screen. */
    val remaining: Int = 0,
    /** How many answers have been given this sitting - the only number that shows progress. */
    val answered: Int = 0,
    val finished: Boolean = false,
    /** When the soonest card comes back, once the sitting is over. Null if nothing is scheduled. */
    val nextDueAt: Long? = null
)

class StudyViewModel(
    private val repository: AbhyasRepository,
    private val deckId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(StudyState())
    val state: StateFlow<StudyState> = _state.asStateFlow()

    /**
     * The sitting's queue, held in memory rather than re-queried per card.
     *
     * It has to be mutable because a card answered Again comes straight back into this same
     * sitting - that is the entire point of the minute-scale learning steps. Re-running the
     * database query after every answer would instead keep handing back whatever is due *now*,
     * and a card due in one minute would either vanish or repeat immediately.
     */
    private val queue = mutableListOf<CardEntity>()

    init {
        viewModelScope.launch {
            queue += repository.buildQueue(deckId)
            advance()
        }
    }

    fun showAnswer() {
        _state.value = _state.value.copy(answerShown = true)
    }

    fun answer(grade: Grade) {
        val current = _state.value.card ?: return
        // Guard the double tap: without this, a fast second press grades the same card twice and
        // pushes its interval out on a review the user never actually did.
        if (!_state.value.answerShown) return

        viewModelScope.launch {
            val updated = repository.answer(current, grade)
            queue.remove(current)

            // Anything still on a minute-scale step belongs in this sitting. Re-inserted in due
            // order so the one-minute card comes back before the ten-minute one.
            if (updated.state == CardState.LEARNING || updated.state == CardState.RELEARNING) {
                val at = queue.indexOfFirst { it.dueAt > updated.dueAt }
                if (at == -1) queue.add(updated) else queue.add(at, updated)
            }

            _state.value = _state.value.copy(answered = _state.value.answered + 1)
            advance()
        }
    }

    private suspend fun advance() {
        val next = queue.firstOrNull()
        if (next == null) {
            _state.value = _state.value.copy(
                loading = false,
                card = null,
                answerShown = false,
                remaining = 0,
                finished = true,
                nextDueAt = repository.nextDueAt(deckId)
            )
            return
        }
        _state.value = _state.value.copy(
            loading = false,
            card = next,
            answerShown = false,
            remaining = queue.size,
            finished = false
        )
    }
}

package com.layerbit.abhyas.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.data.repo.AnsweredReview
import com.layerbit.abhyas.data.srs.Scheduler
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
    val nextDueAt: Long? = null,
    /** Whether the last answer can still be taken back. */
    val canUndo: Boolean = false,
    /**
     * How long each button would put the card away for, in milliseconds from now.
     *
     * Shown on the buttons themselves. It turns a blind self-grade into an informed one - "Good"
     * meaning three weeks and "Easy" meaning three months is the difference the user is actually
     * choosing between, and no other app on a phone tells them before they tap.
     *
     * A duration rather than `intervalDays`, because a card in learning is due again in minutes
     * and its interval in whole days is zero - which rendered three of the four buttons as an
     * identical, useless "0d" on exactly the reviews a new card spends most of its life in.
     */
    val previews: Map<Grade, Long> = emptyMap(),
    /** Set when an answer just suspended a card for being forgotten too often. */
    val leechWarning: String? = null
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

    /** The last answer given, held so it can be taken back. One step only - see [undo]. */
    private var lastReview: AnsweredReview? = null

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
            val review = repository.answer(current, grade)
            lastReview = review
            queue.remove(current)

            // Anything still on a minute-scale step belongs in this sitting. Re-inserted in due
            // order so the one-minute card comes back before the ten-minute one. A card just
            // suspended for being a leech is not re-queued - that is the point of suspending it.
            val updated = review.after
            val staysInSession = !updated.suspended &&
                (updated.state == CardState.LEARNING || updated.state == CardState.RELEARNING)
            if (staysInSession) {
                val at = queue.indexOfFirst { it.dueAt > updated.dueAt }
                if (at == -1) queue.add(updated) else queue.add(at, updated)
            }

            _state.value = _state.value.copy(
                answered = _state.value.answered + 1,
                leechWarning = if (review.becameLeech) {
                    "You have forgotten this one ${updated.lapses} times. It is set aside - " +
                        "a card this sticky usually needs rewriting, not repeating."
                } else {
                    null
                }
            )
            advance()
        }
    }

    /**
     * Take back the last answer and put the card back in front of the user.
     *
     * Only one step, deliberately. Multi-level undo in a review app invites someone to unwind
     * half a session they half-remember, and the mistake this exists for - tapping Easy when you
     * meant Again - is always the answer you just gave.
     */
    fun undo() {
        val review = lastReview ?: return
        lastReview = null

        viewModelScope.launch {
            repository.undo(review)

            queue.removeAll { it.id == review.before.id }
            queue.add(0, review.before)

            _state.value = _state.value.copy(
                answered = (_state.value.answered - 1).coerceAtLeast(0),
                leechWarning = null
            )
            advance(showAnswer = true)
        }
    }

    fun dismissLeechWarning() {
        _state.value = _state.value.copy(leechWarning = null)
    }

    private suspend fun advance(showAnswer: Boolean = false) {
        val next = queue.firstOrNull()
        if (next == null) {
            _state.value = _state.value.copy(
                loading = false,
                card = null,
                answerShown = false,
                remaining = 0,
                finished = true,
                canUndo = lastReview != null,
                previews = emptyMap(),
                nextDueAt = repository.nextDueAt(deckId)
            )
            return
        }
        _state.value = _state.value.copy(
            loading = false,
            card = next,
            answerShown = showAnswer,
            remaining = queue.size,
            finished = false,
            canUndo = lastReview != null,
            previews = previewsFor(next)
        )
    }

    /**
     * What each button would schedule for this card, in days.
     *
     * Computed by running the real scheduler four times rather than approximating, so what the
     * button says is exactly what pressing it does. Cheap - FSRS is a handful of floating point
     * operations and this runs once per card, not per frame.
     */
    private fun previewsFor(card: CardEntity): Map<Grade, Long> {
        val now = System.currentTimeMillis()
        val scheduling = card.scheduling()
        return Grade.entries.associateWith { grade ->
            (Scheduler.next(scheduling, grade, now).dueAt - now).coerceAtLeast(0L)
        }
    }
}

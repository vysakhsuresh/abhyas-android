package com.layerbit.abhyas.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.data.repo.AnsweredReview
import com.layerbit.abhyas.data.srs.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a cram session studies: every card matching a search term and/or a tag, due or not.
 *
 * "Exam on Thursday" mode. The real schedule is not touched - see [StudyViewModel.answer] - so
 * revising everything the night before costs nothing the next day.
 */
data class CramFilter(val term: String = "", val tag: String = "")

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
     *
     * Empty in a cram session, where the buttons schedule nothing.
     */
    val previews: Map<Grade, Long> = emptyMap(),
    /**
     * Set to the card's lapse count when an answer just suspended it for being forgotten too
     * often. A number rather than a sentence, so the screen can say it in the app's language.
     */
    val leechLapses: Int? = null,
    /** The script of the card on screen, so it is read aloud in the right language. */
    val script: ScriptOption = ScriptOption.DEFAULT,
    /** Studying a filter ahead of an exam, with the schedule left alone. */
    val cram: Boolean = false
)

class StudyViewModel(
    private val repository: AbhyasRepository,
    private val deckId: Long,
    private val cramFilter: CramFilter? = null
) : ViewModel() {

    private val _state = MutableStateFlow(StudyState(cram = cramFilter != null))
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

    /** In a cram session there is no stored review to undo, just the card and where it was. */
    private var lastCram: Pair<CardEntity, Int>? = null

    /** Each deck's script, looked up once. A cram session spans decks. */
    private val scripts = mutableMapOf<Long, ScriptOption>()

    /**
     * The in-flight answer or undo.
     *
     * Both write to the database, the queue and [lastReview], and neither may overlap the other.
     * `answerShown` cannot do this job: it is only cleared inside [advance], which runs *after* the
     * database write has suspended, so for the whole duration of that write the flag still reads
     * true and a second tap passes exactly the same guard the first one did. The card would be
     * graded twice - two rows in the review log, the interval pushed out on a review the user never
     * did, and `lastReview` overwritten so undo could only take back one of the two.
     */
    private var mutating: Job? = null

    init {
        viewModelScope.launch {
            queue += if (cramFilter != null) {
                repository.cramQueue(cramFilter.term, cramFilter.tag)
            } else {
                repository.buildQueue(deckId)
            }
            advance()
        }
    }

    fun showAnswer() {
        _state.value = _state.value.copy(answerShown = true)
    }

    fun answer(grade: Grade) {
        // Guard the double tap. See [mutating] for why the answerShown check below cannot.
        if (mutating?.isActive == true) return
        val current = _state.value.card ?: return
        // Grading a card whose answer has not been revealed is not a review, it is a guess about a
        // guess, so the buttons are not live until the user has looked.
        if (!_state.value.answerShown) return

        if (cramFilter != null) {
            answerCram(current, grade)
            return
        }

        mutating = viewModelScope.launch {
            val review = repository.answer(current, grade)
            lastReview = review
            queue.remove(current)
            // Its siblings were just held back until tomorrow in the database; they leave this
            // sitting too, or the next card on screen would be the one whose answer was just shown.
            if (review.buried.isNotEmpty()) queue.removeAll { it.id in review.buried }

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

            // Handed to advance rather than published first, so one answer is one state change.
            // Emitting the new count and then the new card recomposed the whole study screen twice,
            // and for the frame in between it showed a progress count that had moved on with a card
            // that had not.
            advance(
                answered = _state.value.answered + 1,
                leechLapses = if (review.becameLeech) updated.lapses else null
            )
        }
    }

    /**
     * A cram answer: nothing is written, nothing is logged. A forgotten card comes back a few
     * cards later in the same sitting, which is the only scheduling cramming needs.
     */
    private fun answerCram(current: CardEntity, grade: Grade) {
        mutating = viewModelScope.launch {
            val position = queue.indexOf(current)
            queue.remove(current)
            if (grade == Grade.AGAIN) queue.add(minOf(CRAM_RETRY_GAP, queue.size), current)
            lastCram = current to position.coerceAtLeast(0)
            advance(answered = _state.value.answered + 1, leechLapses = null)
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
        if (mutating?.isActive == true) return

        lastCram?.let { (card, _) ->
            lastCram = null
            mutating = viewModelScope.launch {
                queue.removeAll { it.id == card.id }
                queue.add(0, card)
                advance(showAnswer = true, answered = (_state.value.answered - 1).coerceAtLeast(0), leechLapses = null)
            }
            return
        }

        val review = lastReview ?: return
        lastReview = null

        mutating = viewModelScope.launch {
            repository.undo(review)

            queue.removeAll { it.id == review.before.id }
            queue.add(0, review.before)

            advance(
                showAnswer = true,
                answered = (_state.value.answered - 1).coerceAtLeast(0),
                leechLapses = null
            )
        }
    }

    fun dismissLeechWarning() {
        _state.value = _state.value.copy(leechLapses = null)
    }

    /**
     * Publish the next card, or the end of the sitting.
     *
     * [answered] and [leechLapses] are passed through rather than written by the caller beforehand,
     * so that every action produces exactly one state change and no observer ever sees a half-moved
     * sitting.
     */
    private suspend fun advance(
        showAnswer: Boolean = false,
        answered: Int = _state.value.answered,
        leechLapses: Int? = _state.value.leechLapses
    ) {
        val canUndo = lastReview != null || lastCram != null
        val next = queue.firstOrNull()
        if (next == null) {
            _state.value = _state.value.copy(
                loading = false,
                card = null,
                answerShown = false,
                remaining = 0,
                finished = true,
                canUndo = canUndo,
                previews = emptyMap(),
                nextDueAt = if (cramFilter == null) repository.nextDueAt(deckId) else null,
                answered = answered,
                leechLapses = leechLapses
            )
            return
        }
        _state.value = _state.value.copy(
            loading = false,
            card = next,
            answerShown = showAnswer,
            remaining = queue.size,
            finished = false,
            canUndo = canUndo,
            previews = if (cramFilter == null) previewsFor(next) else emptyMap(),
            answered = answered,
            leechLapses = leechLapses,
            script = scriptOf(next)
        )
    }

    private suspend fun scriptOf(card: CardEntity): ScriptOption =
        scripts.getOrPut(card.deckId) { repository.deckScript(card.deckId) }

    /**
     * What each button would schedule for this card.
     *
     * Computed by running the real scheduler four times, with the same weights the answer will
     * use, rather than approximating - so what the button says is exactly what pressing it does.
     * Cheap: FSRS is a handful of floating point operations and this runs once per card.
     */
    private fun previewsFor(card: CardEntity): Map<Grade, Long> {
        val now = System.currentTimeMillis()
        val scheduling = card.scheduling()
        val weights = repository.currentWeights()
        return Grade.entries.associateWith { grade ->
            (Scheduler.next(scheduling, grade, now, weights = weights).dueAt - now).coerceAtLeast(0L)
        }
    }

    private companion object {
        /** How many cards later a forgotten card returns in a cram session. */
        const val CRAM_RETRY_GAP = 3
    }
}

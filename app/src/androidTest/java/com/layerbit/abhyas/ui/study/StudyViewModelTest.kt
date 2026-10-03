package com.layerbit.abhyas.ui.study

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.layerbit.abhyas.data.db.AbhyasDatabase
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.generate.CardKind
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.repo.AbhyasRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sitting: which card comes next, what returns within the session, and what undo puts back.
 *
 * This is the one piece of state in the app the user is looking straight at, and the logic is
 * subtler than it reads - a card answered Again comes back inside the same sitting, which is the
 * whole point of minute-scale learning steps, and that re-insertion is hand-maintained rather than
 * re-queried. Getting it wrong either loses the card or serves it twice.
 *
 * Every assertion goes through [awaitState] rather than reading `state.value`, because each action
 * launches into `viewModelScope` and the database write suspends before the state is reassigned.
 * Reading the value straight afterwards reads the state from *before* the action - which is, as it
 * happens, the same mistake the double-tap guard in the code under test used to make.
 *
 * runBlocking rather than runTest, deliberately. `viewModelScope` dispatches to the real main
 * looper, which runTest has no control over - so its virtual clock would run the timeouts below to
 * completion in an instant while the work they are waiting on had not yet been posted. Real time is
 * the honest choice when the thing under test is not on the test dispatcher.
 */
@RunWith(AndroidJUnit4::class)
class StudyViewModelTest {

    private lateinit var db: AbhyasDatabase
    private lateinit var repository: AbhyasRepository

    /**
     * Owns the ViewModels so they can be cleared before the database closes.
     *
     * Without it, a ViewModel coroutine still in flight at teardown queries a closed database and
     * brings the whole instrumentation process down - which then looks like a failure in whichever
     * test happened to run next.
     */
    private val store = ViewModelStore()

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AbhyasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AbhyasRepository(db)
    }

    @After
    fun close() {
        store.clear()
        db.close()
    }

    private suspend fun deckOf(count: Int): Long {
        val deckId = repository.createDeck("Biology")
        if (count > 0) {
            repository.addCards(
                deckId,
                (1..count).map {
                    CardCandidate(
                        front = "Question $it",
                        back = "Answer $it",
                        sourceText = "Question $it is defined as answer $it.",
                        kind = CardKind.DEFINITION,
                        confidence = 1f
                    )
                }
            )
        }
        return deckId
    }

    private fun studying(deckId: Long) =
        StudyViewModel(repository, deckId).also { store.put("study", it) }

    /** Wait for the sitting to reach a state, rather than assuming it already has. */
    private suspend fun StudyViewModel.awaitState(
        description: String,
        predicate: (StudyState) -> Boolean
    ): StudyState = try {
        withTimeout(5_000) { state.first(predicate) }
    } catch (e: Throwable) {
        throw AssertionError("never reached $description; state was ${state.value}", e)
    }

    private suspend fun StudyViewModel.awaitLoaded() =
        awaitState("a loaded queue") { !it.loading }

    /**
     * Reveal, grade, and wait for the answer to land - retrying if the grade was refused.
     *
     * The retry is here because the new state is published from inside the coroutine that performs
     * the write, a moment before that coroutine completes. A test grading again immediately lands in
     * that window and is correctly refused by the double-tap guard - which no real finger could do,
     * but which otherwise leaves this helper waiting for a count that will never arrive.
     *
     * Retrying cannot double-grade: while the previous answer is still in flight, `answer` is a
     * no-op, and once it has landed the predicate below is already satisfied and we never retry.
     */
    private suspend fun StudyViewModel.grade(grade: Grade, expectAnswered: Int): StudyState {
        repeat(5) {
            if (!state.value.answerShown) showAnswer()
            answer(grade)
            withTimeoutOrNull(2_000) { state.first { s -> s.answered == expectAnswered } }
                ?.let { return it }
        }
        throw AssertionError("$grade never produced $expectAnswered answers; state was ${state.value}")
    }

    /**
     * Take back the last answer and wait for it, retrying for the same reason [grade] does.
     *
     * Undo is refused while an answer is still in flight, and a test can reach that window where a
     * finger cannot. Retrying is safe: if the undo was refused, `lastReview` was not consumed.
     */
    private suspend fun StudyViewModel.undoAndSettle(expectAnswered: Int): StudyState {
        repeat(5) {
            undo()
            withTimeoutOrNull(2_000) { state.first { s -> s.answered == expectAnswered } }
                ?.let { return it }
        }
        throw AssertionError("undo never took effect; state was ${state.value}")
    }

    private suspend fun theOnlyCard() =
        repository.cardsInDeck(db.deckDao().all().single().id).first().single()

    @Test
    fun aSittingStartsOnTheFirstCardWithTheAnswerHidden() = runBlocking {
        val state = studying(deckOf(3)).awaitLoaded()

        assertNotNull("a deck with cards must offer one", state.card)
        assertFalse("the answer is never shown before it is asked for", state.answerShown)
        assertEquals(3, state.remaining)
        assertEquals(0, state.answered)
        assertFalse(state.finished)
    }

    @Test
    fun theGradeButtonsDoNothingUntilTheAnswerHasBeenSeen() = runBlocking {
        // Grading a card you have not looked at is not a review, it is a guess about a guess. The
        // guard is synchronous, so there is nothing to wait for here.
        val model = studying(deckOf(2))
        model.awaitLoaded()

        model.answer(Grade.GOOD)

        assertEquals("nothing may be recorded", 0, model.state.value.answered)
        assertEquals(0, repository.totalReviews().first())
    }

    @Test
    fun answeringAdvancesToTheNextCard() = runBlocking {
        val model = studying(deckOf(2))
        val first = model.awaitLoaded().card

        // Easy graduates the card out of the sitting, so the next card must be a different one.
        val after = model.grade(Grade.EASY, expectAnswered = 1)

        assertTrue("the sitting must move on", after.card?.id != first?.id)
        assertEquals(1, repository.totalReviews().first())
    }

    @Test
    fun aCardAnsweredAgainComesBackWithinTheSameSitting() = runBlocking {
        // The entire reason the queue is held in memory rather than re-queried: a card put back on a
        // one-minute step is not due "now", so a fresh query would drop it and the user would never
        // see again the card they had just said they could not remember.
        val model = studying(deckOf(1))
        val card = model.awaitLoaded().card

        val after = model.grade(Grade.AGAIN, expectAnswered = 1)

        assertFalse("the sitting is not over", after.finished)
        assertEquals("the same card must be back in front of the user", card?.id, after.card?.id)
        assertEquals(1, after.remaining)
    }

    @Test
    fun aGraduatedCardLeavesTheSitting() = runBlocking {
        val model = studying(deckOf(1))
        model.awaitLoaded()

        val after = model.grade(Grade.EASY, expectAnswered = 1)

        assertTrue("Easy on the only card ends the sitting", after.finished)
        assertEquals(0, after.remaining)
    }

    @Test
    fun aDoubleTapOnAGradeButtonGradesTheCardOnce() = runBlocking {
        // The guard used to be `answerShown`, which is only cleared after the database write has
        // suspended - so both taps passed it, two rows landed in the review log, the interval was
        // pushed out on a review the user never did, and undo could take back only one of them.
        val model = studying(deckOf(3))
        model.awaitLoaded()

        model.showAnswer()
        model.answer(Grade.GOOD)
        model.answer(Grade.GOOD)

        val after = model.awaitState("one answer") { it.answered == 1 }
        assertEquals("one tap, one review", 1, after.answered)
        assertEquals(1, repository.totalReviews().first())
    }

    @Test
    fun undoPutsTheCardBackWithItsAnswerAlreadyShowing() = runBlocking {
        // Undo exists for one mistake - tapping Easy when you meant Again - so it has to return the
        // user to the moment before that tap, answer and all, not to a hidden card they must reveal
        // again before they can correct themselves.
        val model = studying(deckOf(2))
        val card = model.awaitLoaded().card

        val graded = model.grade(Grade.EASY, expectAnswered = 1)
        assertTrue("undo must be offered", graded.canUndo)

        val after = model.undoAndSettle(expectAnswered = 0)

        assertEquals("the graded card comes back", card?.id, after.card?.id)
        assertTrue("and comes back revealed", after.answerShown)
        assertEquals("the review must leave no trace", 0, repository.totalReviews().first())
    }

    @Test
    fun undoIsOfferedOnceAndNotTwice() = runBlocking {
        // One step only, deliberately: multi-level undo invites someone to unwind half a session they
        // half-remember, and the mistake it exists for is always the answer just given.
        val model = studying(deckOf(3))
        model.awaitLoaded()
        model.grade(Grade.GOOD, expectAnswered = 1)

        val after = model.undoAndSettle(expectAnswered = 0)
        assertFalse("there is nothing left to take back", after.canUndo)

        model.undo()

        assertEquals("a second undo must not rewind further", 0, model.state.value.answered)
        assertEquals(0, repository.totalReviews().first())
    }

    @Test
    fun undoAfterTheSittingHasEndedReopensIt() = runBlocking {
        val model = studying(deckOf(1))
        model.awaitLoaded()
        assertTrue("the sitting should be over", model.grade(Grade.EASY, expectAnswered = 1).finished)

        val after = model.undoAndSettle(expectAnswered = 0)

        assertFalse("taking back the last answer un-ends the sitting", after.finished)
        assertNotNull("the card must come back", after.card)
    }

    @Test
    fun everyGradeButtonCarriesTheIntervalPressingItWouldSchedule() = runBlocking {
        val model = studying(deckOf(1))
        val loaded = model.awaitLoaded()

        val previews = loaded.previews
        assertEquals("all four buttons are labelled", Grade.entries.size, previews.size)
        previews.forEach { (grade, millis) ->
            assertTrue("$grade must preview a real delay, was $millis", millis > 0)
        }
        assertTrue(
            "Easy must put the card away for longer than Again",
            previews.getValue(Grade.EASY) > previews.getValue(Grade.AGAIN)
        )

        // And the preview has to be what pressing it does, or the label is a lie. This is also what
        // the interval spread had to stay a pure function for.
        val promised = previews.getValue(Grade.GOOD)
        val before = System.currentTimeMillis()
        model.grade(Grade.GOOD, expectAnswered = 1)

        val scheduled = theOnlyCard().dueAt - before
        assertEquals(
            "the button promised ${promised}ms and the answer scheduled ${scheduled}ms",
            promised.toDouble(),
            scheduled.toDouble(),
            // Seconds of slack: the preview and the answer read the clock a moment apart.
            5_000.0
        )
    }

    // The leech suspension is driven from the repository, and tested there
    // (AbhyasRepositoryTest.aLeechIsSuspendedRatherThanLeftInRotation). It cannot sensibly be driven
    // through a sitting: lapses only accrue on a graduated card, and graduating the card ends the
    // sitting it would have to be graded in - so a ViewModel-level version of it would have to
    // rebuild the session on every answer and would be testing the repository anyway.

    @Test
    fun anEmptyDeckFinishesImmediatelyRatherThanHanging() = runBlocking {
        val state = studying(deckOf(0)).awaitLoaded()

        assertTrue("it must not sit on a spinner", state.finished)
        assertEquals(0, state.remaining)
    }

    @Test
    fun newCardsAreCappedSoABigImportCannotBuryTheReviews() = runBlocking {
        // Someone who has just photographed four chapters has hundreds of unseen cards, and serving
        // them all would bury the handful of reviews keeping their existing knowledge alive.
        val state = studying(deckOf(50)).awaitLoaded()

        assertEquals(AbhyasRepository.DEFAULT_NEW_PER_SESSION, state.remaining)
    }
}

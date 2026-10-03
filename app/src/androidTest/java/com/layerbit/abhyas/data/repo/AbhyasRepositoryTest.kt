package com.layerbit.abhyas.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.layerbit.abhyas.data.backup.Backup
import com.layerbit.abhyas.data.db.AbhyasDatabase
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.generate.CardKind
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repository against a real database, because the things worth testing here are the ones a fake
 * cannot have: transactions, cascades, and the SQL in the DAOs.
 *
 * Everything in this file is a write that spans more than one row. Those were the operations that
 * had no transaction around them and no test under them - the combination that lets a half-written
 * collection sit there looking fine.
 */
@RunWith(AndroidJUnit4::class)
class AbhyasRepositoryTest {

    private lateinit var db: AbhyasDatabase
    private lateinit var repository: AbhyasRepository

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // In-memory, so each test starts from nothing and nothing survives to the next.
        db = Room.inMemoryDatabaseBuilder(context, AbhyasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AbhyasRepository(db)
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun deckWithCards(name: String, count: Int): Long {
        val deckId = repository.createDeck(name)
        repository.addCards(
            deckId,
            (1..count).map {
                CardCandidate(
                    front = "$name question $it",
                    back = "$name answer $it",
                    sourceText = "$name source $it",
                    kind = CardKind.DEFINITION,
                    confidence = 1f
                )
            }
        )
        return deckId
    }

    // ------------------------------------------------------------------------------- answer, undo

    @Test
    fun answeringACardAdvancesItAndLogsExactlyOneReview() = runTest {
        val deckId = deckWithCards("Biology", 1)
        val card = repository.buildQueue(deckId).single()

        val review = repository.answer(card, Grade.GOOD)

        assertEquals("the card must leave NEW", CardState.LEARNING, review.after.state)
        assertEquals("one answer is one review", 1, repository.totalReviews().first())
        assertTrue("the card must be scheduled forwards", review.after.dueAt > card.dueAt)
    }

    @Test
    fun undoRestoresTheCardAndRemovesTheReviewFromTheRecord() = runTest {
        // Both halves matter. Restoring the card undoes the scheduling; deleting the log row is what
        // keeps a review the user explicitly took back out of their streak and their retention rate.
        val deckId = deckWithCards("Biology", 1)
        val card = repository.buildQueue(deckId).single()

        val review = repository.answer(card, Grade.EASY)
        repository.undo(review)

        assertEquals("a review taken back must not be counted", 0, repository.totalReviews().first())

        val restored = repository.cardsInDeck(deckId).first().single()
        assertEquals("the card returns to exactly what it was", card.state, restored.state)
        assertEquals(card.dueAt, restored.dueAt)
        assertEquals(card.stability, restored.stability, 0.0)
        assertEquals(card.difficulty, restored.difficulty, 0.0)
        assertEquals(card.intervalDays, restored.intervalDays)
    }

    @Test
    fun aLeechIsSuspendedRatherThanLeftInRotation() = runTest {
        val deckId = deckWithCards("Biology", 1)
        var card = repository.buildQueue(deckId).single()

        // Forget it until it crosses the threshold. Again on a REVIEW card is what counts a lapse.
        repeat(20) {
            card = repository.answer(card, Grade.AGAIN).after
            card = repository.answer(card, Grade.EASY).after
        }

        val finished = repository.cardsInDeck(deckId).first().single()
        assertTrue("a card forgotten this often must be set aside", finished.suspended)
        assertEquals("and not left waiting", 0, repository.totalWaitingNow())
    }

    // --------------------------------------------------------------------------------------- merge

    @Test
    fun mergingMovesTheCardsAndTheirHistoryAndLeavesNoGhostDeck() = runTest {
        val source = deckWithCards("Chapter 1", 3)
        val destination = deckWithCards("Biology", 2)

        // Give the source some history, so the log repointing is actually exercised.
        val card = repository.buildQueue(source).first()
        repository.answer(card, Grade.GOOD)

        repository.mergeDecks(source, destination)

        assertEquals("every card moves", 5, repository.cardsInDeck(destination).first().size)
        assertEquals("the emptied deck is gone, not left behind", 0, repository.cardsInDeck(source).first().size)
        assertNull(
            "the source deck must not survive as an empty shell",
            repository.deckSummaries().first().firstOrNull { it.id == source }
        )
        assertNotNull(repository.deckSummaries().first().firstOrNull { it.id == destination })
        assertEquals("history follows the cards", 1, repository.totalReviews().first())
    }

    @Test
    fun aDeckCannotBeMergedIntoItself() = runTest {
        val deckId = deckWithCards("Biology", 2)

        repository.mergeDecks(deckId, deckId)

        assertNotNull(
            "merging a deck into itself must not delete it",
            repository.deckSummaries().first().firstOrNull { it.id == deckId }
        )
        assertEquals(2, repository.cardsInDeck(deckId).first().size)
    }

    // -------------------------------------------------------------------------------------- backup

    @Test
    fun restoringAddsAlongsideWhatIsAlreadyThereRatherThanOverIt() = runTest {
        // The whole safety position of the backup feature: restoring the wrong file costs the user a
        // few decks to delete, not everything they had.
        val existing = deckWithCards("Keep me", 2)

        val backup = Backup(
            decks = listOf(DeckEntity(id = 7, name = "Restored", createdAt = 1, lastUsedAt = 1)),
            cards = listOf(card(deckId = 7, front = "Q1"), card(deckId = 7, front = "Q2"))
        )

        val (decks, cards) = repository.restoreBackup(backup)

        assertEquals(1, decks)
        assertEquals(2, cards)
        assertEquals("the existing deck is untouched", 2, repository.cardsInDeck(existing).first().size)
        assertEquals("both decks are present", 2, repository.deckSummaries().first().size)
    }

    @Test
    fun restoredCardsFollowTheirDeckRatherThanItsIdInTheFile() = runTest {
        // Deck ids are remapped on insert, so a backup whose deck id collides with one already in the
        // collection must not have its cards land in the wrong deck.
        val existing = repository.createDeck("Already here")

        val backup = Backup(
            decks = listOf(DeckEntity(id = existing, name = "Restored", createdAt = 1, lastUsedAt = 1)),
            cards = listOf(card(deckId = existing, front = "Restored question"))
        )

        repository.restoreBackup(backup)

        assertEquals(
            "a colliding deck id must not drop the card into the existing deck",
            0,
            repository.cardsInDeck(existing).first().size
        )
        val restoredDeck = repository.deckSummaries().first().single { it.name == "Restored" }
        assertEquals(1, repository.cardsInDeck(restoredDeck.id).first().size)
    }

    @Test
    fun aCardWhoseDeckIsMissingFromTheFileIsStillRestored() = runTest {
        // A hand-edited file, or one written by a version that omitted deck ids, used to lose every
        // card in silence. This file is the only thing between the user and a lost phone, so restoring
        // nothing without saying so is the one outcome it must not have.
        val backup = Backup(
            decks = listOf(DeckEntity(id = 1, name = "Restored", createdAt = 1, lastUsedAt = 1)),
            cards = listOf(card(deckId = 999, front = "Orphan question"))
        )

        val (_, cards) = repository.restoreBackup(backup)

        assertEquals("the orphan must not be dropped", 1, cards)
        val deck = repository.deckSummaries().first().single()
        assertEquals(1, repository.cardsInDeck(deck.id).first().size)
    }

    @Test
    fun twoDecksSharingAnIdDoNotDuplicateTheirCards() = runTest {
        val backup = Backup(
            decks = listOf(
                DeckEntity(id = 5, name = "First", createdAt = 1, lastUsedAt = 1),
                DeckEntity(id = 5, name = "Second", createdAt = 1, lastUsedAt = 1)
            ),
            cards = listOf(card(deckId = 5, front = "Shared question"))
        )

        val (_, cards) = repository.restoreBackup(backup)

        assertEquals("one card in the file is one card in the collection", 1, cards)
    }

    @Test
    fun aBackupRoundTripsThroughTheRepository() = runTest {
        val deckId = deckWithCards("Biology", 3)
        val card = repository.buildQueue(deckId).first()
        val answered = repository.answer(card, Grade.GOOD).after

        val exported = repository.exportBackup()
        assertEquals(1, exported.decks.size)
        assertEquals(3, exported.cards.size)

        val carried = exported.cards.single { it.front == answered.front }
        assertEquals("scheduling must travel with the cards", answered.stability, carried.stability, 0.0)
        assertEquals(answered.difficulty, carried.difficulty, 0.0)
        assertEquals(answered.dueAt, carried.dueAt)
        assertEquals(answered.lastReviewedAt, carried.lastReviewedAt)
    }

    // --------------------------------------------------------------------------------------- decks

    @Test
    fun deletingADeckTakesItsCardsWithIt() = runTest {
        val deckId = deckWithCards("Biology", 3)
        val other = deckWithCards("Chemistry", 2)

        repository.deleteDeck(deckId)

        assertEquals(0, repository.cardsInDeck(deckId).first().size)
        assertEquals("other decks are untouched", 2, repository.cardsInDeck(other).first().size)
    }

    @Test
    fun theMergeTargetListNeverOffersTheDeckItself() = runTest {
        val deckId = deckWithCards("Biology", 1)
        deckWithCards("Chemistry", 4)

        val targets = repository.mergeTargets(deckId)

        assertEquals(1, targets.size)
        assertEquals("Chemistry", targets.single().name)
        assertEquals("the count comes from the cards, not the decks", 4, targets.single().total)
        assertEquals(1, repository.otherDeckCount(deckId).first())
    }

    // ------------------------------------------------------------------------------------ forecast

    @Test
    fun theForecastCountsTheBacklogInTodayRatherThanHidingIt() = runTest {
        // The bug this pins: the query excluded anything already due, so the one group of users who
        // most need a forecast - anyone carrying a backlog, which is the normal case - saw it missing.
        val deckId = repository.createDeck("Biology")
        db.cardDao().insertAll(
            listOf(
                dueCard(deckId, "a week overdue", daysFromNow = -7),
                dueCard(deckId, "yesterday", daysFromNow = -1),
                dueCard(deckId, "later today", daysFromNow = 0)
            )
        )

        val today = repository.forecast(days = 14).first().single { it.dayOffset == 0 }

        assertEquals("everything overdue belongs in today's bar", 3, today.count)
    }

    @Test
    fun theForecastBucketsByCalendarDayFromMidnight() = runTest {
        val deckId = repository.createDeck("Biology")
        db.cardDao().insertAll(
            listOf(
                dueCard(deckId, "tomorrow", daysFromNow = 1),
                dueCard(deckId, "also tomorrow", daysFromNow = 1),
                dueCard(deckId, "in three days", daysFromNow = 3)
            )
        )

        val forecast = repository.forecast(days = 14).first().associate { it.dayOffset to it.count }

        assertEquals(2, forecast[1])
        assertEquals(1, forecast[3])
        assertNull("nothing falls on a day with no cards", forecast[2])
    }

    @Test
    fun theForecastIgnoresSuspendedAndUnseenCards() = runTest {
        // A suspended card is not coming back, and a NEW card has no due date to forecast - counting
        // either would overstate the work and make the chart useless for planning.
        val deckId = repository.createDeck("Biology")
        db.cardDao().insertAll(
            listOf(
                dueCard(deckId, "suspended", daysFromNow = 1).copy(suspended = true),
                dueCard(deckId, "unseen", daysFromNow = 1).copy(state = CardState.NEW),
                dueCard(deckId, "real", daysFromNow = 1)
            )
        )

        val forecast = repository.forecast(days = 14).first()

        assertEquals(1, forecast.sumOf { it.count })
    }

    /** A card due [daysFromNow] days from local midnight - negative for an overdue one. */
    private fun dueCard(deckId: Long, front: String, daysFromNow: Int): CardEntity {
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        return CardEntity(
            deckId = deckId,
            front = front,
            back = "An answer.",
            sourceText = null,
            state = CardState.REVIEW,
            // Mid-morning on the target day, so the test does not depend on the hour it runs at.
            dueAt = midnight + daysFromNow * 86_400_000L + 10 * 3_600_000L,
            intervalDays = 5,
            stability = 5.0,
            difficulty = 5.0,
            createdAt = 1000
        )
    }

    private fun card(deckId: Long, front: String) = CardEntity(
        deckId = deckId,
        front = front,
        back = "An answer long enough to be worth keeping.",
        sourceText = null,
        state = CardState.REVIEW,
        dueAt = 5 * 86_400_000L,
        intervalDays = 5,
        stability = 5.0,
        difficulty = 5.0,
        createdAt = 1000
    )
}

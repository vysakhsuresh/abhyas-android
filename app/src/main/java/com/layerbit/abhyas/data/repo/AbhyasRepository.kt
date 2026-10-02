package com.layerbit.abhyas.data.repo

import android.content.Context
import com.layerbit.abhyas.data.backup.Backup
import com.layerbit.abhyas.data.db.AbhyasDatabase
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DailyCount
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.db.ForecastDay
import com.layerbit.abhyas.data.db.Maturity
import com.layerbit.abhyas.data.db.RetentionCount
import com.layerbit.abhyas.data.db.ReviewLogEntity
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.srs.Scheduler
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Everything the UI is allowed to do to the collection. */
class AbhyasRepository(context: Context) {

    private val db = AbhyasDatabase.get(context)
    private val decks = db.deckDao()
    private val cards = db.cardDao()
    private val log = db.reviewLogDao()

    // ------------------------------------------------------------------------------------ decks

    fun deckSummaries(now: Long = System.currentTimeMillis()): Flow<List<DeckSummary>> =
        decks.summaries(now)

    fun deckSummary(deckId: Long, now: Long = System.currentTimeMillis()): Flow<DeckSummary?> =
        decks.summary(deckId, now)

    suspend fun createDeck(name: String, script: ScriptOption = ScriptOption.DEFAULT): Long {
        val now = System.currentTimeMillis()
        return decks.insert(
            DeckEntity(name = name.trim(), createdAt = now, lastUsedAt = now, script = script)
        )
    }

    /**
     * Change which recogniser this deck's pages are read with. Existing cards are untouched -
     * they were already read, and re-reading them is not possible without the original photos,
     * which Abhyas deliberately does not keep.
     */
    suspend fun setDeckScript(deckId: Long, script: ScriptOption) {
        decks.byId(deckId)?.let { decks.update(it.copy(script = script)) }
    }

    suspend fun deckScript(deckId: Long): ScriptOption =
        decks.byId(deckId)?.script ?: ScriptOption.DEFAULT

    suspend fun renameDeck(deckId: Long, name: String) {
        decks.byId(deckId)?.let { decks.update(it.copy(name = name.trim())) }
    }

    suspend fun deleteDeck(deckId: Long) {
        decks.byId(deckId)?.let { decks.delete(it) }
    }

    // ------------------------------------------------------------------------------------ cards

    fun cardsInDeck(deckId: Long): Flow<List<CardEntity>> = cards.inDeck(deckId)

    /**
     * Commit the candidates the user kept on the review screen.
     *
     * They land as NEW with `dueAt = 0`, which is what puts them at the front of the queue the
     * moment the deck is next opened - a student who has just photographed a page expects to be
     * able to study it immediately, not tomorrow.
     */
    suspend fun addCards(deckId: Long, candidates: List<CardCandidate>): Int {
        if (candidates.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val rows = candidates.map {
            CardEntity(
                deckId = deckId,
                front = it.front.trim(),
                back = it.back.trim(),
                sourceText = it.sourceText.trim().takeIf { s -> s.isNotEmpty() },
                state = CardState.NEW,
                dueAt = 0L,
                createdAt = now
            )
        }
        cards.insertAll(rows)
        decks.touch(deckId, now)
        return rows.size
    }

    suspend fun updateCard(card: CardEntity) = cards.update(card)

    suspend fun deleteCard(card: CardEntity) = cards.delete(card)

    suspend fun setSuspended(card: CardEntity, suspended: Boolean) =
        cards.update(card.copy(suspended = suspended))

    // ------------------------------------------------------------------------------- study loop

    /**
     * Build the queue for one sitting: everything genuinely due, then up to [newLimit] unseen
     * cards behind it.
     *
     * The cap on new cards is the whole reason this is not one query. Someone who has just
     * imported four chapters has hundreds of NEW rows, and serving them all would bury the
     * handful of reviews that are actually keeping their existing knowledge alive.
     */
    suspend fun buildQueue(deckId: Long, newLimit: Int = DEFAULT_NEW_PER_SESSION): List<CardEntity> {
        val now = System.currentTimeMillis()
        return cards.dueNow(deckId, now) + cards.newCards(deckId, newLimit)
    }

    /**
     * Record an answer: advance the card's scheduling and append to the log.
     *
     * Returns everything needed to show the result *and* to take it back. Mis-tapping Easy when
     * you meant Again is the single most common mistake in any review app, and without undo it
     * silently costs the user months of correct scheduling on that card - a mistake they cannot
     * see and would not know how to repair.
     */
    suspend fun answer(card: CardEntity, grade: Grade): AnsweredReview {
        val now = System.currentTimeMillis()
        val before = card.scheduling()
        val after = Scheduler.next(before, grade, now)

        var updated = card.withScheduling(after)

        // Crossing the leech threshold suspends the card. Left in the queue it would come back
        // every few days forever, soaking up review time and teaching the user that the app
        // wastes it - the card needs rewriting, not repeating.
        val becameLeech = updated.isLeech && !card.isLeech
        if (becameLeech) updated = updated.copy(suspended = true)

        cards.update(updated)
        val logId = log.insert(
            ReviewLogEntity(
                cardId = card.id,
                deckId = card.deckId,
                reviewedAt = now,
                grade = grade,
                intervalBefore = before.intervalDays,
                intervalAfter = after.intervalDays
            )
        )
        decks.touch(card.deckId, now)
        return AnsweredReview(before = card, after = updated, logId = logId, becameLeech = becameLeech)
    }

    /**
     * Take back the last answer.
     *
     * Both halves matter. Restoring the card undoes the scheduling damage; deleting the log row
     * undoes the rest, because a review the user explicitly took back must not keep counting
     * towards their streak or their retention figure.
     */
    suspend fun undo(review: AnsweredReview) {
        cards.update(review.before)
        log.deleteById(review.logId)
    }

    /** When the soonest card in this deck comes back, or null if nothing is scheduled. */
    suspend fun nextDueAt(deckId: Long): Long? = cards.nextDueAt(deckId)

    // ------------------------------------------------------------------------------------ stats

    fun reviewsSince(since: Long): Flow<Int> = log.countSince(since)

    fun dailyCounts(since: Long): Flow<List<DailyCount>> = log.dailyCounts(since)

    fun totalReviews(): Flow<Int> = log.totalReviews()

    // --------------------------------------------------------------------- search and insight

    /** Every card matching [term], across every deck. Blank returns nothing rather than all. */
    fun search(term: String): Flow<List<CardEntity>> =
        if (term.isBlank()) flowOf(emptyList()) else cards.search(term.trim())

    fun leeches(): Flow<List<CardEntity>> = cards.leeches(Scheduler.LEECH_THRESHOLD)

    fun maturity(): Flow<Maturity> = cards.maturity()

    fun forecast(days: Int = 14): Flow<List<ForecastDay>> =
        cards.forecast(System.currentTimeMillis(), days)

    fun retention(since: Long): Flow<RetentionCount> = log.retention(since)

    // ------------------------------------------------------------------------------ reminders

    /** Cards waiting across every deck, due plus unseen. What the reminder counts. */
    suspend fun totalWaitingNow(): Int = cards.totalWaiting(System.currentTimeMillis())

    /**
     * Answers given since local midnight.
     *
     * The reminder uses this to stay quiet when the day's work is already done. Local midnight
     * rather than "24 hours ago", because someone who studied at 11pm last night has not studied
     * today and should still be reminded.
     */
    suspend fun reviewedToday(): Int = log.countSinceOnce(startOfToday())

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ----------------------------------------------------------------------------- deck admin

    /**
     * Fold [source] into [destination] and delete the empty shell.
     *
     * Cards keep their scheduling, and their review history is repointed too - a merge is a
     * filing decision, not a reason to lose what the user has learned. Merging a deck into
     * itself is refused rather than silently deleting it.
     */
    suspend fun mergeDecks(source: Long, destination: Long) {
        if (source == destination) return
        cards.moveAll(source, destination)
        log.moveAll(source, destination)
        decks.byId(source)?.let { decks.delete(it) }
        decks.touch(destination, System.currentTimeMillis())
    }

    // -------------------------------------------------------------------------------- backup

    suspend fun exportBackup(): Backup = Backup(decks = decks.all(), cards = cards.allCards())

    /**
     * Restore a backup **alongside** whatever is already here, never over it.
     *
     * Every deck is inserted fresh and its cards repointed at the new id, so restoring into a
     * collection that already has decks cannot collide with them - and, more importantly,
     * restoring the wrong file cannot destroy the right collection. Undoing an unwanted restore
     * is deleting some decks; undoing a wipe would be impossible.
     *
     * Returns how many decks and cards actually landed.
     */
    suspend fun restoreBackup(backup: Backup): Pair<Int, Int> {
        var restoredCards = 0
        val now = System.currentTimeMillis()

        backup.decks.forEach { deck ->
            val newId = decks.insert(deck.copy(id = 0, lastUsedAt = now))
            val forDeck = backup.cards
                .filter { it.deckId == deck.id }
                .map { it.copy(id = 0, deckId = newId) }
            if (forDeck.isNotEmpty()) {
                cards.insertAll(forDeck)
                restoredCards += forDeck.size
            }
        }
        return backup.decks.size to restoredCards
    }

    companion object {
        /**
         * New cards introduced per sitting. Twenty is Anki's long-standing default and it holds
         * up: each one will be seen several times today and then on a growing schedule for
         * months, so the real cost of a new card is much larger than it looks on the day.
         */
        const val DEFAULT_NEW_PER_SESSION = 20
    }
}

/**
 * One answered review, and everything needed to take it back.
 *
 * [before] is the card exactly as it was, so undo is a restore rather than a reconstruction -
 * there is no attempt to run the scheduler backwards, which for FSRS would not be possible.
 */
data class AnsweredReview(
    val before: CardEntity,
    val after: CardEntity,
    val logId: Long,
    /** True when this answer is what pushed the card over the leech threshold. */
    val becameLeech: Boolean
)

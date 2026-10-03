package com.layerbit.abhyas.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.layerbit.abhyas.data.backup.Backup
import com.layerbit.abhyas.data.db.AbhyasDatabase
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DailyCount
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.db.ForecastDay
import com.layerbit.abhyas.data.db.Maturity
import com.layerbit.abhyas.data.db.MergeTarget
import com.layerbit.abhyas.data.db.RetentionCount
import com.layerbit.abhyas.data.db.ReviewLogEntity
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.srs.Scheduler
import java.util.Calendar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Everything the UI is allowed to do to the collection. */
class AbhyasRepository(context: Context) {

    private val db = AbhyasDatabase.get(context)
    private val decks = db.deckDao()
    private val cards = db.cardDao()
    private val log = db.reviewLogDao()

    // ------------------------------------------------------------------------------------ decks

    fun deckSummaries(): Flow<List<DeckSummary>> = atSubscription { decks.summaries(it) }

    fun deckSummary(deckId: Long): Flow<DeckSummary?> =
        atSubscription { decks.summary(deckId, it) }

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

    /** Whether this deck has anywhere to merge into. Cheap enough to keep observed. */
    fun otherDeckCount(deckId: Long): Flow<Int> = decks.countOthers(deckId)

    suspend fun mergeTargets(deckId: Long): List<MergeTarget> = decks.mergeTargets(deckId)

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

        // One transaction, and uncancellable. Three separate writes meant a process killed between
        // them - or a user leaving the screen, which cancels the scope mid-answer - could advance
        // the card without logging the review. The card would then be scheduled months out while
        // the streak, the retention figure and undo all behaved as though the review never
        // happened, and nothing in the app would ever notice the discrepancy.
        return atomically {
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
            AnsweredReview(before = card, after = updated, logId = logId, becameLeech = becameLeech)
        }
    }

    /**
     * Take back the last answer.
     *
     * Both halves matter. Restoring the card undoes the scheduling damage; deleting the log row
     * undoes the rest, because a review the user explicitly took back must not keep counting
     * towards their streak or their retention figure.
     */
    suspend fun undo(review: AnsweredReview) = atomically {
        cards.update(review.before)
        log.deleteById(review.logId)
    }

    /** When the soonest card in this deck comes back, or null if nothing is scheduled. */
    suspend fun nextDueAt(deckId: Long): Long? = cards.nextDueAt(deckId)

    // ------------------------------------------------------------------------------------ stats

    fun reviewsInLast(days: Int): Flow<Int> = atSubscription { log.countSince(it - inDays(days)) }

    fun dailyCountsInLast(days: Int): Flow<List<DailyCount>> =
        atSubscription { log.dailyCounts(it - inDays(days)) }

    fun totalReviews(): Flow<Int> = log.totalReviews()

    // --------------------------------------------------------------------- search and insight

    /** Every card matching [term], across every deck. Blank returns nothing rather than all. */
    fun search(term: String): Flow<List<CardEntity>> =
        if (term.isBlank()) flowOf(emptyList()) else cards.search(term.trim())

    fun leeches(): Flow<List<CardEntity>> = cards.leeches(Scheduler.LEECH_THRESHOLD)

    fun maturity(): Flow<Maturity> = cards.maturity()

    fun forecast(days: Int = 14): Flow<List<ForecastDay>> =
        atSubscription { cards.forecast(it, days) }

    fun retentionInLast(days: Int): Flow<RetentionCount> =
        atSubscription { log.retention(it - inDays(days)) }

    /**
     * A query whose SQL embeds the current time, re-read every time the flow is *subscribed*.
     *
     * The timestamp used to come from a default argument, which is evaluated once when the ViewModel
     * builds the flow - and Room only re-runs a query when its tables change, never on a clock
     * boundary. So the "due" counts on the decks list were pinned to the instant the ViewModel was
     * constructed, which for the start destination is once per process: leave Abhyas open overnight
     * and it would still be showing yesterday evening's numbers, with a comment claiming they
     * refreshed on re-entry. Wrapping the call in a flow builder moves the clock read into the
     * subscription, so `WhileSubscribed` dropping and restoring it on screen exit and re-entry
     * re-evaluates the counts - which is what that comment always promised.
     *
     * It also keeps the property the comment was protecting: within one sitting the subscription is
     * continuous, so the list cannot re-sort under a finger reaching for a deck.
     */
    private fun <T> atSubscription(query: (Long) -> Flow<T>): Flow<T> =
        flow { emitAll(query(System.currentTimeMillis())) }

    private fun inDays(days: Int): Long = days * 86_400_000L

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
        // Four writes that are only correct together. Interrupted after the cards moved but before
        // the source was deleted, the user is left with an empty ghost deck they did not ask for;
        // interrupted the other way round, the cards go with it.
        atomically {
            cards.moveAll(source, destination)
            log.moveAll(source, destination)
            decks.byId(source)?.let { decks.delete(it) }
            decks.touch(destination, System.currentTimeMillis())
        }
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
    suspend fun restoreBackup(backup: Backup): Pair<Int, Int> = atomically {
        val now = System.currentTimeMillis()

        // Insert every deck first, remembering which new id each *file* id became. putIfAbsent, so
        // a hand-edited file where two decks share an id sends that id's cards to one of them
        // rather than duplicating every card into both.
        val newIdFor = mutableMapOf<Long, Long>()
        backup.decks.forEach { deck ->
            val newId = decks.insert(deck.copy(id = 0, lastUsedAt = now))
            newIdFor.putIfAbsent(deck.id, newId)
        }

        // Cards whose deckId matches no deck in the file go to the first deck restored rather than
        // being dropped. A file written by this app always matches, but one that was hand-edited,
        // merged by someone, or written by a version that omitted deck ids would otherwise lose
        // every card in silence - and this file is the only thing standing between the user and a
        // lost phone, so quietly restoring nothing is the one outcome it must not have.
        val fallback = newIdFor.values.firstOrNull() ?: return@atomically 0 to 0

        var restoredCards = 0
        backup.cards.groupBy { it.deckId }.forEach { (fileDeckId, group) ->
            val target = newIdFor[fileDeckId] ?: fallback
            cards.insertAll(group.map { it.copy(id = 0, deckId = target) })
            restoredCards += group.size
        }

        backup.decks.size to restoredCards
    }

    /**
     * Run [block] as one all-or-nothing database transaction that a cancelled caller cannot cut in
     * half.
     *
     * Both halves earn their place. The transaction is what makes a multi-row write atomic, so a
     * process death part-way through rolls back rather than leaving a state no code expects.
     * [NonCancellable] is for the much more ordinary case: every caller here runs in a
     * `viewModelScope`, and that scope is cancelled the instant the user navigates away - so
     * tapping Restore and immediately pressing Back would otherwise abandon the write. Without the
     * transaction that left half a collection; with the transaction alone it would roll the whole
     * restore back and report success. Shielding it means the work the user asked for finishes.
     */
    private suspend fun <T> atomically(block: suspend () -> T): T =
        withContext(NonCancellable) { db.withTransaction { block() } }

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

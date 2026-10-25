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
import com.layerbit.abhyas.data.db.LoggedAnswer
import com.layerbit.abhyas.data.db.Maturity
import com.layerbit.abhyas.data.db.MergeTarget
import com.layerbit.abhyas.data.db.ParentOption
import com.layerbit.abhyas.data.db.RetentionCount
import com.layerbit.abhyas.data.db.ReviewLogEntity
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.CardType
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.model.Tags
import com.layerbit.abhyas.data.occlusion.ImageStore
import com.layerbit.abhyas.data.occlusion.Occlusion
import com.layerbit.abhyas.data.occlusion.Region
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.srs.Fsrs
import com.layerbit.abhyas.data.srs.Scheduler
import java.util.Calendar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A tag in use, and how many cards carry it. */
data class TagCount(val tag: String, val count: Int)

/**
 * Everything the UI is allowed to do to the collection.
 *
 * Takes the database rather than reaching for the singleton, so a test can hand it an in-memory one.
 * The transactional writes below are the part of this app most worth testing and the part a fake
 * repository cannot test at all - a fake has no transactions to roll back and no cascades to honour.
 *
 * [appContext] is only for the image store behind picture cards, and is optional so those tests can
 * keep constructing this from a database alone. [weights] is the FSRS parameter set in force - the
 * defaults, or the user's fitted set - read on every answer so a re-fit applies to the next card.
 */
class AbhyasRepository(
    private val db: AbhyasDatabase,
    private val appContext: Context? = null,
    private val weights: () -> DoubleArray = { Fsrs.WEIGHTS }
) {

    constructor(context: Context, weights: () -> DoubleArray = { Fsrs.WEIGHTS }) :
        this(AbhyasDatabase.get(context), context.applicationContext, weights)

    private val decks = db.deckDao()
    private val cards = db.cardDao()
    private val log = db.reviewLogDao()

    /** The weights in force, for anything outside the repository that previews a schedule. */
    fun currentWeights(): DoubleArray = weights()

    // ------------------------------------------------------------------------------------ decks

    fun deckSummaries(): Flow<List<DeckSummary>> = atSubscription { decks.summaries(it) }

    fun deckSummary(deckId: Long): Flow<DeckSummary?> =
        atSubscription { decks.summary(deckId, it) }

    fun subdecks(deckId: Long): Flow<List<DeckSummary>> =
        atSubscription { decks.children(deckId, it) }

    suspend fun createDeck(
        name: String,
        script: ScriptOption = ScriptOption.DEFAULT,
        parentId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        // A parent that is itself a subdeck would make the tree two deep, which every count on the
        // home screen assumes it never is. Filed at the top level instead.
        val parent = parentId?.let { decks.byId(it) }?.takeIf { it.parentId == null }
        return decks.insert(
            DeckEntity(
                name = name.trim(),
                createdAt = now,
                lastUsedAt = now,
                script = parent?.script ?: script,
                parentId = parent?.id
            )
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

    suspend fun parentOptions(deckId: Long): List<ParentOption> = decks.parentOptions(deckId)

    /**
     * File [deckId] under [parentId], or back at the top level for null.
     *
     * Refused rather than half-done when it would break the one-level rule: the parent must be a
     * top-level deck other than this one, and a deck that already holds subdecks cannot itself
     * become one. [parentOptions] only ever offers decks that pass, so this is the backstop.
     */
    suspend fun setParent(deckId: Long, parentId: Long?): Boolean = atomically {
        if (parentId == null) {
            decks.setParent(deckId, null)
            return@atomically true
        }
        val parent = decks.byId(parentId)
        val ok = parentId != deckId && parent != null && parent.parentId == null &&
            decks.selfAndChildren(deckId).size == 1
        if (ok) decks.setParent(deckId, parentId)
        ok
    }

    suspend fun deckScript(deckId: Long): ScriptOption =
        decks.byId(deckId)?.script ?: ScriptOption.DEFAULT

    suspend fun renameDeck(deckId: Long, name: String) {
        decks.byId(deckId)?.let { decks.update(it.copy(name = name.trim())) }
    }

    /**
     * Delete a deck and its cards. Its subdecks are kept and lifted to the top level: deleting
     * "Biology" because the subject is finished must not silently take "Chapter 4" with it.
     */
    suspend fun deleteDeck(deckId: Long) {
        atomically {
            decks.releaseChildren(deckId)
            decks.byId(deckId)?.let { decks.delete(it) }
        }
        sweepImages()
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
    suspend fun addCards(deckId: Long, candidates: List<CardCandidate>, tags: String = ""): Int {
        if (candidates.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val stored = Tags.normalise(tags)
        val rows = candidates.map {
            CardEntity(
                deckId = deckId,
                front = it.front.trim(),
                back = it.back.trim(),
                sourceText = it.sourceText.trim().takeIf { s -> s.isNotEmpty() },
                state = CardState.NEW,
                dueAt = 0L,
                createdAt = now,
                tags = stored
            )
        }
        cards.insertAll(rows)
        decks.touch(deckId, now)
        return rows.size
    }

    /**
     * One card per masked region of a stored picture.
     *
     * The front is the user's title for the picture - "Heart, anterior view" - and the back is what
     * the region hides, or "Label 3" when they did not type one. The study screen draws the picture
     * rather than these words, but they are what search, the deck list and a screen reader see.
     */
    suspend fun addOcclusionCards(
        deckId: Long,
        imageName: String,
        title: String,
        regions: List<Region>,
        tags: String,
        fallbackLabel: (Int) -> String
    ): Int {
        if (regions.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val stored = Tags.normalise(tags)
        val rows = regions.indices.map { index ->
            CardEntity(
                deckId = deckId,
                front = title.trim(),
                back = regions[index].label.trim().ifEmpty { fallbackLabel(index + 1) },
                sourceText = null,
                state = CardState.NEW,
                dueAt = 0L,
                // createdAt nudged per card so the queue's createdAt order keeps the regions in
                // the order they were drawn, which is usually reading order round the diagram.
                createdAt = now + index,
                type = CardType.OCCLUSION,
                tags = stored,
                imagePath = imageName,
                occlusion = Occlusion(regions, index).encode()
            )
        }
        cards.insertAll(rows)
        decks.touch(deckId, now)
        return rows.size
    }

    suspend fun updateCard(card: CardEntity) = cards.update(card)

    /**
     * The questions already in this deck, normalised for comparison.
     *
     * Photographing the same page twice is ordinary - a student re-shoots a blurred page, or
     * forgets they did that chapter last week - and without this it produces a second copy of
     * every card. Duplicates are worse than missing cards: the scheduler treats them as separate
     * memories, so the user answers the same question twice forever.
     *
     * Normalised rather than exact, because OCR will not read a page identically twice. Case and
     * punctuation go; the words have to match.
     */
    suspend fun existingQuestions(deckId: Long): Set<String> =
        cards.frontsInDeck(deckId).mapTo(mutableSetOf()) { normaliseQuestion(it) }

    companion object {
        /**
         * New cards introduced per sitting. Twenty is Anki's long-standing default and it holds
         * up: each one will be seen several times today and then on a growing schedule for
         * months, so the real cost of a new card is much larger than it looks on the day.
         */
        const val DEFAULT_NEW_PER_SESSION = 20

        /** The most a cram session pulls in at once. Past this it is not a session, it is a day. */
        const val CRAM_LIMIT = 300

        private val NON_COMPARABLE = Regex("""[^\p{L}\p{N} ]""")
        private val REPEATED_SPACE = Regex("""\s+""")

        /** Lower-cased, stripped of punctuation, single-spaced. */
        fun normaliseQuestion(text: String): String =
            text.lowercase()
                .replace(NON_COMPARABLE, " ")
                .replace(REPEATED_SPACE, " ")
                .trim()

        /**
         * Keep the first card of each sibling group and drop the rest, preserving order.
         *
         * Pure, so the rule is testable without a database. A card with no sibling key - written by
         * hand - is never dropped.
         */
        fun withoutSiblings(queue: List<CardEntity>): List<CardEntity> {
            val seen = HashSet<String>()
            return queue.filter { card -> card.siblingKey?.let { seen.add(it) } ?: true }
        }
    }

    suspend fun deleteCard(card: CardEntity) {
        cards.delete(card)
        if (card.imagePath != null) sweepImages()
    }

    suspend fun setSuspended(card: CardEntity, suspended: Boolean) =
        cards.update(card.copy(suspended = suspended))

    // ------------------------------------------------------------------------------- study loop

    /**
     * Build the queue for one sitting: everything genuinely due, then up to [newLimit] unseen
     * cards behind it, across the deck and its subdecks.
     *
     * The cap on new cards is the whole reason this is not one query. Someone who has just
     * imported four chapters has hundreds of NEW rows, and serving them all would bury the
     * handful of reviews that are actually keeping their existing knowledge alive.
     *
     * Siblings are thinned here as well as buried on answer. Two cards made from one sentence can
     * both be due today without either having been answered yet - a list page produces exactly
     * that - and the second one's answer is in the first one's question. The first in queue order
     * is kept; the rest come back on a later day.
     */
    suspend fun buildQueue(deckId: Long, newLimit: Int = DEFAULT_NEW_PER_SESSION): List<CardEntity> {
        val now = System.currentTimeMillis()
        val ids = decks.selfAndChildren(deckId).ifEmpty { listOf(deckId) }
        return withoutSiblings(cards.dueNow(ids, now) + cards.newCards(ids, now, newLimit))
    }

    /** Cards for a cram session: everything the filter matches, due or not. */
    suspend fun cramQueue(term: String, tag: String): List<CardEntity> =
        cards.cram(term.trim(), Tags.single(tag).orEmpty(), CRAM_LIMIT)

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
        val after = Scheduler.next(before, grade, now, weights = weights())

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

            // Its siblings wait for tomorrow. Inside the transaction, so undo can rely on exactly
            // these rows having been buried by exactly this answer.
            val siblings = if (card.siblingKey == null) {
                emptyList()
            } else {
                cards.siblingsOf(card.deckId, card.id, card.sourceText, card.imagePath)
            }
            if (siblings.isNotEmpty()) cards.bury(siblings, startOfTomorrow())

            AnsweredReview(
                before = card,
                after = updated,
                logId = logId,
                becameLeech = becameLeech,
                buried = siblings
            )
        }
    }

    /**
     * Take back the last answer.
     *
     * Both halves matter. Restoring the card undoes the scheduling damage; deleting the log row
     * undoes the rest, because a review the user explicitly took back must not keep counting
     * towards their streak or their retention figure. The siblings it buried are released too.
     */
    suspend fun undo(review: AnsweredReview) = atomically {
        cards.update(review.before)
        log.deleteById(review.logId)
        if (review.buried.isNotEmpty()) cards.bury(review.buried, 0L)
    }

    /** When the soonest card in this deck comes back, or null if nothing is scheduled. */
    suspend fun nextDueAt(deckId: Long): Long? =
        cards.nextDueAt(decks.selfAndChildren(deckId).ifEmpty { listOf(deckId) })

    // ------------------------------------------------------------------------------------ stats

    fun reviewsInLast(days: Int): Flow<Int> = atSubscription { log.countSince(it - inDays(days)) }

    fun dailyCountsInLast(days: Int): Flow<List<DailyCount>> =
        atSubscription { log.dailyCounts(it - inDays(days)) }

    fun totalReviews(): Flow<Int> = log.totalReviews()

    /** Every answer in the log, for fitting the schedule to this user. */
    suspend fun allAnswers(): List<LoggedAnswer> = log.allAnswers()

    // --------------------------------------------------------------------- search and insight

    /**
     * Every card matching [term] and [tag], across every deck. Both blank returns nothing rather
     * than the whole collection; a tag alone lists that tag's cards.
     */
    fun search(term: String, tag: String = ""): Flow<List<CardEntity>> {
        val cleanTag = Tags.single(tag).orEmpty()
        return if (term.isBlank() && cleanTag.isEmpty()) {
            flow { emit(emptyList()) }
        } else {
            cards.search(term.trim(), cleanTag)
        }
    }

    /** Every tag in use, most-used first. */
    fun tags(): Flow<List<TagCount>> = cards.tagColumns().map { columns ->
        columns.flatMap { Tags.parse(it) }
            .groupingBy { it }
            .eachCount()
            .map { (tag, count) -> TagCount(tag, count) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })
    }

    fun leeches(): Flow<List<CardEntity>> = cards.leeches(Scheduler.LEECH_THRESHOLD)

    fun maturity(): Flow<Maturity> = cards.maturity()

    /**
     * The next [days] calendar days of work, counted from local midnight rather than from now.
     *
     * startOfToday is the same helper the reminder uses, reused deliberately: the chart's first bar
     * is labelled "Today", so its buckets have to be days on a calendar and not rolling 24-hour
     * windows measured from whenever the screen was opened.
     */
    fun forecast(days: Int = 14): Flow<List<ForecastDay>> =
        flow { emitAll(cards.forecast(startOfToday(), days)) }

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

    /** Local midnight tonight - when a buried sibling becomes eligible again. */
    private fun startOfTomorrow(): Long = Calendar.getInstance().apply {
        timeInMillis = startOfToday()
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    // ----------------------------------------------------------------------------- deck admin

    /**
     * Fold [source] into [destination] and delete the empty shell.
     *
     * Cards keep their scheduling, and their review history is repointed too - a merge is a
     * filing decision, not a reason to lose what the user has learned. Merging a deck into
     * itself is refused rather than silently deleting it.
     *
     * The source's subdecks are kept: they move under the destination when it is a top-level deck,
     * and to the top level otherwise, so the tree never grows a second level.
     */
    suspend fun mergeDecks(source: Long, destination: Long) {
        if (source == destination) return
        // Writes that are only correct together. Interrupted after the cards moved but before the
        // source was deleted, the user is left with an empty ghost deck they did not ask for;
        // interrupted the other way round, the cards go with it.
        atomically {
            val target = decks.byId(destination) ?: return@atomically
            cards.moveAll(source, destination)
            log.moveAll(source, destination)
            // The destination may be one of the source's own subdecks, so the children are lifted
            // to the top level first and only then filed under the destination - which by then is
            // top-level itself, and is skipped so it is never filed under itself.
            val children = decks.selfAndChildren(source).filter { it != source }
            decks.releaseChildren(source)
            val destinationIsTopLevel = target.parentId == null || target.parentId == source
            if (destinationIsTopLevel) {
                children.filter { it != destination }.forEach { decks.setParent(it, destination) }
            }
            decks.byId(source)?.let { decks.delete(it) }
            decks.touch(destination, System.currentTimeMillis())
        }
    }

    // -------------------------------------------------------------------------------- backup

    /** The collection, without picture bytes - those are streamed by name. See [imageBytes]. */
    suspend fun exportBackup(): Backup = Backup(decks = decks.all(), cards = cards.allCards())

    /** The stored bytes of one picture, for streaming into a backup. */
    fun imageBytes(name: String): ByteArray? = appContext?.let { ImageStore.read(it, name) }

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
        // Pictures first and outside the transaction: they are files, not rows, and a picture
        // written for a card that then fails to restore is swept up on the next start.
        appContext?.let { context ->
            backup.images.forEach { (name, bytes) -> ImageStore.restore(context, name, bytes) }
        }

        return atomically {
            val now = System.currentTimeMillis()

            // Insert every deck first, remembering which new id each *file* id became. putIfAbsent,
            // so a hand-edited file where two decks share an id sends that id's cards to one of them
            // rather than duplicating every card into both. Parents are linked in a second pass,
            // once every deck in the file has a new id to point at.
            val newIdFor = mutableMapOf<Long, Long>()
            backup.decks.forEach { deck ->
                val newId = decks.insert(deck.copy(id = 0, lastUsedAt = now, parentId = null))
                newIdFor.putIfAbsent(deck.id, newId)
            }
            backup.decks.forEach { deck ->
                val parent = deck.parentId?.let { newIdFor[it] } ?: return@forEach
                val child = newIdFor[deck.id] ?: return@forEach
                // Only a top-level parent, so a malformed file cannot build a two-level tree.
                val parentIsTopLevel = backup.decks.firstOrNull { it.id == deck.parentId }?.parentId == null
                if (parent != child && parentIsTopLevel) decks.setParent(child, parent)
            }

            // Cards whose deckId matches no deck in the file go to the first deck restored rather
            // than being dropped. A file written by this app always matches, but one that was
            // hand-edited, merged by someone, or written by a version that omitted deck ids would
            // otherwise lose every card in silence - and this file is the only thing standing
            // between the user and a lost phone, so quietly restoring nothing is the one outcome
            // it must not have.
            val fallback = newIdFor.values.firstOrNull() ?: return@atomically 0 to 0

            var restoredCards = 0
            backup.cards.groupBy { it.deckId }.forEach { (fileDeckId, group) ->
                val target = newIdFor[fileDeckId] ?: fallback
                cards.insertAll(group.map { it.copy(id = 0, deckId = target, buriedUntil = 0L) })
                restoredCards += group.size
            }

            backup.decks.size to restoredCards
        }
    }

    /** Delete every stored picture no card refers to. Safe to call at any time. */
    suspend fun sweepImages() {
        val context = appContext ?: return
        val referenced = cards.imagePaths().toSet()
        withContext(kotlinx.coroutines.Dispatchers.IO) { ImageStore.sweep(context, referenced) }
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
    val becameLeech: Boolean,
    /** Siblings this answer held back until tomorrow, so undo can release them. */
    val buried: List<Long> = emptyList()
)

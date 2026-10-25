package com.layerbit.abhyas.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.srs.Scheduler
import kotlinx.coroutines.flow.Flow

/** A deck plus the three counts the deck list and the study screen both need. */
data class DeckSummary(
    val id: Long,
    val name: String,
    val lastUsedAt: Long,
    val script: ScriptOption,
    /** The deck this one sits inside, or null at the top level. See [DeckEntity.parentId]. */
    val parentId: Long?,
    /** Counts include every subdeck's cards, so a subject shows the work in all its chapters. */
    val total: Int,
    val due: Int,
    val newCount: Int
)

/** A deck that could hold another as a subdeck. */
data class ParentOption(val id: Long, val name: String)

/** A deck as the merge dialog needs it: what to call it, and how much is in it. */
data class MergeTarget(
    val id: Long,
    val name: String,
    val total: Int
)

@Dao
interface DeckDao {

    @Insert
    suspend fun insert(deck: DeckEntity): Long

    @Update
    suspend fun update(deck: DeckEntity)

    @Delete
    suspend fun delete(deck: DeckEntity)

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun byId(id: Long): DeckEntity?

    @Query("SELECT * FROM decks ORDER BY lastUsedAt DESC")
    suspend fun all(): List<DeckEntity>

    @Query("UPDATE decks SET lastUsedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    /** This deck and its subdecks: what studying it, or counting it, covers. */
    @Query("SELECT id FROM decks WHERE id = :deckId OR parentId = :deckId")
    suspend fun selfAndChildren(deckId: Long): List<Long>

    /** Lift a deleted deck's subdecks to the top level rather than orphaning them. */
    @Query("UPDATE decks SET parentId = NULL WHERE parentId = :deckId")
    suspend fun releaseChildren(deckId: Long)

    @Query("UPDATE decks SET parentId = :parentId WHERE id = :deckId")
    suspend fun setParent(deckId: Long, parentId: Long?)

    /**
     * Decks this one could be filed under: top-level, not itself, and only if this deck has no
     * subdecks of its own - which is what keeps the tree one level deep.
     */
    @Query(
        """
        SELECT id, name FROM decks
         WHERE parentId IS NULL AND id != :deckId
           AND NOT EXISTS (SELECT 1 FROM decks c WHERE c.parentId = :deckId)
         ORDER BY name COLLATE NOCASE
        """
    )
    suspend fun parentOptions(deckId: Long): List<ParentOption>

    /** The subdecks of one deck, with their own counts, for the deck screen. */
    @Query(
        """
        SELECT d.id, d.name, d.lastUsedAt, d.script, d.parentId,
               (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id) AS total,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId = d.id AND c.suspended = 0 AND c.buriedUntil <= :now
                   AND c.state != 'NEW' AND c.dueAt <= :now) AS due,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId = d.id AND c.suspended = 0 AND c.buriedUntil <= :now
                   AND c.state = 'NEW') AS newCount
        FROM decks d WHERE d.parentId = :deckId
        ORDER BY d.name COLLATE NOCASE
        """
    )
    fun children(deckId: Long, now: Long): Flow<List<DeckSummary>>

    /**
     * Every deck with its counts in one query.
     *
     * The counts are computed with correlated subqueries rather than by loading each deck's cards
     * and counting in Kotlin, because this drives the home screen and has to stay cheap as a
     * collection grows into the thousands. `newCount` is reported separately from `due` because a
     * pile of unseen cards and a pile of overdue ones mean very different things to a student.
     *
     * Each deck's counts cover its subdecks too. The tree is one level deep, so "this deck or a deck
     * whose parent is this one" is the whole subtree, without a recursive query. Buried cards are
     * left out, because the study queue leaves them out and the number on the button has to match.
     */
    @Query(
        """
        SELECT d.id, d.name, d.lastUsedAt, d.script, d.parentId,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId IN (SELECT s.id FROM decks s WHERE s.id = d.id OR s.parentId = d.id)
               ) AS total,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId IN (SELECT s.id FROM decks s WHERE s.id = d.id OR s.parentId = d.id)
                   AND c.suspended = 0 AND c.buriedUntil <= :now
                   AND c.state != 'NEW' AND c.dueAt <= :now) AS due,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId IN (SELECT s.id FROM decks s WHERE s.id = d.id OR s.parentId = d.id)
                   AND c.suspended = 0 AND c.buriedUntil <= :now
                   AND c.state = 'NEW') AS newCount
        FROM decks d
        ORDER BY d.lastUsedAt DESC
        """
    )
    fun summaries(now: Long): Flow<List<DeckSummary>>

    /**
     * How many other decks exist. Drives nothing but whether the "Merge" link is shown.
     *
     * Deliberately not `summaries().map { it.size }`. That query carries three correlated counts over
     * the whole `cards` table per deck, and observing it registers an invalidation watch on `cards` -
     * so the deck screen re-ran the entire collection's aggregate every time the user edited a single
     * card, to answer a question about `decks` alone.
     */
    @Query("SELECT COUNT(*) FROM decks WHERE id != :deckId")
    fun countOthers(deckId: Long): Flow<Int>

    /**
     * The decks this one could be folded into, loaded once when the merge dialog opens.
     *
     * One shot rather than a Flow: the list is read while a modal is on screen, so there is nothing
     * to keep live, and a snapshot cannot re-order under the finger picking from it.
     */
    @Query(
        """
        SELECT d.id, d.name,
               (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id) AS total
        FROM decks d WHERE d.id != :deckId
        ORDER BY d.lastUsedAt DESC
        """
    )
    suspend fun mergeTargets(deckId: Long): List<MergeTarget>

    @Query(
        """
        SELECT d.id, d.name, d.lastUsedAt, d.script, d.parentId,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId IN (SELECT s.id FROM decks s WHERE s.id = d.id OR s.parentId = d.id)
               ) AS total,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId IN (SELECT s.id FROM decks s WHERE s.id = d.id OR s.parentId = d.id)
                   AND c.suspended = 0 AND c.buriedUntil <= :now
                   AND c.state != 'NEW' AND c.dueAt <= :now) AS due,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId IN (SELECT s.id FROM decks s WHERE s.id = d.id OR s.parentId = d.id)
                   AND c.suspended = 0 AND c.buriedUntil <= :now
                   AND c.state = 'NEW') AS newCount
        FROM decks d WHERE d.id = :deckId
        """
    )
    fun summary(deckId: Long, now: Long): Flow<DeckSummary?>
}

@Dao
interface CardDao {

    @Insert
    suspend fun insertAll(cards: List<CardEntity>): List<Long>

    @Insert
    suspend fun insert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Delete
    suspend fun delete(card: CardEntity)

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun byId(id: Long): CardEntity?

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY createdAt DESC")
    fun inDeck(deckId: Long): Flow<List<CardEntity>>

    /**
     * The study queue.
     *
     * Cards already in flight (learning and relearning) come first so a session actually finishes
     * what it started, then genuine reviews, then unseen cards - `state = 'NEW'` sorts last
     * because introducing new material before clearing what is already half-learned is how people
     * end up with a backlog they never dig out of.
     *
     * NEW cards come from [newCards], capped, so a freshly imported 200-card chapter does not bury
     * the day's reviews.
     *
     * Takes the deck *and its subdecks*, so studying a subject studies every chapter in it. Buried
     * cards wait for tomorrow; see [CardEntity.buriedUntil].
     */
    @Query(
        """
        SELECT * FROM cards
         WHERE deckId IN (:deckIds) AND suspended = 0 AND buriedUntil <= :now
           AND (state != 'NEW' AND dueAt <= :now)
         ORDER BY CASE state WHEN 'LEARNING' THEN 0 WHEN 'RELEARNING' THEN 0 ELSE 1 END,
                  dueAt ASC
        """
    )
    suspend fun dueNow(deckIds: List<Long>, now: Long): List<CardEntity>

    @Query(
        """
        SELECT * FROM cards
         WHERE deckId IN (:deckIds) AND suspended = 0 AND buriedUntil <= :now AND state = 'NEW'
         ORDER BY createdAt ASC, id ASC
         LIMIT :newLimit
        """
    )
    suspend fun newCards(deckIds: List<Long>, now: Long, newLimit: Int): List<CardEntity>

    /**
     * The soonest a card in this deck comes back, used to tell the user *when* to return rather
     * than just that they are done. Learning cards count, which is what makes "in 9 minutes"
     * possible instead of an unhelpful "tomorrow".
     */
    @Query(
        """
        SELECT MIN(MAX(dueAt, buriedUntil)) FROM cards
         WHERE deckId IN (:deckIds) AND suspended = 0 AND state != 'NEW'
        """
    )
    suspend fun nextDueAt(deckIds: List<Long>): Long?

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND state = :state")
    suspend fun countInState(deckId: Long, state: CardState): Int

    /**
     * Search every card in the collection.
     *
     * LIKE rather than an FTS table: a phone-sized collection is thousands of cards, not
     * millions, and FTS would add a second table to keep in step with this one for no gain a
     * user could feel. It searches the source sentence too, which is often where the word the
     * user half-remembers actually appears.
     */
    @Query(
        """
        SELECT * FROM cards
         WHERE (:term = '' OR front LIKE '%' || :term || '%'
                OR back LIKE '%' || :term || '%'
                OR sourceText LIKE '%' || :term || '%')
           AND (:tag = '' OR (' ' || tags || ' ') LIKE '% ' || :tag || ' %')
         ORDER BY createdAt DESC
         LIMIT 200
        """
    )
    fun search(term: String, tag: String): Flow<List<CardEntity>>

    /**
     * Everything a filter matches, for studying ahead of an exam.
     *
     * Not limited to what is due - that is the point - and shuffled, because cramming in the
     * order the cards were made teaches the order. Suspended cards stay out: the user set them
     * aside on purpose.
     */
    @Query(
        """
        SELECT * FROM cards
         WHERE suspended = 0
           AND (:term = '' OR front LIKE '%' || :term || '%'
                OR back LIKE '%' || :term || '%'
                OR sourceText LIKE '%' || :term || '%')
           AND (:tag = '' OR (' ' || tags || ' ') LIKE '% ' || :tag || ' %')
         ORDER BY RANDOM()
         LIMIT :limit
        """
    )
    suspend fun cram(term: String, tag: String, limit: Int): List<CardEntity>

    /** Every tag column with something in it, for building the list of tags in use. */
    @Query("SELECT tags FROM cards WHERE tags != ''")
    fun tagColumns(): Flow<List<String>>

    /**
     * Siblings of an answered card that should wait until tomorrow: same deck, same source
     * sentence or picture, and not mid-learning - a card on a ten-minute step is in flight, and
     * holding it back a day would break its learning steps.
     */
    @Query(
        """
        SELECT id FROM cards
         WHERE deckId = :deckId AND id != :cardId AND suspended = 0
           AND state IN ('NEW', 'REVIEW')
           AND ((:sourceText IS NOT NULL AND sourceText = :sourceText)
                OR (:imagePath IS NOT NULL AND imagePath = :imagePath))
        """
    )
    suspend fun siblingsOf(
        deckId: Long,
        cardId: Long,
        sourceText: String?,
        imagePath: String?
    ): List<Long>

    @Query("UPDATE cards SET buriedUntil = :until WHERE id IN (:ids)")
    suspend fun bury(ids: List<Long>, until: Long)

    /** Every picture still used by some card, so the image store can drop the rest. */
    @Query("SELECT DISTINCT imagePath FROM cards WHERE imagePath IS NOT NULL")
    suspend fun imagePaths(): List<String>

    /** Cards forgotten so often they are worth rewriting. See [Scheduler.LEECH_THRESHOLD]. */
    @Query("SELECT * FROM cards WHERE lapses >= :threshold ORDER BY lapses DESC")
    fun leeches(threshold: Int): Flow<List<CardEntity>>

    /** How card memory is spread across the collection, for the statistics screen. */
    @Query(
        """
        SELECT
          SUM(CASE WHEN state = 'NEW' THEN 1 ELSE 0 END) AS unseen,
          SUM(CASE WHEN state IN ('LEARNING','RELEARNING') THEN 1 ELSE 0 END) AS learning,
          SUM(CASE WHEN state = 'REVIEW' AND intervalDays < 21 THEN 1 ELSE 0 END) AS young,
          SUM(CASE WHEN state = 'REVIEW' AND intervalDays >= 21 THEN 1 ELSE 0 END) AS mature
        FROM cards WHERE suspended = 0
        """
    )
    fun maturity(): Flow<Maturity>

    /**
     * How many cards fall due on each of the next [days] days, counting from local midnight.
     *
     * The forecast is what turns "you have 40 reviews today" into something a student can plan
     * around - a wall of work on Thursday is worth knowing about on Monday.
     *
     * [from] must be the start of today, not the current instant: the chart labels its first bucket
     * "Today" and paints it as today, so the buckets have to be calendar days. Measured from "now"
     * instead, every boundary fell at whatever time of day the screen happened to be opened.
     *
     * MAX(..., 0) rather than `dueAt >= :from`, so anything already overdue folds into today's bar.
     * Excluding it meant the one group of users who most need a forecast - anyone carrying a backlog,
     * which is the normal case - saw their backlog missing from it entirely.
     */
    @Query(
        """
        SELECT CAST(MAX((dueAt - :from) / 86400000, 0) AS INTEGER) AS dayOffset, COUNT(*) AS count
          FROM cards
         WHERE suspended = 0 AND state != 'NEW'
           AND dueAt < :from + (:days * 86400000)
         GROUP BY dayOffset
         ORDER BY dayOffset ASC
        """
    )
    fun forecast(from: Long, days: Int): Flow<List<ForecastDay>>

    @Query("DELETE FROM cards WHERE deckId = :deckId")
    suspend fun deleteAllInDeck(deckId: Long)

    /**
     * Everything waiting across every deck, due plus unseen. What the daily reminder counts.
     *
     * New cards are capped per session when studying, but not here - the reminder is answering
     * "is there anything to do?", and a thousand unseen cards is emphatically a yes.
     */
    @Query(
        """
        SELECT COUNT(*) FROM cards
         WHERE suspended = 0 AND buriedUntil <= :now
           AND (state = 'NEW' OR dueAt <= :now)
        """
    )
    suspend fun totalWaiting(now: Long): Int

    /** Move every card from one deck to another. The merge. */
    @Query("UPDATE cards SET deckId = :destination WHERE deckId = :source")
    suspend fun moveAll(source: Long, destination: Long)

    @Query("SELECT * FROM cards")
    suspend fun allCards(): List<CardEntity>

    /** Just the questions in one deck, for keeping a second photograph from re-suggesting them. */
    @Query("SELECT front FROM cards WHERE deckId = :deckId")
    suspend fun frontsInDeck(deckId: Long): List<String>
}

/** One answer from the log, as the schedule fitter reads it. */
data class LoggedAnswer(val cardId: Long, val reviewedAt: Long, val grade: com.layerbit.abhyas.data.model.Grade)

/** One day's answer count, for the streak strip on the stats screen. */
data class DailyCount(val day: String, val count: Int)

/** How the collection is spread across the stages of being learned. */
data class Maturity(
    val unseen: Int,
    val learning: Int,
    /** Reviewing, but still on an interval under three weeks. */
    val young: Int,
    /** Reviewing on three weeks or more - knowledge that is actually sticking. */
    val mature: Int
) {
    val total: Int get() = unseen + learning + young + mature
}

/** Cards falling due [dayOffset] days from now. */
data class ForecastDay(val dayOffset: Int, val count: Int)

/** How often answers were correct, over some window. */
data class RetentionCount(val correct: Int, val total: Int) {
    /** Share of reviews recalled, 0..1. Null when there is nothing to divide by. */
    val rate: Float? get() = if (total == 0) null else correct.toFloat() / total
}

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    /**
     * Remove one logged answer. The only caller is undo.
     *
     * The log is append-only everywhere else on purpose - it is the audit trail when scheduling
     * looks wrong. Undo is the one case where a row must genuinely disappear, because leaving it
     * would mean a review the user explicitly took back still counted towards their streak.
     */
    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM review_log WHERE reviewedAt >= :since")
    fun countSince(since: Long): Flow<Int>

    /**
     * Answers per local day for the last [days] days.
     *
     * `'unixepoch'` converts the stored millis (divided to seconds) into a date and `'localtime'`
     * shifts it into the user's zone, so a session at 11pm counts towards that evening rather
     * than the next morning in UTC - which is exactly the kind of detail that makes a streak
     * counter feel broken.
     */
    @Query(
        """
        SELECT date(reviewedAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
          FROM review_log
         WHERE reviewedAt >= :since
         GROUP BY day
         ORDER BY day ASC
        """
    )
    fun dailyCounts(since: Long): Flow<List<DailyCount>>

    @Query("SELECT COUNT(*) FROM review_log")
    fun totalReviews(): Flow<Int>

    /** A plain count rather than a Flow, for the reminder worker to ask once and exit. */
    @Query("SELECT COUNT(*) FROM review_log WHERE reviewedAt >= :since")
    suspend fun countSinceOnce(since: Long): Int

    /**
     * Every answer ever given, oldest first per card - what the schedule is fitted to. Three
     * narrow columns rather than whole rows, since a long-time user has hundreds of thousands.
     */
    @Query("SELECT cardId, reviewedAt, grade FROM review_log ORDER BY cardId, reviewedAt")
    suspend fun allAnswers(): List<LoggedAnswer>

    @Query("UPDATE review_log SET deckId = :destination WHERE deckId = :source")
    suspend fun moveAll(source: Long, destination: Long)

    /**
     * How many answers since [since] were recalled at all, versus forgotten.
     *
     * "Again" is the only grade that means the card was not recalled, so true retention is
     * everything else over everything. This is the number that says whether the schedule is
     * working - if it drifts well below the target, the intervals are too long for this user.
     */
    @Query(
        """
        SELECT SUM(CASE WHEN grade != 'AGAIN' THEN 1 ELSE 0 END) AS correct,
               COUNT(*) AS total
          FROM review_log
         WHERE reviewedAt >= :since
        """
    )
    fun retention(since: Long): Flow<RetentionCount>
}

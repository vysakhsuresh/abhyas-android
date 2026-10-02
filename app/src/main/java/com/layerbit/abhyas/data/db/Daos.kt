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
    val total: Int,
    val due: Int,
    val newCount: Int
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

    /**
     * Every deck with its counts in one query.
     *
     * The counts are computed with correlated subqueries rather than by loading each deck's cards
     * and counting in Kotlin, because this drives the home screen and has to stay cheap as a
     * collection grows into the thousands. `newCount` is reported separately from `due` because a
     * pile of unseen cards and a pile of overdue ones mean very different things to a student.
     */
    @Query(
        """
        SELECT d.id, d.name, d.lastUsedAt, d.script,
               (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id) AS total,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId = d.id AND c.suspended = 0
                   AND c.state != 'NEW' AND c.dueAt <= :now) AS due,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId = d.id AND c.suspended = 0
                   AND c.state = 'NEW') AS newCount
        FROM decks d
        ORDER BY d.lastUsedAt DESC
        """
    )
    fun summaries(now: Long): Flow<List<DeckSummary>>

    @Query(
        """
        SELECT d.id, d.name, d.lastUsedAt, d.script,
               (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id) AS total,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId = d.id AND c.suspended = 0
                   AND c.state != 'NEW' AND c.dueAt <= :now) AS due,
               (SELECT COUNT(*) FROM cards c
                 WHERE c.deckId = d.id AND c.suspended = 0
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
     * NEW cards are included regardless of `dueAt` (which is 0 for them) but capped by
     * [newLimit], so a freshly imported 200-card chapter does not bury the day's reviews.
     */
    @Query(
        """
        SELECT * FROM cards
         WHERE deckId = :deckId AND suspended = 0
           AND (state != 'NEW' AND dueAt <= :now)
         ORDER BY CASE state WHEN 'LEARNING' THEN 0 WHEN 'RELEARNING' THEN 0 ELSE 1 END,
                  dueAt ASC
        """
    )
    suspend fun dueNow(deckId: Long, now: Long): List<CardEntity>

    @Query(
        """
        SELECT * FROM cards
         WHERE deckId = :deckId AND suspended = 0 AND state = 'NEW'
         ORDER BY createdAt ASC
         LIMIT :newLimit
        """
    )
    suspend fun newCards(deckId: Long, newLimit: Int): List<CardEntity>

    /**
     * The soonest a card in this deck comes back, used to tell the user *when* to return rather
     * than just that they are done. Learning cards count, which is what makes "in 9 minutes"
     * possible instead of an unhelpful "tomorrow".
     */
    @Query(
        """
        SELECT MIN(dueAt) FROM cards
         WHERE deckId = :deckId AND suspended = 0 AND state != 'NEW'
        """
    )
    suspend fun nextDueAt(deckId: Long): Long?

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
         WHERE front LIKE '%' || :term || '%'
            OR back LIKE '%' || :term || '%'
            OR sourceText LIKE '%' || :term || '%'
         ORDER BY createdAt DESC
         LIMIT 200
        """
    )
    fun search(term: String): Flow<List<CardEntity>>

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
     * How many cards fall due on each of the next [days] days.
     *
     * The forecast is what turns "you have 40 reviews today" into something a student can plan
     * around - a wall of work on Thursday is worth knowing about on Monday.
     */
    @Query(
        """
        SELECT CAST((dueAt - :from) / 86400000 AS INTEGER) AS dayOffset, COUNT(*) AS count
          FROM cards
         WHERE suspended = 0 AND state != 'NEW' AND dueAt >= :from
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
         WHERE suspended = 0
           AND (state = 'NEW' OR dueAt <= :now)
        """
    )
    suspend fun totalWaiting(now: Long): Int

    /** Move every card from one deck to another. The merge. */
    @Query("UPDATE cards SET deckId = :destination WHERE deckId = :source")
    suspend fun moveAll(source: Long, destination: Long)

    @Query("SELECT * FROM cards")
    suspend fun allCards(): List<CardEntity>
}

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

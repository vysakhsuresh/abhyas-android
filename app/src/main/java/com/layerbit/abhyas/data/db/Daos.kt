package com.layerbit.abhyas.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.ocr.ScriptOption
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

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLogEntity)

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
}

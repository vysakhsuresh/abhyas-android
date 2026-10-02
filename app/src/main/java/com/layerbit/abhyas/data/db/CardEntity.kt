package com.layerbit.abhyas.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.srs.Scheduler
import com.layerbit.abhyas.data.srs.Scheduling

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = DeckEntity::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            // Deleting a deck has to take its cards with it, or the review queue starts serving
            // cards from a deck the user believes they deleted.
            onDelete = ForeignKey.CASCADE
        )
    ],
    // The study queue is always "cards in this deck, due before now, not suspended, soonest
    // first". Without this index that becomes a full scan on every card flip.
    indices = [Index(value = ["deckId", "dueAt"])]
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,

    val front: String,
    val back: String,

    /**
     * The sentence this card was generated from, kept so the study screen can show the original
     * wording in context. A generated question is only as good as its source, and being able to
     * see that source is what lets a user tell a bad card from a bad memory.
     */
    val sourceText: String?,

    // --- Scheduling. Mirrors [Scheduling]; see [Scheduler] and [Fsrs] for what each means. ---
    val state: CardState = CardState.NEW,
    val dueAt: Long = 0L,
    val intervalDays: Int = 0,

    /** FSRS stability: days until recall probability falls to 90%. */
    @ColumnInfo(defaultValue = "0.0")
    val stability: Double = 0.0,
    /** FSRS difficulty, 1..10. */
    @ColumnInfo(defaultValue = "0.0")
    val difficulty: Double = 0.0,
    /**
     * When this card was last answered.
     *
     * FSRS needs it, and SM-2 never did - it had no concept of how late a review was. Collections
     * created before the change have zero here, and the scheduler falls back to the interval.
     */
    @ColumnInfo(defaultValue = "0")
    val lastReviewedAt: Long = 0L,

    val repetitions: Int = 0,
    val lapses: Int = 0,
    val learningStep: Int = 0,

    /** SM-2's ease factor, kept only so a pre-FSRS collection can be converted. */
    val easeFactor: Double = Scheduler.LEGACY_STARTING_EASE,

    /** Suspended cards stay in the deck and in the counts, but never enter the queue. */
    val suspended: Boolean = false,

    val createdAt: Long
) {
    /** Read the scheduling fields out as the value the [Scheduler] operates on. */
    fun scheduling(): Scheduling = Scheduling(
        state = state,
        dueAt = dueAt,
        intervalDays = intervalDays,
        stability = stability,
        difficulty = difficulty,
        lastReviewedAt = lastReviewedAt,
        repetitions = repetitions,
        lapses = lapses,
        learningStep = learningStep,
        easeFactor = easeFactor
    )

    /** Forgotten often enough that the card itself is the problem. See [Scheduler.LEECH_THRESHOLD]. */
    val isLeech: Boolean get() = Scheduler.isLeech(lapses)

    /** Fold a scheduler result back in, leaving the content fields untouched. */
    fun withScheduling(s: Scheduling): CardEntity = copy(
        state = s.state,
        dueAt = s.dueAt,
        intervalDays = s.intervalDays,
        stability = s.stability,
        difficulty = s.difficulty,
        lastReviewedAt = s.lastReviewedAt,
        repetitions = s.repetitions,
        lapses = s.lapses,
        learningStep = s.learningStep,
        easeFactor = s.easeFactor
    )
}

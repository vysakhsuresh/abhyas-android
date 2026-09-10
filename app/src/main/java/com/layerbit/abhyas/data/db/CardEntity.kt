package com.layerbit.abhyas.data.db

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

    // --- Scheduling. Mirrors [Scheduling]; see [Scheduler] for what each field means. ---
    val state: CardState = CardState.NEW,
    val dueAt: Long = 0L,
    val intervalDays: Int = 0,
    val easeFactor: Double = Scheduler.STARTING_EASE,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val learningStep: Int = 0,

    /** Suspended cards stay in the deck and in the counts, but never enter the queue. */
    val suspended: Boolean = false,

    val createdAt: Long
) {
    /** Read the scheduling fields out as the value the [Scheduler] operates on. */
    fun scheduling(): Scheduling = Scheduling(
        state = state,
        dueAt = dueAt,
        intervalDays = intervalDays,
        easeFactor = easeFactor,
        repetitions = repetitions,
        lapses = lapses,
        learningStep = learningStep
    )

    /** Fold a scheduler result back in, leaving the content fields untouched. */
    fun withScheduling(s: Scheduling): CardEntity = copy(
        state = s.state,
        dueAt = s.dueAt,
        intervalDays = s.intervalDays,
        easeFactor = s.easeFactor,
        repetitions = s.repetitions,
        lapses = s.lapses,
        learningStep = s.learningStep
    )
}

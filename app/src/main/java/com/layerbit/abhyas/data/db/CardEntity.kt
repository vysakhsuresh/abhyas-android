package com.layerbit.abhyas.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.CardType
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

    val createdAt: Long,

    /** Text on both sides, or a masked picture. Decides how the study screen draws the card. */
    @ColumnInfo(defaultValue = "BASIC")
    val type: CardType = CardType.BASIC,

    /**
     * The user's tags, lower-cased and space-separated with no `#`: `"anatomy exam-1"`. See
     * [Tags]. One column rather than a join table: a card carries a handful at most, every query
     * that reads them already reads the card, and a filter is a single LIKE.
     */
    @ColumnInfo(defaultValue = "")
    val tags: String = "",

    /**
     * For an [CardType.OCCLUSION] card, the file name of its picture inside the app's private
     * image directory - never a path, so a backup restored on another phone still resolves.
     */
    val imagePath: String? = null,

    /** For an [CardType.OCCLUSION] card, the masked regions as JSON. See `Occlusion`. */
    val occlusion: String? = null,

    /**
     * Held back from the queue until this instant, because a sibling - a card made from the same
     * sentence or the same picture - was answered today. Showing the second card straight after
     * the first gives its answer away. Never touches the schedule itself.
     */
    @ColumnInfo(defaultValue = "0")
    val buriedUntil: Long = 0L
) {
    /**
     * What makes two cards siblings: the sentence they were both made from, or the picture they
     * both mask. Null for a card made by hand, which has no siblings.
     */
    val siblingKey: String?
        get() = sourceText?.takeIf { it.isNotBlank() } ?: imagePath?.let { "image:$it" }

    /** Read the scheduling fields out as the value the [Scheduler] operates on. */
    fun scheduling(): Scheduling = Scheduling(
        id = id,
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

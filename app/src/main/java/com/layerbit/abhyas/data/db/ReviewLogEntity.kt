package com.layerbit.abhyas.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.layerbit.abhyas.data.model.Grade

/**
 * One row per answer, ever.
 *
 * The card itself only carries its *current* scheduling, so without this table there would be no
 * way to show a streak, count what was actually done today, or explain why a card is where it is.
 * Rows are never updated - only appended - which also makes it the audit trail if scheduling ever
 * looks wrong.
 */
@Entity(
    tableName = "review_log",
    indices = [Index(value = ["reviewedAt"]), Index(value = ["cardId"])]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val deckId: Long,
    val reviewedAt: Long,
    val grade: Grade,
    /** Interval in days before and after this answer, for explaining a card's history. */
    val intervalBefore: Int,
    val intervalAfter: Int
)

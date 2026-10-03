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
 *
 * **No foreign key, and no pruning.** Both are deliberate, and both look like oversights, so:
 *
 * A row records that the user answered something, which stays true after the card is gone. Cascading
 * on delete would mean tidying up a deck retroactively shortened their streak and moved their
 * retention rate - rewriting their own history as a side effect of housekeeping. Nothing joins this
 * table either (every query counts or groups rows of it alone), so an orphaned row costs its own
 * bytes and nothing else.
 *
 * Which leaves growth, and the arithmetic says to leave it: a heavy user answering a hundred cards a
 * day for ten years writes about 365,000 rows of a few dozen bytes - tens of megabytes, on a device
 * that holds the photographs those cards came from a hundred times over. Pruning to cap it would
 * have to delete the oldest reviews, which is precisely what "answers ever" counts.
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

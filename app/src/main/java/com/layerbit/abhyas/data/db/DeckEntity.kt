package com.layerbit.abhyas.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A subject, chapter or exam - whatever the user decided to group cards under. */
@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Bumped whenever cards are added or reviewed, so the deck list can sort by recent use. */
    val lastUsedAt: Long
)

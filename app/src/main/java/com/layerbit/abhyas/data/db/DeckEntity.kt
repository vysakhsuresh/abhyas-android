package com.layerbit.abhyas.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.layerbit.abhyas.data.ocr.ScriptOption

/** A subject, chapter or exam - whatever the user decided to group cards under. */
@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Bumped whenever cards are added or reviewed, so the deck list can sort by recent use. */
    val lastUsedAt: Long,
    /**
     * The writing system this deck's pages are in.
     *
     * Per deck rather than per app, because one student's Hindi deck and Biology deck are both
     * open at once and the recognisers are not interchangeable - the Latin model returns
     * confident nonsense when shown Devanagari rather than failing.
     */
    @ColumnInfo(defaultValue = "LATIN")
    val script: ScriptOption = ScriptOption.DEFAULT
)

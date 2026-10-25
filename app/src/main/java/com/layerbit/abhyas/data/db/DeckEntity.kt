package com.layerbit.abhyas.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.layerbit.abhyas.data.ocr.ScriptOption

/** A subject, chapter or exam - whatever the user decided to group cards under. */
@Entity(tableName = "decks", indices = [Index(value = ["parentId"])])
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
    val script: ScriptOption = ScriptOption.DEFAULT,
    /**
     * The deck this one sits inside, or null for a top-level deck: "Biology" holding "Chapter 4".
     *
     * One level only, enforced by the repository. A subject holding its chapters is what students
     * actually build; arbitrary depth would cost a recursive query on the home screen and a tree
     * editor nobody asked for. No foreign key, deliberately - deleting a subject must not take its
     * chapters with it, and SQLite cannot add a SET NULL key to an existing table without rebuilding
     * it, so the repository lifts the children to the top level in the same transaction instead.
     */
    val parentId: Long? = null
)

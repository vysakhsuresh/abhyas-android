package com.layerbit.abhyas.data.db

import androidx.room.TypeConverter
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.ocr.ScriptOption

/**
 * Enums are stored by name rather than ordinal on purpose: an ordinal silently reinterprets every
 * existing row the day someone reorders the enum, and that failure is invisible until a user's
 * whole collection is scheduled wrongly.
 */
class Converters {
    @TypeConverter fun cardStateToString(value: CardState): String = value.name
    @TypeConverter fun stringToCardState(value: String): CardState = CardState.valueOf(value)

    @TypeConverter fun gradeToString(value: Grade): String = value.name
    @TypeConverter fun stringToGrade(value: String): Grade = Grade.valueOf(value)

    @TypeConverter fun scriptToString(value: ScriptOption): String = value.name

    /**
     * Falls back rather than throwing. A row written by a future build that knows a script this
     * one does not would otherwise crash the app on read, and reading someone's deck as Latin is
     * a far better failure than refusing to open their collection at all.
     */
    @TypeConverter fun stringToScript(value: String): ScriptOption =
        runCatching { ScriptOption.valueOf(value) }.getOrDefault(ScriptOption.DEFAULT)
}

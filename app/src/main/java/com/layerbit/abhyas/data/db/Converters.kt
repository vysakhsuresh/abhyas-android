package com.layerbit.abhyas.data.db

import androidx.room.TypeConverter
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade

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
}

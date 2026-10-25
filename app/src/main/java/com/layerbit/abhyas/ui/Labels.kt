package com.layerbit.abhyas.ui

import androidx.annotation.StringRes
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.generate.CardKind
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.ocr.ScriptOption

/**
 * Where the data layer's enums get their words.
 *
 * The enums keep English labels for logs and tests, but nothing on screen reads them: the UI goes
 * through these, so every word a user sees comes from strings.xml and follows the app's language.
 */

@StringRes
fun Grade.labelRes(): Int = when (this) {
    Grade.AGAIN -> R.string.grade_again
    Grade.HARD -> R.string.grade_hard
    Grade.GOOD -> R.string.grade_good
    Grade.EASY -> R.string.grade_easy
}

@StringRes
fun CardKind.labelRes(): Int = when (this) {
    CardKind.QA -> R.string.kind_qa
    CardKind.DEFINITION -> R.string.kind_definition
    CardKind.LIST -> R.string.kind_list
    CardKind.CLOZE -> R.string.kind_cloze
    CardKind.TABLE -> R.string.kind_table
}

@StringRes
fun CardState.labelRes(): Int = when (this) {
    CardState.NEW -> R.string.state_new
    CardState.LEARNING -> R.string.state_learning
    CardState.REVIEW -> R.string.state_review
    CardState.RELEARNING -> R.string.state_relearning
}

@StringRes
fun ScriptOption.labelRes(): Int = when (this) {
    ScriptOption.LATIN -> R.string.script_latin
    ScriptOption.DEVANAGARI -> R.string.script_devanagari
    ScriptOption.CHINESE -> R.string.script_chinese
    ScriptOption.JAPANESE -> R.string.script_japanese
    ScriptOption.KOREAN -> R.string.script_korean
}

@StringRes
fun ScriptOption.coversRes(): Int = when (this) {
    ScriptOption.LATIN -> R.string.script_latin_covers
    ScriptOption.DEVANAGARI -> R.string.script_devanagari_covers
    ScriptOption.CHINESE -> R.string.script_chinese_covers
    ScriptOption.JAPANESE -> R.string.script_japanese_covers
    ScriptOption.KOREAN -> R.string.script_korean_covers
}

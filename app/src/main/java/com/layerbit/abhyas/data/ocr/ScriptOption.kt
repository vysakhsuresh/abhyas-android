package com.layerbit.abhyas.data.ocr

/**
 * The writing system a deck's pages are in.
 *
 * ML Kit ships a separate bundled recogniser per script family, and they are not
 * interchangeable: the Latin model returns confident nonsense when shown Devanagari rather than
 * failing, which is the worst possible behaviour because the user sees cards get made and only
 * later discovers they are gibberish. So the script is chosen per deck and the right model is
 * used, instead of being guessed.
 *
 * The non-Latin models all recognise Latin characters as well, which is what makes mixed pages
 * work - a Hindi textbook with English technical terms in it reads correctly under
 * [DEVANAGARI]. That is also why there is no "auto" option: picking the non-Latin model when in
 * doubt is strictly better than picking Latin, and the user knows their own textbook.
 */
enum class ScriptOption(
    /** How this is named in the UI, in English. */
    val label: String,
    /** The same name in its own script, shown alongside so it is recognisable at a glance. */
    val nativeLabel: String,
    /** The languages a user would actually be studying with this model. */
    val covers: String
) {
    LATIN("Latin", "Abc", "English, Hindi in Roman, and most European languages"),
    DEVANAGARI("Devanagari", "देवनागरी", "Hindi, Marathi, Sanskrit, Nepali (and Latin)"),
    CHINESE("Chinese", "中文", "Simplified and Traditional Chinese (and Latin)"),
    JAPANESE("Japanese", "日本語", "Kanji, hiragana, katakana (and Latin)"),
    KOREAN("Korean", "한국어", "Hangul (and Latin)");

    companion object {
        val DEFAULT = LATIN
    }
}

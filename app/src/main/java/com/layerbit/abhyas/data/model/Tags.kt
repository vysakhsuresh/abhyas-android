package com.layerbit.abhyas.data.model

/**
 * How tags are written to and read from a card's `tags` column.
 *
 * Stored lower-cased, space-separated, without the `#` people type - `"anatomy exam-1"` - so a filter
 * is one LIKE against `' ' || tags || ' '` and two spellings of the same tag cannot drift apart.
 * Letters in any script are allowed, so a Hindi tag works as well as an English one.
 */
object Tags {

    private val SEPARATORS = Regex("""[\s,;#]+""")
    private val NOT_TAG = Regex("""[^\p{L}\p{M}\p{N}_\-]""")

    /** Parse what a user typed - "#Exam 1, anatomy" - into the stored form, "exam 1 anatomy". */
    fun normalise(input: String): String = parse(input).joinToString(" ")

    /** The individual tags in a stored or typed value, de-duplicated, in order. */
    fun parse(input: String): List<String> =
        input.split(SEPARATORS)
            .map { it.lowercase().replace(NOT_TAG, "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS)

    /** One tag in the stored form, or null if nothing usable is left of it. */
    fun single(input: String): String? = parse(input).firstOrNull()

    /** Shown back to the user with the `#` they expect. */
    fun display(stored: String): String = parse(stored).joinToString(" ") { "#$it" }

    /** Merge a tag set into an existing one, keeping what was already there first. */
    fun merge(existing: String, added: String): String = (parse(existing) + parse(added)).distinct()
        .take(MAX_TAGS).joinToString(" ")

    /** More than this on one card is a sentence, not a set of tags. */
    const val MAX_TAGS = 12
}

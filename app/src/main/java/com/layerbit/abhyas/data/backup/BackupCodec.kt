package com.layerbit.abhyas.data.backup

import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.srs.Scheduler
import org.json.JSONArray
import org.json.JSONObject

/** A whole collection, as it travels in and out of a backup file. */
data class Backup(
    val decks: List<DeckEntity>,
    val cards: List<CardEntity>
)

/**
 * Reads and writes the backup file.
 *
 * Abhyas has no account and no network, so this file is the *only* way a collection survives a
 * lost phone. That makes two things non-negotiable:
 *
 *  - **Scheduling travels with the cards.** A backup that restored the text but not the
 *    intervals would hand back a pile of new cards and quietly erase months of work.
 *  - **Reading is forgiving.** A file written by a newer version, hand-edited, or truncated by a
 *    full disk must restore what it can rather than refuse everything. Every field falls back to
 *    a sane default and an unreadable row is skipped, not fatal.
 *
 * Plain org.json rather than a serialisation library: the format is a dozen fields, it has to
 * stay readable by a human in a text editor, and adding a dependency for it would be the only
 * reason the APK grew.
 */
object BackupCodec {

    const val FORMAT_VERSION = 1
    const val MIME_TYPE = "application/json"

    fun encode(backup: Backup): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("app", "abhyas")
        root.put("exportedAt", System.currentTimeMillis())

        val decks = JSONArray()
        backup.decks.forEach { deck ->
            decks.put(
                JSONObject().apply {
                    put("id", deck.id)
                    put("name", deck.name)
                    put("createdAt", deck.createdAt)
                    put("lastUsedAt", deck.lastUsedAt)
                    put("script", deck.script.name)
                }
            )
        }
        root.put("decks", decks)

        val cards = JSONArray()
        backup.cards.forEach { card ->
            cards.put(
                JSONObject().apply {
                    put("deckId", card.deckId)
                    put("front", card.front)
                    put("back", card.back)
                    put("sourceText", card.sourceText ?: JSONObject.NULL)
                    put("state", card.state.name)
                    put("dueAt", card.dueAt)
                    put("intervalDays", card.intervalDays)
                    put("stability", card.stability)
                    put("difficulty", card.difficulty)
                    put("lastReviewedAt", card.lastReviewedAt)
                    put("easeFactor", card.easeFactor)
                    put("repetitions", card.repetitions)
                    put("lapses", card.lapses)
                    put("learningStep", card.learningStep)
                    put("suspended", card.suspended)
                    put("createdAt", card.createdAt)
                }
            )
        }
        root.put("cards", cards)

        return root.toString(2)
    }

    /**
     * Parse a backup file. Throws only if the text is not JSON at all - everything softer than
     * that is recovered from.
     *
     * Deck ids in the file are kept here as written; the caller remaps them on insert, because
     * restoring into a collection that already has decks must not collide with existing rows.
     */
    fun decode(text: String): Backup {
        val root = JSONObject(text)

        val decks = root.optJSONArray("decks").orEmpty().mapObjects { obj ->
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapObjects null
            DeckEntity(
                id = obj.optLong("id"),
                name = name,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                lastUsedAt = obj.optLong("lastUsedAt", System.currentTimeMillis()),
                script = runCatching { ScriptOption.valueOf(obj.optString("script")) }
                    .getOrDefault(ScriptOption.DEFAULT)
            )
        }

        val cards = root.optJSONArray("cards").orEmpty().mapObjects { obj ->
            val front = obj.optString("front").takeIf { it.isNotBlank() } ?: return@mapObjects null
            val back = obj.optString("back").takeIf { it.isNotBlank() } ?: return@mapObjects null
            CardEntity(
                deckId = obj.optLong("deckId"),
                front = front,
                back = back,
                // isNull first, and it is not belt-and-braces. A null sourceText is written as
                // JSONObject.NULL, and Android's optString does not return the fallback for that -
                // it stringifies the sentinel and hands back the four characters "null". So every
                // card without a source sentence came back from a restore claiming its source text
                // was the word "null". Invisible in unit tests, because the org.json on the JVM
                // test classpath returns the fallback here and Android's does not.
                sourceText = if (obj.isNull("sourceText")) {
                    null
                } else {
                    obj.optString("sourceText").takeIf { it.isNotBlank() }
                },
                state = runCatching { CardState.valueOf(obj.optString("state")) }
                    .getOrDefault(CardState.NEW),
                dueAt = obj.optLong("dueAt"),
                intervalDays = obj.optInt("intervalDays"),
                // A file written before FSRS carries none of these three. Zero is exactly what a
                // pre-FSRS row holds in the database too, and the scheduler converts from the ease
                // factor when it sees it - so the fallback here is the migration path, not a loss.
                stability = obj.optDouble("stability", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                difficulty = obj.optDouble("difficulty", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                lastReviewedAt = obj.optLong("lastReviewedAt"),
                easeFactor = obj.optDouble("easeFactor", Scheduler.LEGACY_STARTING_EASE)
                    .takeIf { !it.isNaN() } ?: Scheduler.LEGACY_STARTING_EASE,
                repetitions = obj.optInt("repetitions"),
                lapses = obj.optInt("lapses"),
                learningStep = obj.optInt("learningStep"),
                suspended = obj.optBoolean("suspended"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }

        return Backup(decks, cards)
    }

    /** A filename someone can recognise a month later in a folder of downloads. */
    fun suggestedFileName(dateStamp: String): String = "abhyas-backup-$dateStamp.json"

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> =
        (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { runCatching { transform(it) }.getOrNull() }
        }
}

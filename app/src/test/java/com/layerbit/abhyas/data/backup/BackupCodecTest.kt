package com.layerbit.abhyas.data.backup

import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.srs.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abhyas has no account and no sync, so a backup file is the only thing between a user and
 * losing their whole collection with a phone. These tests are the safety net for that file.
 */
class BackupCodecTest {

    private val deck = DeckEntity(
        id = 7,
        name = "Biology - Chapter 4",
        createdAt = 1_700_000_000_000,
        lastUsedAt = 1_700_000_500_000,
        script = ScriptOption.DEVANAGARI
    )

    private val card = CardEntity(
        id = 42,
        deckId = 7,
        front = "What is Chlorophyll?",
        back = "The green pigment in chloroplasts",
        sourceText = "Chlorophyll: the green pigment in chloroplasts.",
        state = CardState.REVIEW,
        dueAt = 1_700_100_000_000,
        intervalDays = 21,
        stability = 20.74,
        difficulty = 5.31,
        lastReviewedAt = 1_698_300_000_000,
        easeFactor = 2.36,
        repetitions = 5,
        lapses = 2,
        learningStep = 0,
        suspended = false,
        createdAt = 1_700_000_000_000
    )

    private fun roundTrip(backup: Backup) = BackupCodec.decode(BackupCodec.encode(backup))

    @Test
    fun `a deck survives the round trip`() {
        val restored = roundTrip(Backup(listOf(deck), emptyList())).decks.single()

        assertEquals(deck.name, restored.name)
        assertEquals(deck.createdAt, restored.createdAt)
        assertEquals("a Hindi deck must not come back reading Latin", deck.script, restored.script)
    }

    @Test
    fun `scheduling survives the round trip`() {
        // The point of the whole file. Restoring the words but not the intervals would hand back
        // a pile of new cards and quietly erase months of work.
        val restored = roundTrip(Backup(listOf(deck), listOf(card))).cards.single()

        assertEquals(card.state, restored.state)
        assertEquals(card.dueAt, restored.dueAt)
        assertEquals(card.intervalDays, restored.intervalDays)
        // FSRS keeps its memory in these three, so they are now as load-bearing as the interval:
        // dropping them would restore the card as one FSRS has never seen before.
        assertEquals(card.stability, restored.stability, 0.0001)
        assertEquals(card.difficulty, restored.difficulty, 0.0001)
        assertEquals(card.lastReviewedAt, restored.lastReviewedAt)
        assertEquals(card.easeFactor, restored.easeFactor, 0.0001)
        assertEquals(card.repetitions, restored.repetitions)
        assertEquals(card.lapses, restored.lapses)
    }

    @Test
    fun `text survives the round trip`() {
        val restored = roundTrip(Backup(listOf(deck), listOf(card))).cards.single()

        assertEquals(card.front, restored.front)
        assertEquals(card.back, restored.back)
        assertEquals(card.sourceText, restored.sourceText)
    }

    @Test
    fun `non-Latin text survives the round trip`() {
        val hindi = card.copy(
            front = "पर्णहरित क्या है?",
            back = "पत्तियों में पाया जाने वाला हरा वर्णक",
            sourceText = null
        )
        val restored = roundTrip(Backup(listOf(deck), listOf(hindi))).cards.single()

        assertEquals(hindi.front, restored.front)
        assertEquals(hindi.back, restored.back)
        assertNull(restored.sourceText)
    }

    @Test
    fun `cards stay attached to their own deck`() {
        val second = deck.copy(id = 9, name = "History")
        val other = card.copy(id = 43, deckId = 9, front = "Who was Ashoka?")

        val restored = roundTrip(Backup(listOf(deck, second), listOf(card, other)))

        assertEquals(7, restored.cards.first { it.front == card.front }.deckId)
        assertEquals(9, restored.cards.first { it.front == other.front }.deckId)
    }

    @Test
    fun `an empty collection encodes and decodes without error`() {
        val restored = roundTrip(Backup(emptyList(), emptyList()))

        assertTrue(restored.decks.isEmpty())
        assertTrue(restored.cards.isEmpty())
    }

    // ------------------------------------------------------------------ tolerating bad input

    @Test
    fun `a file from a newer version restores what it can`() {
        val future = """
            {
              "format": 99,
              "decks": [{"id": 1, "name": "Physics", "somethingNew": {"a": 1}}],
              "cards": [{"deckId": 1, "front": "Q", "back": "A", "unknownField": true}]
            }
        """.trimIndent()

        val restored = BackupCodec.decode(future)

        assertEquals("Physics", restored.decks.single().name)
        assertEquals("Q", restored.cards.single().front)
    }

    @Test
    fun `missing scheduling fields fall back to a sane new card`() {
        val minimal = """{"decks":[{"id":1,"name":"D"}],"cards":[{"deckId":1,"front":"Q","back":"A"}]}"""

        val restored = BackupCodec.decode(minimal).cards.single()

        assertEquals(CardState.NEW, restored.state)
        assertEquals(Scheduler.LEGACY_STARTING_EASE, restored.easeFactor, 0.0001)
        assertEquals(0, restored.intervalDays)
        assertEquals(0.0, restored.stability, 0.0001)
        assertEquals(0.0, restored.difficulty, 0.0001)
        assertEquals(0L, restored.lastReviewedAt)
    }

    @Test
    fun `an unknown script falls back rather than failing the whole restore`() {
        val odd = """{"decks":[{"id":1,"name":"D","script":"KLINGON"}],"cards":[]}"""

        assertEquals(ScriptOption.DEFAULT, BackupCodec.decode(odd).decks.single().script)
    }

    @Test
    fun `rows missing required text are skipped, not fatal`() {
        val partly = """
            {
              "decks": [{"id": 1, "name": "Good"}, {"id": 2}],
              "cards": [
                {"deckId": 1, "front": "Q", "back": "A"},
                {"deckId": 1, "front": "", "back": "A"},
                {"deckId": 1, "front": "Q2"}
              ]
            }
        """.trimIndent()

        val restored = BackupCodec.decode(partly)

        assertEquals("a deck with no name is not recoverable", 1, restored.decks.size)
        assertEquals("a card with no front or no back is not a card", 1, restored.cards.size)
    }

    @Test
    fun `the suggested filename carries the date so a folder of them is legible`() {
        assertEquals("abhyas-backup-2026-03-15.json", BackupCodec.suggestedFileName("2026-03-15"))
    }
}

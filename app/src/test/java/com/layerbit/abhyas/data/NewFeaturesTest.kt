package com.layerbit.abhyas.data

import com.layerbit.abhyas.data.backup.Backup
import com.layerbit.abhyas.data.backup.BackupCodec
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckEntity
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.model.CardType
import com.layerbit.abhyas.data.model.Tags
import com.layerbit.abhyas.data.occlusion.ImageNames
import com.layerbit.abhyas.data.occlusion.Occlusion
import com.layerbit.abhyas.data.occlusion.Region
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.decks.DecksViewModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tags, sibling burying, occlusion masks, the deck tree and the version-2 backup format. */
class NewFeaturesTest {

    private fun card(id: Long, source: String? = null, image: String? = null) = CardEntity(
        id = id, deckId = 1, front = "Q$id", back = "A$id", sourceText = source,
        createdAt = id, imagePath = image
    )

    // ------------------------------------------------------------------------------------ tags

    @Test
    fun `tags are normalised from whatever the user typed`() {
        assertEquals("exam-1 anatomy", Tags.normalise("#Exam-1, anatomy  #ANATOMY"))
        assertEquals("", Tags.normalise("  # , ; "))
        // Letters in any script survive - a Hindi tag works.
        assertEquals("परीक्षा", Tags.normalise("#परीक्षा"))
        assertEquals("#exam-1 #anatomy", Tags.display("exam-1 anatomy"))
    }

    @Test
    fun `merging tags keeps the existing order and drops duplicates`() {
        assertEquals("a b c", Tags.merge("a b", "#B c"))
    }

    // -------------------------------------------------------------------------------- siblings

    @Test
    fun `only the first card made from one sentence enters the queue`() {
        val queue = listOf(
            card(1, source = "The heart has four chambers."),
            card(2, source = "Mitosis has four phases."),
            card(3, source = "The heart has four chambers."),
            card(4),
            card(5)
        )
        assertEquals(listOf(1L, 2L, 4L, 5L), AbhyasRepository.withoutSiblings(queue).map { it.id })
    }

    @Test
    fun `cards masking the same picture are siblings too`() {
        val queue = listOf(card(1, image = "aaaaaaaa.jpg"), card(2, image = "aaaaaaaa.jpg"), card(3, image = "bbbbbbbb.jpg"))
        assertEquals(listOf(1L, 3L), AbhyasRepository.withoutSiblings(queue).map { it.id })
    }

    // ------------------------------------------------------------------------------- occlusion

    @Test
    fun `masks survive an encode and decode, whichever corner the drag began at`() {
        val regions = listOf(Region(0.5f, 0.6f, 0.1f, 0.2f, "Aorta").normalised(), Region(0.7f, 0.1f, 0.9f, 0.2f))
        val decoded = Occlusion.decode(Occlusion(regions, target = 1).encode())!!
        assertEquals(1, decoded.target)
        assertEquals(0.1f, decoded.regions[0].left, 1e-6f)
        assertEquals(0.6f, decoded.regions[0].bottom, 1e-6f)
        assertEquals("Aorta", decoded.regions[0].label)
    }

    @Test
    fun `an unreadable or out of range mask decodes to nothing rather than crashing`() {
        assertNull(Occlusion.decode("not json"))
        assertNull(Occlusion.decode(Occlusion(listOf(Region(0f, 0f, 0.5f, 0.5f)), target = 4).encode()))
        assertNull(Occlusion.decode(null))
    }

    @Test
    fun `only names the image store produces are accepted from a backup`() {
        assertTrue(ImageNames.isSafe("3f2a9c1e-77b0-4c1e-9d0a-1b2c3d4e5f60.jpg"))
        assertFalse(ImageNames.isSafe("../databases/abhyas.db"))
        assertFalse(ImageNames.isSafe("../../x.jpg"))
        assertFalse(ImageNames.isSafe("a.jpg"))
    }

    // ---------------------------------------------------------------------------------- backup

    @Test
    fun `a backup carries tags, parents, picture cards and the pictures themselves`() {
        val image = "3f2a9c1e-77b0-4c1e-9d0a-1b2c3d4e5f60.jpg"
        val bytes = byteArrayOf(1, 2, 3, 127, -128, 0)
        val occlusion = Occlusion(listOf(Region(0.1f, 0.1f, 0.3f, 0.2f, "Atrium")), 0).encode()
        val backup = Backup(
            decks = listOf(
                DeckEntity(id = 1, name = "Biology", createdAt = 1, lastUsedAt = 1),
                DeckEntity(id = 2, name = "Chapter 4", createdAt = 1, lastUsedAt = 1, parentId = 1, script = ScriptOption.DEVANAGARI)
            ),
            cards = listOf(
                CardEntity(deckId = 2, front = "Heart", back = "Atrium", sourceText = null, createdAt = 5,
                    type = CardType.OCCLUSION, tags = "anatomy exam", imagePath = image, occlusion = occlusion)
            ),
            images = mapOf(image to bytes)
        )

        val decoded = BackupCodec.decode(BackupCodec.encode(backup))

        assertEquals(1L, decoded.decks[1].parentId)
        assertNull(decoded.decks[0].parentId)
        val restored = decoded.cards.single()
        assertEquals(CardType.OCCLUSION, restored.type)
        assertEquals("anatomy exam", restored.tags)
        assertEquals(image, restored.imagePath)
        assertEquals(occlusion, restored.occlusion)
        assertArrayEquals(bytes, decoded.images[image])
    }

    @Test
    fun `a picture card whose picture name is unsafe comes back as a plain card, not a blank one`() {
        val text = """
            {"format":2,"decks":[{"id":1,"name":"D"}],
             "cards":[{"deckId":1,"front":"Heart","back":"Atrium","type":"OCCLUSION",
                       "imagePath":"../databases/abhyas.db","occlusion":"{}"}],
             "images":{"../databases/abhyas.db":"AAAA"}}
        """.trimIndent()
        val decoded = BackupCodec.decode(text)
        assertEquals(CardType.BASIC, decoded.cards.single().type)
        assertNull(decoded.cards.single().imagePath)
        assertTrue(decoded.images.isEmpty())
    }

    @Test
    fun `a version-1 file with none of the new fields still restores`() {
        val text = """{"format":1,"decks":[{"id":7,"name":"Old"}],"cards":[{"deckId":7,"front":"Q","back":"A"}]}"""
        val decoded = BackupCodec.decode(text)
        assertEquals(CardType.BASIC, decoded.cards.single().type)
        assertEquals("", decoded.cards.single().tags)
        assertNull(decoded.decks.single().parentId)
    }

    // ------------------------------------------------------------------------------- deck tree

    private fun summary(id: Long, name: String, used: Long, parent: Long? = null) =
        DeckSummary(id, name, used, ScriptOption.LATIN, parent, 0, 0, 0)

    @Test
    fun `subdecks sit beneath their parent, and a subject is as recent as its newest chapter`() {
        val rows = DecksViewModel.arrange(
            listOf(
                summary(1, "Biology", used = 10),
                summary(2, "History", used = 50),
                summary(3, "Ch 2", used = 100, parent = 1),
                summary(4, "Ch 1", used = 5, parent = 1),
                summary(5, "Orphan", used = 1, parent = 99)
            )
        )
        assertEquals(listOf(1L, 4L, 3L, 2L, 5L), rows.map { it.deck.id })
        assertEquals(listOf(false, true, true, false, false), rows.map { it.isChild })
    }
}

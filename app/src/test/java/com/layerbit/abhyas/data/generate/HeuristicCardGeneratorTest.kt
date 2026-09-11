package com.layerbit.abhyas.data.generate

import com.layerbit.abhyas.data.ocr.PageBlock
import com.layerbit.abhyas.data.ocr.PageText
import com.layerbit.abhyas.data.ocr.TextBox
import com.layerbit.abhyas.data.ocr.ScriptProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card generation against realistic OCR output, in every script Abhyas supports.
 *
 * These are regression tests before they are anything else. Each case below is a bug that
 * actually shipped into the working tree, and every one of them failed *silently* - the app kept
 * producing cards, they were just the wrong cards, or a whole pass quietly stopped firing and
 * nothing said so. That is the failure mode this file exists to catch.
 *
 * Blocks are written the way ML Kit hands them over: wrapped lines, not sentences.
 */
class HeuristicCardGeneratorTest {

    private val generator = HeuristicCardGenerator()

    private fun cards(blocks: List<List<String>>, profile: ScriptProfile) =
        runBlocking { generator.generate(PageText.ofLines(blocks, profile)) }

    private fun cardsFrom(page: PageText) = runBlocking { generator.generate(page) }

    /**
     * A block laid out on a notional 1000px-wide page, one line 40px tall per line of text,
     * starting at [top]. Enough geometry for the stitching rules to be exercised honestly.
     */
    private fun block(top: Int, vararg lines: String, left: Int = 100, right: Int = 900) =
        PageBlock(
            lines = lines.toList(),
            box = TextBox(left, top, right, top + lines.size * LINE_HEIGHT)
        )

    private fun List<CardCandidate>.withFront(text: String) = firstOrNull { it.front == text }

    // ------------------------------------------------------------------------------------ Latin

    private val englishPage = listOf(
        listOf("Chapter 4: Photosynthesis"),
        listOf(
            "Photosynthesis is the process by which green plants",
            "and some other organisms use sunlight to synthesise",
            "foods from carbon dioxide and water.",
            "Chlorophyll: the green pigment found in the chloroplasts",
            "of plant cells.",
            "The process was first described by Jan Ingenhousz in 1779.",
            "Stomata are tiny pores on the surface of leaves."
        ),
        listOf(
            "Q1. Where does photosynthesis take place?",
            "Ans. In the chloroplasts of the leaf cells."
        )
    )

    @Test
    fun `wrapped OCR lines are rejoined into whole sentences`() {
        val page = PageText.ofLines(englishPage, ScriptProfile.Latin)

        assertTrue(
            "a paragraph split across three OCR lines must come back as one sentence",
            page.sentences().any {
                it.startsWith("Photosynthesis is the process") && it.endsWith("water.")
            }
        )
    }

    @Test
    fun `an explicit question and answer on the page becomes the highest-scoring card`() {
        // Regression: sentence splitting used to destroy the "Q1." and "Ans." labels before this
        // pass ever saw them, so the most reliable pass in the app never fired at all.
        val result = cards(englishPage, ScriptProfile.Latin)
        val qa = result.withFront("Where does photosynthesis take place?")

        assertNotNull("the labelled Q&A pair must produce a card", qa)
        assertEquals("In the chloroplasts of the leaf cells.", qa!!.back)
        assertEquals(CardKind.QA, qa.kind)
        assertEquals("it is the surest card on the page, so it sorts first", qa, result.first())
    }

    @Test
    fun `a singular subject does not get a plural question`() {
        // Regression: "Photosynthesis" ends in s, so guessing plurality from the noun produced
        // "What are Photosynthesis?". The sentence's own verb is the authority.
        val result = cards(englishPage, ScriptProfile.Latin)

        assertNotNull(result.withFront("What is Photosynthesis?"))
        assertNull(result.withFront("What are Photosynthesis?"))
    }

    @Test
    fun `a plural subject does get a plural question`() {
        assertNotNull(cards(englishPage, ScriptProfile.Latin).withFront("What are Stomata?"))
    }

    @Test
    fun `a past-tense sentence becomes a cloze rather than a definition`() {
        // Regression: "was" used to be treated as a defining verb, yielding "What is The
        // process?". Past tense is narrative, so these fall through to the cloze pass - which
        // produces the better card anyway, by blanking the date.
        val result = cards(englishPage, ScriptProfile.Latin)

        assertNull(result.withFront("What is The process?"))
        val cloze = result.firstOrNull { it.back == "1779" }
        assertNotNull("the date is the thing worth remembering in that sentence", cloze)
        assertEquals(CardKind.CLOZE, cloze!!.kind)
        assertTrue(cloze.front.contains("_____"))
    }

    @Test
    fun `a chapter heading is not a definition`() {
        // Regression: "Chapter 4: Photosynthesis" has the exact shape of a colon definition.
        val result = cards(englishPage, ScriptProfile.Latin)

        assertNull(result.withFront("What is Chapter 4?"))
    }

    @Test
    fun `a colon definition of ordinary length is kept`() {
        // Regression: measuring the minimum answer length in words rather than characters
        // rejected almost every real definition, and every page came back as clozes only.
        val chlorophyll = cards(englishPage, ScriptProfile.Latin).withFront("What is Chlorophyll?")

        assertNotNull(chlorophyll)
        assertEquals(
            "the green pigment found in the chloroplasts of plant cells",
            chlorophyll!!.back
        )
    }

    @Test
    fun `a lead-in colon is not mistaken for a definition`() {
        val result = cards(
            listOf(listOf("There are three main factors: light, temperature and carbon dioxide.")),
            ScriptProfile.Latin
        )

        assertTrue(
            "\"There are three reasons:\" is a lead-in, not a definition",
            result.none { it.kind == CardKind.DEFINITION }
        )
    }

    @Test
    fun `a cloze never blanks a word that appears twice in the sentence`() {
        val result = cards(
            listOf(listOf("The cell wall protects the cell from mechanical damage always.")),
            ScriptProfile.Latin
        )

        assertTrue(
            "blanking a repeated word would leave the answer visible in the question",
            result.none { it.kind == CardKind.CLOZE && it.back == "cell" }
        )
    }

    // ------------------------------------------------------------------------------- Devanagari

    private val hindiPage = listOf(
        listOf("अध्याय 3: प्रकाश संश्लेषण"),
        listOf(
            "प्रकाश संश्लेषण: वह प्रक्रिया जिसके द्वारा हरे पौधे",
            "सूर्य के प्रकाश से अपना भोजन बनाते हैं।",
            "पर्णहरित का अर्थ है पत्तियों में पाया जाने वाला हरा वर्णक।",
            "यह प्रक्रिया सन् 1779 में खोजी गई थी।"
        ),
        listOf(
            "प्रश्न 1. प्रकाश संश्लेषण कहाँ होता है?",
            "उत्तर. पत्तियों की कोशिकाओं के हरितलवक में।"
        )
    )

    @Test
    fun `Hindi sentences split on the danda`() {
        val page = PageText.ofLines(hindiPage, ScriptProfile.Devanagari)

        assertTrue(
            "a full stop is not how Hindi ends a sentence",
            page.sentences().any { it.endsWith("बनाते हैं।") }
        )
    }

    @Test
    fun `a Hindi colon definition is asked in Hindi`() {
        val result = cards(hindiPage, ScriptProfile.Devanagari)

        assertNotNull(
            "the question has to be worded in the language of the page",
            result.withFront("प्रकाश संश्लेषण क्या है?")
        )
    }

    @Test
    fun `Hindi's means pattern produces a definition`() {
        // Copula inversion is off for Devanagari because Hindi puts its verb at the end of the
        // clause. This fixed phrase is the exception that carries real Hindi definitions.
        val meaning = cards(hindiPage, ScriptProfile.Devanagari).withFront("पर्णहरित क्या है?")

        assertNotNull(meaning)
        assertEquals(CardKind.DEFINITION, meaning!!.kind)
    }

    @Test
    fun `a Hindi question and answer pair is recognised`() {
        val qa = cards(hindiPage, ScriptProfile.Devanagari)
            .firstOrNull { it.kind == CardKind.QA }

        assertNotNull("प्रश्न and उत्तर label a worked exercise just as Q and Ans do", qa)
        assertTrue(qa!!.front.contains("प्रकाश संश्लेषण कहाँ"))
    }

    @Test
    fun `a Hindi chapter heading is not a definition`() {
        assertNull(cards(hindiPage, ScriptProfile.Devanagari).withFront("अध्याय 3 क्या है?"))
    }

    @Test
    fun `Hindi paragraphs still join across wrapped lines`() {
        // Regression: Devanagari has no capital letters, so the Latin heading test - short and
        // unterminated - matched ordinary wrapped body lines and stopped paragraphs joining.
        val page = PageText.ofLines(hindiPage, ScriptProfile.Devanagari)

        assertTrue(
            page.runs().any { it.contains("हरे पौधे सूर्य के प्रकाश") }
        )
    }

    // -------------------------------------------------------------------------------------- CJK

    private val chinesePage = listOf(
        listOf("第三章：光合作用"),
        listOf(
            "光合作用：绿色植物利用阳光制造养分的过程。",
            "这个过程在1779年首次被描述。",
            "叶绿体：植物细胞中进行光合作用的细胞器。"
        )
    )

    @Test
    fun `a Chinese colon definition is asked in Chinese`() {
        val result = cards(chinesePage, ScriptProfile.Cjk.Chinese)

        assertNotNull(result.withFront("什么是光合作用？"))
        assertNotNull(result.withFront("什么是叶绿体？"))
    }

    @Test
    fun `a Chinese chapter heading is not a definition`() {
        assertNull(cards(chinesePage, ScriptProfile.Cjk.Chinese).withFront("什么是第三章？"))
    }

    @Test
    fun `CJK clozes fall back to digits rather than guessing at word boundaries`() {
        val clozes = cards(chinesePage, ScriptProfile.Cjk.Chinese)
            .filter { it.kind == CardKind.CLOZE }

        assertTrue(
            "without word segmentation, a guessed term would cut through the middle of a word",
            clozes.all { it.back.any(Char::isDigit) }
        )
    }

    @Test
    fun `CJK lines join without inserting spaces`() {
        val page = PageText.ofLines(
            listOf(listOf("光合作用是绿色植物", "利用阳光制造养分的过程。")),
            ScriptProfile.Cjk.Chinese
        )

        assertTrue(
            "a space at every wrapped line would open a gap mid-word",
            page.runs().any { it == "光合作用是绿色植物利用阳光制造养分的过程。" }
        )
    }

    // ------------------------------------------------ regressions from a real photographed page

    /**
     * The notepad page from the first real test on a phone, with the blocks in the order ML Kit
     * actually returned them: each line its own block, and the mitochondrion line landing
     * between the question and its answer.
     *
     * PageTextReader now sorts blocks by position before this point, but the generator is tested
     * against the unsorted order on purpose - OCR reading order is never a guarantee, and these
     * cards have to come out right either way.
     */
    private val photographedPage = PageText(
        listOf(
            block(100, "Photosynthesis: the process by which green plants"),
            block(140, "make their own food using sunlight."),
            block(220, "Chlorophyll: the green pigment found in leaves."),
            block(300, "Q1. Where does photosynthesis take place?"),
            // Sits between the question and its answer on the page, exactly as it did in the
            // photograph that exposed this.
            block(380, "The mitochondrion is the powerhouse of the cell."),
            block(460, "Ans. In the chloroplasts of the leaf cells.")
        ),
        ScriptProfile.Latin
    )

    @Test
    fun `a question is paired with its labelled answer, not with whatever block came next`() {
        // Shipped bug: this card asked where photosynthesis takes place and answered "The
        // mitochondrion is the powerhouse of the cell", because that block was next in the list.
        val qa = cardsFrom(photographedPage).first { it.kind == CardKind.QA }

        assertEquals("Where does photosynthesis take place?", qa.front)
        assertEquals("In the chloroplasts of the leaf cells.", qa.back)
    }

    @Test
    fun `a paragraph split across blocks is rejoined before it is turned into a card`() {
        // Shipped bug: the answer stopped at "green plants" because the rest of the sentence was
        // in a different ML Kit block and joining never crossed one.
        val definition = cardsFrom(photographedPage).first { it.front == "What is Photosynthesis?" }

        assertEquals(
            "the process by which green plants make their own food using sunlight",
            definition.back
        )
    }

    @Test
    fun `a line stolen as a wrong answer is still available as its own card`() {
        // The mitochondrion line was consumed as a bogus answer, so its own good card vanished.
        val mitochondrion = cardsFrom(photographedPage)
            .firstOrNull { it.front == "What is The mitochondrion?" }

        assertNotNull("the page defines it, so it should be offered", mitochondrion)
        assertEquals("the powerhouse of the cell", mitochondrion!!.back)
    }

    @Test
    fun `a menu bar is never stitched onto a sentence it merely sits near`() {
        // Shipped bug, from photographing the page in a text editor on screen. The window's menu
        // bar was welded onto a continuation line, producing the card
        // "File _____ View make their own food using sunlight." - and eating the real sentence,
        // which stayed truncated at "green plants".
        val onScreen = PageText(
            listOf(
                block(20, "File Edit Format View Help", left = 0, right = 400),
                block(100, "Photosynthesis: the process by which green plants"),
                block(140, "make their own food using sunlight.")
            ),
            ScriptProfile.Latin
        )

        val result = cardsFrom(onScreen)

        assertTrue(
            "nothing should mention the menu bar",
            result.none { it.front.contains("File") || it.back.contains("File") }
        )
        assertEquals(
            "and the real sentence must still be whole",
            "the process by which green plants make their own food using sunlight",
            result.first { it.front == "What is Photosynthesis?" }.back
        )
    }

    @Test
    fun `a blank line between paragraphs stops the stitch`() {
        // Two lines that would read as a continuation, but with a paragraph break between them.
        val separated = PageText(
            listOf(
                block(100, "Osmosis is the movement of water across a membrane"),
                block(260, "made of protein and fat in every living cell.")
            ),
            ScriptProfile.Latin
        )

        assertTrue(
            "a gap that wide is a new paragraph, whatever the words suggest",
            cardsFrom(separated).none { it.back.contains("made of protein") }
        )
    }

    @Test
    fun `a block in another column is not stitched on`() {
        val twoColumn = PageText(
            listOf(
                block(100, "Osmosis is the movement of water across a membrane",
                    left = 60, right = 460),
                block(140, "made of protein and fat in every living cell.",
                    left = 540, right = 940)
            ),
            ScriptProfile.Latin
        )

        assertTrue(
            "vertically adjacent is not enough - it has to be the same column",
            cardsFrom(twoColumn).none { it.back.contains("made of protein") }
        )
    }

    @Test
    fun `a subordinate clause is not a definition`() {
        // "If that bites, document-boundary detection is the obvious next feature" became
        // "What is If that bites, document-boundary detection?".
        val result = cards(
            listOf(listOf("If that bites, document-boundary detection is the obvious next feature.")),
            ScriptProfile.Latin
        )

        assertTrue(
            "a condition is not a definition",
            result.none { it.kind == CardKind.DEFINITION }
        )
    }

    @Test
    fun `The plus a generic noun is not a definable term`() {
        // "The generator is heuristic, so tell me..." became "What is The generator?" - the
        // reader has to already know which generator for the question to mean anything.
        val result = cards(
            listOf(listOf("The generator is heuristic and runs entirely on the device.")),
            ScriptProfile.Latin
        )

        assertNull(result.withFront("What is The generator?"))
    }

    @Test
    fun `a real noun behind The is still definable`() {
        // The guard above must not swallow "The mitochondrion is the powerhouse of the cell."
        val result = cards(
            listOf(listOf("The mitochondrion is the powerhouse of the cell.")),
            ScriptProfile.Latin
        )

        assertNotNull(result.withFront("What is The mitochondrion?"))
    }

    // ------------------------------------------------------------------------------------ shared

    @Test
    fun `an empty page produces nothing rather than failing`() {
        assertTrue(cards(emptyList(), ScriptProfile.Latin).isEmpty())
    }

    @Test
    fun `suggestions are capped so a dense page cannot flood the review screen`() {
        val dense = (1..40).map { "Term$it: a definition long enough to be worth keeping here." }
        val result = cards(listOf(dense), ScriptProfile.Latin)

        assertTrue("forty suggestions is a screen people close", result.size <= 12)
    }

    @Test
    fun `one sentence yields at most one card`() {
        // A colon definition is usually also a fine cloze; the user should be offered the better
        // reading, not asked to choose between two versions of the same fact.
        val result = cards(
            listOf(listOf("Osmosis: the movement of water across a semi-permeable membrane.")),
            ScriptProfile.Latin
        )

        assertEquals(1, result.size)
        assertEquals(CardKind.DEFINITION, result.first().kind)
    }

    private companion object {
        /** Nominal height of one line of text in the synthetic page layouts above. */
        const val LINE_HEIGHT = 40
    }
}

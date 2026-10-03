package com.layerbit.abhyas.data.generate

import com.layerbit.abhyas.data.ocr.PageBlock
import com.layerbit.abhyas.data.ocr.PageText
import com.layerbit.abhyas.data.ocr.TextBox
import com.layerbit.abhyas.data.ocr.ScriptProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertNull(result.withFront("What is the process?"))
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
            .firstOrNull { it.front == "What is the mitochondrion?" }

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
        // "The generator is heuristic, so tell me..." became "What is the generator?" - the
        // reader has to already know which generator for the question to mean anything.
        val result = cards(
            listOf(listOf("The generator is heuristic and runs entirely on the device.")),
            ScriptProfile.Latin
        )

        assertNull(result.withFront("What is the generator?"))
    }

    @Test
    fun `a real noun behind The is still definable`() {
        // The guard above must not swallow "The mitochondrion is the powerhouse of the cell."
        val result = cards(
            listOf(listOf("The mitochondrion is the powerhouse of the cell.")),
            ScriptProfile.Latin
        )

        assertNotNull(result.withFront("What is the mitochondrion?"))
    }

    @Test
    fun `an article does not carry its sentence capital into the question`() {
        // Shipped bug, seen on a photographed page of physics notes: the term only has a capital
        // because it started the sentence it was lifted from, and "What is The focal length?"
        // reads mid-question as a typo. An article is never a proper noun, so it is safe to lower.
        val result = cards(
            listOf(listOf("The focal length is the distance from the lens to its focus.")),
            ScriptProfile.Latin
        )

        assertNotNull(result.withFront("What is the focal length?"))
        assertNull(result.withFront("What is The focal length?"))
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

    // ------------------------------------------------------------- the second audit's findings

    @Test
    fun `an imperative exercise does not keep its full stop in front of the question mark`() {
        // Textbook exercises are overwhelmingly imperative, and the old code only looked for an
        // existing question mark before concatenating - so the commonest shape on the page shipped
        // as "Define osmosis.?".
        val result = cards(
            listOf(
                listOf(
                    "Q2. Define osmosis.",
                    "Ans. The movement of water across a semi-permeable membrane."
                )
            ),
            ScriptProfile.Latin
        )

        assertNotNull("the exercise should produce a card", result.withFront("Define osmosis?"))
    }

    @Test
    fun `a spelled-out Answer label is stripped whole rather than three letters deep`() {
        // "Ans\.?\s*[:.]?" had both terminators optional, so it matched the bare "Ans" of "Answer:"
        // and the card shipped as "wer: the green pigment..." at the top confidence.
        val result = cards(
            listOf(
                listOf(
                    "Question 1. Which pigment absorbs sunlight?",
                    "Answer: the green pigment found in the leaves of the plant."
                )
            ),
            ScriptProfile.Latin
        )

        val card = result.withFront("Which pigment absorbs sunlight?")
        assertNotNull("a spelled-out Question label should be recognised", card)
        assertEquals("the green pigment found in the leaves of the plant.", card!!.back)
    }

    @Test
    fun `a word merely beginning with the answer label is not treated as a label`() {
        val result = cards(
            listOf(
                listOf(
                    "Q1. Who first described photosynthesis?",
                    "Answered by Jan Ingenhousz, working in Austria during 1779."
                )
            ),
            ScriptProfile.Latin
        )

        // The run is taken as an unlabelled answer, which is fine - what must not happen is the
        // label being stripped out of the middle of the word, leaving "ed by Jan Ingenhousz".
        val card = result.withFront("Who first described photosynthesis?")
        assertNotNull("the question should still pair with the run beneath it", card)
        assertTrue(
            "the answer must not be chopped mid-word: ${card!!.back}",
            card.back.startsWith("Answered")
        )
    }

    @Test
    fun `a Devanagari word beginning with the answer label keeps its first letter`() {
        // "उत्तरी" ("northern") begins with "उत्तर" ("answer"), and there is no case to fall back on.
        val result = cards(
            listOf(
                listOf(
                    "प्रश्न 1. प्रकाश संश्लेषण कहाँ होता है?",
                    "उत्तरी भारत में यह प्रक्रिया गर्मियों में तेज़ होती है।"
                )
            ),
            ScriptProfile.Devanagari
        )

        val card = result.withFront("प्रकाश संश्लेषण कहाँ होता है?")
        assertNotNull("the Hindi exercise should still produce a card", card)
        assertTrue(
            "an answer must not begin mid-word: ${card!!.back}",
            card.back.startsWith("उत्तरी")
        )
    }

    @Test
    fun `two questions never share one answer`() {
        // The lookahead skipped runs already claimed; the fallback did not, so it landed back on the
        // answer the first question had taken - and because that run is still labelled, both guards
        // protecting the fallback were bypassed and the duplicate shipped at 0.98.
        val result = cards(
            listOf(
                listOf(
                    "Q1. Where does photosynthesis take place?",
                    "Q2. Which pigment absorbs sunlight?",
                    "Ans. In the chloroplasts of the leaf cells."
                )
            ),
            ScriptProfile.Latin
        )

        val qa = result.filter { it.kind == CardKind.QA }
        assertEquals("one labelled answer can only answer one question", 1, qa.size)
    }

    @Test
    fun `a bulleted definition does not carry its bullet into the question`() {
        val result = cards(
            listOf(listOf("• Chlorophyll: the green pigment found in the leaves of plants.")),
            ScriptProfile.Latin
        )

        assertNotNull("the bullet belongs to the layout, not the term", result.withFront("What is Chlorophyll?"))
    }

    @Test
    fun `a bullet does not blind the generic-noun guard`() {
        // Every guard in these passes is anchored at the start of the string, so a leading bullet
        // walked straight past isDefiniteGeneric's startsWith("the ") test.
        val result = cards(
            listOf(listOf("• The generator is heuristic and runs entirely on the device.")),
            ScriptProfile.Latin
        )

        assertNull(
            "\"The generator\" is a generic noun phrase, bulleted or not",
            result.withFront("What is The generator?")
        )
    }

    @Test
    fun `a number joined to a function word is not blanked as a cloze`() {
        // "\d+\s?[a-zA-Z]{1,4}" matched a number plus any short word, and this branch is tried
        // before the salient term - so "2 to" pre-empted the better candidate as well as being wrong.
        val result = cards(
            listOf(listOf("The process of germination usually takes 2 to 3 hours to complete.")),
            ScriptProfile.Latin
        )

        result.filter { it.kind == CardKind.CLOZE }.forEach {
            assertTrue("\"${it.back}\" is not a fact worth blanking", it.back.trim() != "2 to")
        }
    }

    @Test
    fun `a real unit is blanked, which the old pattern could not match at all`() {
        // [a-zA-Z]{1,4} cannot reach a word boundary inside "hours", so the exact case the numeric
        // branch was written for never matched.
        val result = cards(
            listOf(listOf("Water held at this temperature will pasteurise fully within 3 hours.")),
            ScriptProfile.Latin
        )

        assertTrue(
            "a measured quantity is the one thing worth blanking in that sentence",
            result.any { it.kind == CardKind.CLOZE && it.back.contains("3 hours") }
        )
    }

    @Test
    fun `a term on its own line is joined to the definition beneath it`() {
        // A colon is in every profile's sentenceEnders, so "Chlorophyll:" was left as a run of its
        // own, colonDefinition saw an empty definition, and the commonest glossary layout on a page
        // produced no card whatsoever.
        val page = PageText(
            blocks = listOf(
                block(100, "Chlorophyll:"),
                block(140, "the green pigment found in the chloroplasts of plant cells.")
            ),
            profile = ScriptProfile.Latin
        )

        val card = cardsFrom(page).withFront("What is Chlorophyll?")
        assertNotNull("a term and the line below it are one definition", card)
        assertTrue("the whole definition should survive: ${card!!.back}", card.back.contains("chloroplasts"))
    }

    @Test
    fun `an exercise heading is not joined to the question beneath it`() {
        // The other side of the same fix: "Exercise 1:" must stay separate, or the "Q1." label is
        // swallowed into the middle of a run and the Q&A pass stops seeing it.
        val page = PageText(
            blocks = listOf(
                block(100, "Exercise 1:"),
                block(140, "Q1. Where does photosynthesis take place?"),
                block(180, "Ans. In the chloroplasts of the leaf cells.")
            ),
            profile = ScriptProfile.Latin
        )

        assertNotNull(
            "the exercise label must not swallow the question label",
            cardsFrom(page).withFront("Where does photosynthesis take place?")
        )
    }

    @Test
    fun `a paragraph broken into four blocks is stitched whole`() {
        // The stitched box was never advanced, so each gap was measured from the top of the chain and
        // grew by the height of every block already joined, while the budget stayed one line. Three
        // blocks survived; the fourth was always rejected.
        val page = PageText(
            blocks = listOf(
                block(100, "Photosynthesis is the process by which green plants and"),
                block(140, "some other organisms convert light energy into the"),
                block(180, "chemical energy that is later released to fuel the"),
                block(220, "activities of the organism itself.")
            ),
            profile = ScriptProfile.Latin
        )

        val runs = page.runs()
        assertEquals("all four blocks are one paragraph", 1, runs.size)
        assertTrue("the last block must not be dropped: ${runs.first()}", runs.first().endsWith("itself."))
    }

    @Test
    fun `a Hindi paragraph split across blocks is stitched whole`() {
        // Cross-block stitching required wordSpaced and a lowercase first letter. Devanagari
        // consonants are OTHER_LETTER, for which isLowerCase() is false, so no Hindi continuation
        // ever passed - and for CJK the wordSpaced gate rejected every one outright.
        val page = PageText(
            blocks = listOf(
                block(100, "प्रकाश संश्लेषण वह प्रक्रिया है जिसके द्वारा हरे पौधे"),
                block(140, "सूर्य के प्रकाश से अपना भोजन स्वयं बनाते हैं।")
            ),
            profile = ScriptProfile.Devanagari
        )

        val runs = page.runs()
        assertEquals("both blocks are one sentence", 1, runs.size)
        assertTrue("the continuation must survive: ${runs.first()}", runs.first().contains("भोजन"))
    }

    @Test
    fun `a Hindi cloze blanks a whole word rather than cutting off its vowel sign`() {
        // Found on a phone. A Devanagari vowel sign is a combining mark, not a letter, so trimming a
        // token with `!isLetterOrDigit()` cut the `ा` off `प्रक्रिया` and left the stem `प्रक्रिय`.
        // The blank was then made from the stem, stranding the sign in the question: the card read
        // "यह _____ा गर्मियों में" and offered a non-word as its answer. Most Hindi words end in one
        // of these signs, so this was most Hindi clozes.
        val result = cards(
            listOf(listOf("उत्तरी भारत में यह प्रक्रिया गर्मियों के महीनों में तेज़ होती है।")),
            ScriptProfile.Devanagari
        )

        val cloze = result.firstOrNull { it.kind == CardKind.CLOZE }
        assertNotNull("the sentence should still yield a cloze", cloze)
        assertFalse(
            "the blank must not strand a vowel sign: ${cloze!!.front}",
            cloze.front.contains("_ा") || cloze.front.contains("_ि") || cloze.front.contains("_ी")
        )
        assertTrue(
            "the answer must be a whole word, was \"${cloze.back}\"",
            cloze.back == "प्रक्रिया" || !"उत्तरी भारत में यह प्रक्रिया".contains(cloze.back + "ा")
        )
    }

    @Test
    fun `a Hindi answer label is not stripped from a word that merely starts with it`() {
        // The companion to the above, also confirmed on the phone: "उत्तरी" ("northern") begins with
        // "उत्तर" ("answer"), and Devanagari has no case to tell them apart.
        val result = cards(
            listOf(listOf("उत्तरी भारत में यह प्रक्रिया गर्मियों के महीनों में तेज़ होती है।")),
            ScriptProfile.Devanagari
        )

        result.forEach {
            assertFalse(
                "nothing may begin mid-word: \"${it.front}\"",
                it.front.startsWith("ी") || it.back.startsWith("ी")
            )
        }
    }

    @Test
    fun `a Chinese decimal is not split into two sentences`() {
        // The ASCII full stop sat in a zero-width lookbehind, and Pattern.split only skips a
        // zero-width match at offset zero - so every decimal point split the sentence, and both
        // halves were long enough to go on and become cards.
        val page = PageText.ofLines(
            listOf(listOf("水的密度约为1.0克每立方厘米，这是一个重要的物理常数。")),
            ScriptProfile.Cjk.Chinese
        )

        page.runs().forEach { run ->
            page.sentencesOf(run).forEach { sentence ->
                assertTrue("a sentence must not begin mid-number: $sentence", !sentence.startsWith("0克"))
            }
        }
    }

    private companion object {
        /** Nominal height of one line of text in the synthetic page layouts above. */
        const val LINE_HEIGHT = 40
    }
}

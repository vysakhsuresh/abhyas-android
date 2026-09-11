package com.layerbit.abhyas.data.ocr

/**
 * Everything about a writing system that the text pipeline has to know.
 *
 * Sentence splitting, definition patterns and question wording are all language-specific, and
 * the first version of Abhyas hard-coded English into every one of them. Running a Devanagari
 * recogniser without this would produce cards, they would simply all be wrong: Hindi ends
 * sentences with a danda rather than a full stop, has no capital letters to find proper nouns
 * with, and puts its copula at the end of the clause instead of the middle.
 *
 * What each script actually supports is deliberately uneven, and that is honest rather than
 * lazy. Latin gets the full set. Devanagari gets sentence splitting, colon definitions, a real
 * Hindi "means" pattern and clozes. CJK gets sentence splitting, colon definitions and numeric
 * clozes only - picking out a key term needs word segmentation that none of these languages
 * give you from whitespace, and guessing would produce clozes cut through the middle of words.
 */
sealed class ScriptProfile(val script: ScriptOption) {

    /** Characters that finish a sentence in this script. */
    abstract val sentenceEnders: String

    /** Where one sentence ends and the next begins, inside a joined run of text. */
    abstract val sentenceBoundary: Regex

    /** False for scripts that do not put spaces between words - CJK. */
    open val wordSpaced: Boolean = true

    /**
     * Shortest run worth making a card from. CJK is far denser per character than an alphabet,
     * so the same threshold would throw away perfectly good sentences.
     */
    open val minSentenceLength: Int = 12

    /** Inverting "X is Y" into "What is X?". Null where the grammar does not allow it. */
    open val copula: Regex? = null

    /** How a worked exercise labels its question and its answer on the page. */
    open val questionPrefix: Regex =
        Regex("""^\s*(Q\s*\d*\s*[.):]|\d{1,2}\s*[.)])\s*""", RegexOption.IGNORE_CASE)
    open val answerPrefix: Regex =
        Regex("""^\s*(A\s*\d*\s*[.):]|Ans\.?\s*[:.]?)\s*""", RegexOption.IGNORE_CASE)

    /** Sentence openers that mean "a list follows", not "here is a definition". */
    open val leadInStarts: List<String> = emptyList()

    /** Sentence openers that define nothing without their antecedent. */
    open val pronounStarts: List<String> = emptyList()

    /** Words never worth blanking out in a cloze. */
    open val stopwords: Set<String> = emptySet()

    // --- Size limits, all measured in [units] so one set of numbers works for every script. ---

    /**
     * Longest a defined term may be before it stops being a term and starts being a claim.
     * "The mitochondrion" is a term; half a sentence is not.
     */
    open val maxTermUnits: Int = 6

    /**
     * Shortest a definition or answer may be and still be worth putting on a card, in
     * **characters**.
     *
     * Characters rather than units, and that distinction is load-bearing. Measuring it in words
     * rejects almost every real definition - "the green pigment found in the chloroplasts of
     * plant cells" is ten words - and the failure is silent: the definition passes simply stop
     * firing and every page comes back as nothing but clozes.
     */
    open val minAnswerChars: Int = 15

    /** Shortest a question from the page may be before it is a stray label, in characters. */
    open val minQuestionChars: Int = 10

    /**
     * A term that is really a chapter or section heading. "Chapter 4: Photosynthesis" has the
     * exact shape of a colon definition and is not one.
     *
     * A regex rather than a prefix list because CJK writes no space after the marker, so the
     * word-boundary test used for [leadInStarts] would never match there.
     */
    open val headingPrefix: Regex? = null

    /** A sentence outside this range is either too thin to test or too long to remember. */
    open val clozeRange: IntRange = 6..34

    /**
     * How long a piece of text is, in whatever unit this script counts in: words where words are
     * separated by spaces, characters where they are not. Without this every limit above would
     * have to exist twice, and the CJK half would inevitably drift out of step.
     */
    fun units(text: String): Int =
        if (wordSpaced) text.split(WHITESPACE).count { it.isNotBlank() } else text.length

    /** The question that asks for [term]. [plural] is null when the grammar has nothing to read. */
    abstract fun definitionQuestion(term: String, plural: Boolean? = null): String

    /**
     * The word in this sentence most worth blanking. Null means this script has no dependable
     * way to pick one, and the cloze pass should fall back to numbers and dates alone.
     */
    open fun salientTerm(sentence: String): String? = null

    /** A line that opens a new thought rather than continuing the previous one. */
    open fun startsNewThought(line: String): Boolean {
        val first = line.firstOrNull() ?: return false
        return first.isUpperCase() && line.length < HEADING_MAX_LENGTH &&
            line.trimEnd().lastOrNull()?.let { it in sentenceEnders } != true
    }

    // ------------------------------------------------------------------------------------ Latin

    object Latin : ScriptProfile(ScriptOption.LATIN) {
        override val sentenceEnders = ".?!:;"
        override val sentenceBoundary = Regex("""(?<=[.?!])\s+(?=[A-Z])""")

        override val copula = Regex(
            """\s+(is defined as|are defined as|is known as|are known as|is called|are called|refers to|refer to|means|is|are)\s+"""
        )

        override val leadInStarts = listOf(
            "there", "these", "those", "the following", "following", "note", "example",
            "examples", "for example", "steps", "reasons", "types", "kinds"
        )

        override val headingPrefix = Regex(
            """^(chapter|unit|lesson|section|part|exercise|topic|module)\b""",
            RegexOption.IGNORE_CASE
        )

        override val pronounStarts = listOf(
            "it", "this", "that", "they", "these", "those", "there", "he", "she", "we", "you",
            "which", "who", "what", "here"
        )

        override val stopwords = setOf(
            "the", "this", "that", "these", "those", "there", "then", "thus", "hence",
            "however", "therefore", "moreover", "although", "because", "since", "while",
            "when", "where", "which", "what", "who", "whom", "whose", "with", "without",
            "from", "into", "onto", "upon", "about", "after", "before", "during", "under",
            "over", "between", "among", "also", "such", "some", "many", "most", "more",
            "less", "than", "they", "them", "their", "have", "has", "had", "been", "being",
            "mr", "mrs", "dr", "prof", "fig", "figure", "table", "chapter", "page", "note"
        )

        /**
         * -sis, -is, -us, -ss and -ous all end in s without being plural, which is what stops
         * "Photosynthesis is..." turning into "What are Photosynthesis?".
         */
        private val notActuallyPlural = listOf("ss", "us", "is", "sis", "ous")

        override fun definitionQuestion(term: String, plural: Boolean?): String {
            val looksPlural = plural
                ?: (term.endsWith("s") && notActuallyPlural.none { term.lowercase().endsWith(it) })
            return if (looksPlural) "What are $term?" else "What is $term?"
        }

        /**
         * A capital letter mid-sentence is the best proxy an alphabet gives for a proper noun or
         * a technical term. Ties break towards the longer word.
         */
        override fun salientTerm(sentence: String): String? =
            sentence.split(WHITESPACE)
                .drop(1)
                .map { it.trim { c -> !c.isLetterOrDigit() } }
                .filter { it.length >= 4 && it.first().isUpperCase() }
                .filterNot { it.lowercase() in stopwords }
                .maxByOrNull { it.length }
    }

    // ------------------------------------------------------------------------------- Devanagari

    object Devanagari : ScriptProfile(ScriptOption.DEVANAGARI) {
        /** The danda and double danda, plus Latin terminators for mixed pages. */
        override val sentenceEnders = "।॥.?!:;"
        override val sentenceBoundary = Regex("""(?<=[।॥.?!])\s+(?=[ऀ-ॿA-Z])""")

        /**
         * Hindi is subject-object-verb, so its copula sits at the end of the clause rather than
         * between the two halves. Splitting a sentence on "है" would put the whole definition on
         * the left and nothing on the right - the Latin trick simply does not transfer, so
         * copula inversion is off and colon definitions carry this script instead.
         */
        override val copula: Regex? = null

        /**
         * "X ka arth hai Y" / "X ka matlab hai Y" - X means Y. This one *is* dependable, because
         * the phrase is fixed and the definition genuinely follows it.
         */
        val meansPattern = Regex("""\s+(का अर्थ है|का मतलब है|की परिभाषा है)\s+""")

        override val questionPrefix = Regex(
            """^\s*(प्रश्न\s*\d*\s*[.):]?|प्र\s*\d*\s*[.):]|Q\s*\d*\s*[.):]|\d{1,2}\s*[.)])\s*""",
            RegexOption.IGNORE_CASE
        )
        override val answerPrefix = Regex(
            """^\s*(उत्तर\s*\d*\s*[.):]?|उ\s*\d*\s*[.):]|A\s*\d*\s*[.):]|Ans\.?\s*[:.]?)\s*""",
            RegexOption.IGNORE_CASE
        )

        override val leadInStarts = listOf("निम्नलिखित", "उदाहरण", "जैसे", "नोट", "इनमें")

        override val headingPrefix = Regex("""^(अध्याय|पाठ|इकाई|खंड|भाग|अभ्यास)""")

        override val pronounStarts = listOf("यह", "वह", "ये", "वे", "इस", "उस", "जो", "यहाँ")

        override val stopwords = setOf(
            "है", "हैं", "था", "थे", "थी", "का", "की", "के", "को", "में", "से", "पर",
            "और", "या", "एक", "यह", "वह", "जो", "कि", "तथा", "एवं", "आदि", "लिए",
            "द्वारा", "हुआ", "होता", "होती", "होते", "करने", "वाला", "साथ", "तक", "भी"
        )

        /** "X kya hai?" - What is X? No number agreement to get wrong. */
        override fun definitionQuestion(term: String, plural: Boolean?): String = "$term क्या है?"

        /**
         * Devanagari has no capital letters, so the Latin trick is unavailable. The longest
         * non-stopword token is a cruder signal but a workable one, since Hindi's grammatical
         * words are short and its technical vocabulary is not.
         */
        override fun salientTerm(sentence: String): String? =
            sentence.split(WHITESPACE)
                .map { it.trim { c -> !c.isLetterOrDigit() } }
                .filter { it.length >= 4 }
                .filterNot { it in stopwords }
                .maxByOrNull { it.length }

        /**
         * No capitals to detect a heading with, so length and terminator are all there is - and
         * the threshold has to be far tighter than the Latin one. At 60 characters this matched
         * ordinary wrapped body lines, so Hindi paragraphs stopped joining and every generator
         * downstream saw fragments instead of sentences. A real Devanagari heading is short.
         */
        override fun startsNewThought(line: String): Boolean =
            line.length < DEVANAGARI_HEADING_MAX_LENGTH &&
                line.trimEnd().lastOrNull()?.let { it in sentenceEnders } != true
    }

    // -------------------------------------------------------------------------------------- CJK

    /**
     * Chinese, Japanese and Korean share their punctuation and their lack of spaces between
     * words. Only the wording of a generated question differs, so it is the one thing subclasses
     * override.
     */
    sealed class Cjk(script: ScriptOption) : ScriptProfile(script) {
        override val sentenceEnders = "。？！；：.?!:;"
        override val sentenceBoundary = Regex("""(?<=[。？！.?!])\s*""")
        override val wordSpaced = false

        /** A CJK character carries far more meaning than a letter, so the floor is much lower. */
        override val minSentenceLength = 6

        // Term length and cloze range count characters here rather than words, hence the
        // different magnitudes - a CJK character carries roughly a word's worth of meaning.
        override val maxTermUnits = 12
        override val minAnswerChars = 6
        override val minQuestionChars = 5
        override val clozeRange = 8..60

        override val headingPrefix = Regex("""^(第|제\d|챕터)""")

        override val questionPrefix = Regex(
            """^\s*(問\s*\d*\s*[.):：、]?|问\s*\d*\s*[.):：、]?|문제\s*\d*\s*[.):]?|Q\s*\d*\s*[.):]|\d{1,2}\s*[.)])\s*""",
            RegexOption.IGNORE_CASE
        )
        override val answerPrefix = Regex(
            """^\s*(答\s*\d*\s*[.):：、]?|정답\s*[.):]?|답\s*[.):]?|A\s*\d*\s*[.):]|Ans\.?\s*[:.]?)\s*""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Deliberately null. Choosing a term to blank needs word segmentation, and these scripts
         * do not mark word boundaries with spaces. A cloze cut at the wrong character is not a
         * harder card, it is a broken one - so the cloze pass falls back to numbers and dates,
         * which are unambiguous in any script.
         */
        override fun salientTerm(sentence: String): String? = null

        /** No letter case, so a heading is recognised by brevity alone. */
        override fun startsNewThought(line: String): Boolean =
            line.length < CJK_HEADING_MAX_LENGTH &&
                line.trimEnd().lastOrNull()?.let { it in sentenceEnders } != true

        object Chinese : Cjk(ScriptOption.CHINESE) {
            override fun definitionQuestion(term: String, plural: Boolean?) = "什么是$term？"
        }

        object Japanese : Cjk(ScriptOption.JAPANESE) {
            override fun definitionQuestion(term: String, plural: Boolean?) = "${term}とは何ですか。"
        }

        object Korean : Cjk(ScriptOption.KOREAN) {
            /**
             * "(이)란" rather than a bare topic particle: Korean picks between 은 and 는 by
             * whether the preceding syllable ends in a consonant, and this dictionary phrasing
             * sidesteps the agreement entirely.
             */
            override fun definitionQuestion(term: String, plural: Boolean?) = "$term(이)란 무엇입니까?"
        }
    }

    companion object {
        const val HEADING_MAX_LENGTH = 60
        const val DEVANAGARI_HEADING_MAX_LENGTH = 32
        const val CJK_HEADING_MAX_LENGTH = 24
        val WHITESPACE = Regex("""\s+""")

        fun of(script: ScriptOption): ScriptProfile = when (script) {
            ScriptOption.LATIN -> Latin
            ScriptOption.DEVANAGARI -> Devanagari
            ScriptOption.CHINESE -> Cjk.Chinese
            ScriptOption.JAPANESE -> Cjk.Japanese
            ScriptOption.KOREAN -> Cjk.Korean
        }
    }
}

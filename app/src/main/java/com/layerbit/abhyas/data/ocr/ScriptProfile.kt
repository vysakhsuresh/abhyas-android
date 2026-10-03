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
     * What goes between two lines that were one line before the text wrapped.
     *
     * Empty for the unspaced scripts: inserting a space at every wrapped line would open a gap in
     * the middle of a word that was never broken in the first place.
     */
    val joiner: String get() = if (wordSpaced) " " else ""

    /**
     * What goes between [left] and [right] when a wrapped line is joined back together.
     *
     * [joiner] with one exception, and the exception is why this exists. Every CJK profile covers
     * "Chinese (and Latin)" - these recognisers read the English that runs through a Chinese
     * textbook - and the no-space rule that is right for Chinese is wrong in the middle of an
     * English phrase. A page whose line broke between "generate" and "most" came back as
     * "generatemost", because the rule looked at the script of the deck rather than at the
     * characters either side of the break.
     */
    fun joinerBetween(left: String, right: String): String {
        if (wordSpaced) return " "
        val before = left.lastOrNull() ?: return ""
        val after = right.firstOrNull() ?: return ""
        return if (before.isLatinWord() && after.isLatinWord()) " " else ""
    }

    private fun Char.isLatinWord(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || isDigit()

    /**
     * Strip surrounding punctuation from a token without cutting into the word itself.
     *
     * `trim { !it.isLetterOrDigit() }` is the obvious way to write this, and it is wrong outside
     * Latin. A Devanagari vowel sign is a *combining mark* rather than a letter - the `ा` in
     * `प्रक्रिया` is category Mc - so that predicate trimmed it off the end, leaving the stem
     * `प्रक्रिय`, which is not a word. The cloze pass then blanked that stem out of the sentence and
     * left the orphaned sign stranded, printing `यह _____ा गर्मियों` and offering the non-word as the
     * answer. Most Hindi words end in one of these signs, so this was most Hindi clozes.
     */
    protected fun String.trimToWord(): String = trim { !it.isWordCharacter() }

    private fun Char.isWordCharacter(): Boolean =
        isLetterOrDigit() ||
            category == CharCategory.NON_SPACING_MARK ||
            category == CharCategory.COMBINING_SPACING_MARK

    /**
     * Shortest run worth making a card from. CJK is far denser per character than an alphabet,
     * so the same threshold would throw away perfectly good sentences.
     */
    open val minSentenceLength: Int = 12

    /** Inverting "X is Y" into "What is X?". Null where the grammar does not allow it. */
    open val copula: Regex? = null

    /**
     * How a worked exercise labels its question and its answer on the page.
     *
     * The lookahead on the optional-terminator alternatives is what keeps these from matching a
     * *prefix* of a longer word. `Ans\.?\s*[:.]?` has both terminators optional, so it matched the
     * bare "Ans" of "Answer:" and stripped only those three letters - the card then shipped as
     * "wer: the green pigment found in leaves", at 0.98 confidence, which is the top of the review
     * list. Requiring the label to end at whitespace or end-of-string accepts "Answer:" and "Ans."
     * whole while rejecting "Answered" and "Ansel".
     *
     * The spelled-out forms are matched too. "Question 1." and "Answer:" are at least as common in
     * a textbook as the abbreviations, and the old patterns required a terminator straight after the
     * Q or A, so every exercise written out in full was skipped by the Q&A pass entirely.
     */
    open val questionPrefix: Regex =
        Regex(
            """^\s*(Q(?:uestion)?s?\s*\d*\s*[.):]|\d{1,2}\s*[.)])\s*""",
            RegexOption.IGNORE_CASE
        )
    open val answerPrefix: Regex =
        Regex(
            """^\s*(A\s*\d*\s*[.):]|Ans(?:wer)?s?\s*\d*\s*[.):]?(?=\s|$))\s*""",
            RegexOption.IGNORE_CASE
        )

    /** Sentence openers that mean "a list follows", not "here is a definition". */
    open val leadInStarts: List<String> = emptyList()

    /** Sentence openers that define nothing without their antecedent. */
    open val pronounStarts: List<String> = emptyList()

    /**
     * Words that open a subordinate clause. A sentence starting with one states a condition
     * before it states anything else, so its grammatical subject is not the thing being defined.
     */
    open val subordinatorStarts: List<String> = emptyList()

    /**
     * Nouns too generic to define on their own. Only consulted for a subject of the form
     * "The <noun>", where the definite article means the reader is expected to already know
     * which one - and a card asking about it out of context is unanswerable.
     */
    open val genericNouns: Set<String> = emptySet()

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

        /**
         * A sentence opening with one of these is a condition or an aside, not a definition.
         * "If that bites, document-boundary detection is the obvious next feature" inverts into
         * "What is If that bites, document-boundary detection?" - a question nobody could answer.
         */
        override val subordinatorStarts = listOf(
            "if", "when", "while", "although", "though", "because", "since", "unless",
            "after", "before", "until", "whereas", "whenever", "wherever", "as", "once",
            "given", "assuming", "provided", "suppose", "supposing"
        )

        /**
         * "The generator is heuristic" is a sentence about a thing already introduced, not a
         * definition of it - the reader has to know which generator before the question means
         * anything. "The" plus a generic noun is the giveaway; "The mitochondrion is..." is
         * fine, because the noun carries the meaning on its own.
         */
        override val genericNouns = setOf(
            "generator", "process", "system", "method", "result", "results", "value", "values",
            "app", "application", "program", "code", "file", "files", "user", "users", "page",
            "pages", "way", "ways", "thing", "things", "part", "parts", "idea", "ideas",
            "answer", "answers", "question", "questions", "point", "points", "reason", "reasons",
            "problem", "problems", "issue", "issues", "case", "cases", "example", "examples",
            "number", "numbers", "amount", "rest", "whole", "purpose", "goal", "aim", "cost",
            "difference", "change", "changes", "effect", "effects", "term", "terms", "word",
            "words", "name", "names", "list", "lists", "set", "sets", "group", "groups"
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

        /**
         * Articles only ever carry a capital because they started the sentence we lifted the term
         * out of. Mid-question that capital reads as a mistake - "What is The focal length?" - and
         * unlike a bare noun an article is never a proper noun, so lowering it is always safe.
         */
        private val leadingArticle = Regex("""^(the|a|an)\b""", RegexOption.IGNORE_CASE)

        override fun definitionQuestion(term: String, plural: Boolean?): String {
            val looksPlural = plural
                ?: (term.endsWith("s") && notActuallyPlural.none { term.lowercase().endsWith(it) })
            val phrase = leadingArticle.replace(term) { it.value.lowercase() }
            return if (looksPlural) "What are $phrase?" else "What is $phrase?"
        }

        /**
         * A capital letter mid-sentence is the best proxy an alphabet gives for a proper noun or
         * a technical term. Ties break towards the longer word.
         */
        override fun salientTerm(sentence: String): String? =
            sentence.split(WHITESPACE)
                .drop(1)
                .map { it.trimToWord() }
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

        // The lookaheads matter more here than in Latin, because Devanagari has no case to fall
        // back on: without one, "उत्तरी भारत" ("northern India") had its उत्तर stripped and shipped
        // as an answer beginning "ी भारत".
        override val questionPrefix = Regex(
            """^\s*(प्रश्न\s*\d*\s*[.):]?(?=\s|$)|प्र\s*\d*\s*[.):]|""" +
                """Q(?:uestion)?s?\s*\d*\s*[.):]|\d{1,2}\s*[.)])\s*""",
            RegexOption.IGNORE_CASE
        )
        override val answerPrefix = Regex(
            """^\s*(उत्तर\s*\d*\s*[.):]?(?=\s|$)|उ\s*\d*\s*[.):]|""" +
                """A\s*\d*\s*[.):]|Ans(?:wer)?s?\s*\d*\s*[.):]?(?=\s|$))\s*""",
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
                .map { it.trimToWord() }
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
        /**
         * Two alternatives, because the full-width and borrowed-Latin terminators need different
         * rules. CJK does not space its sentences, so 。？！ must split on a zero-width match; the
         * ASCII '.' must not, or it splits inside a decimal. With both in one zero-width lookbehind,
         * "密度约为1.0克每立方厘米。" came back as "密度约为1." and "0克每立方厘米。" - two fragments
         * long enough to clear the six-character minimum and go on to become cards.
         */
        override val sentenceBoundary = Regex("""(?<=[。？！])\s*|(?<=[.?!])\s+""")
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
        // 答案 before 答, so the two-character form is consumed whole. Matching 答 first left the
        // answer beginning "案：".
        override val answerPrefix = Regex(
            """^\s*((?:答案|答)\s*\d*\s*[.):：、]?|정답\s*[.):]?|답\s*[.):]?|""" +
                """A\s*\d*\s*[.):]|Ans(?:wer)?s?\s*\d*\s*[.):]?(?=\s|$))\s*""",
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

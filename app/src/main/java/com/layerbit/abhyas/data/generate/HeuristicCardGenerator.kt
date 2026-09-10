package com.layerbit.abhyas.data.generate

import com.layerbit.abhyas.data.ocr.PageText

/**
 * Writes cards from a page without a language model.
 *
 * The four passes below run in descending order of how much the page told us. An explicit "Q./A."
 * pair is the page handing over a finished card; a definition is the page stating a fact in a
 * shape we can invert; a cloze is us guessing which word mattered. Everything is scored so the
 * confident suggestions sort to the top of the review screen.
 *
 * The deliberate bias is towards **fewer, better** cards. A student who sees six good suggestions
 * accepts them all; one who sees forty mediocre ones closes the app. So every pass refuses more
 * often than it fires, and [MAX_CARDS_PER_PAGE] caps the result even when they all fire at once.
 */
class HeuristicCardGenerator : CardGenerator {

    override suspend fun generate(page: PageText): List<CardCandidate> {
        val runs = page.runs()
        if (runs.isEmpty()) return emptyList()

        // Explicit Q&A has to run before sentence splitting, which destroys the very "Q1." and
        // "Ans." labels it keys off - splitting reads the stop in "Q1." as a sentence end and
        // discards the fragment. The runs it consumes are then withheld from the later passes so
        // one worked exercise cannot also come back as a mangled cloze of its own question.
        val qa = explicitQa(runs)
        val sentences = runs
            .filterIndexed { index, _ -> index !in qa.consumedRuns }
            .flatMap { page.sentencesOf(it) }

        val candidates = buildList {
            addAll(qa.candidates)
            addAll(definitions(sentences))
            addAll(clozes(sentences))
        }

        // Two passes can describe the same sentence - a colon definition is often also a fine
        // cloze. Keep the highest-scoring reading of each source sentence rather than asking the
        // user to choose between two versions of one fact.
        return candidates
            .groupBy { it.sourceText }
            .map { (_, forSentence) -> forSentence.maxBy { it.confidence } }
            .distinctBy { it.front.lowercase() }
            .sortedByDescending { it.confidence }
            .take(MAX_CARDS_PER_PAGE)
    }

    // ---------------------------------------------------------------- explicit question/answer

    /** What [explicitQa] found, plus which runs it used up. */
    private class QaPass(
        val candidates: List<CardCandidate>,
        val consumedRuns: Set<Int>
    )

    /**
     * The page already had questions on it - a worked exercise, or a textbook's end-of-chapter
     * list. Nothing has to be inferred, so these score highest of anything here.
     */
    private fun explicitQa(runs: List<String>): QaPass {
        val out = mutableListOf<CardCandidate>()
        val consumed = mutableSetOf<Int>()

        runs.forEachIndexed { index, run ->
            // A run already claimed as somebody's answer is not also the next question.
            if (index in consumed) return@forEachIndexed

            val question = QUESTION_PREFIX.find(run) ?: return@forEachIndexed
            val questionText = run.removeRange(question.range).trim()
            if (questionText.length < MIN_QUESTION_LENGTH) return@forEachIndexed

            val following = runs.getOrNull(index + 1) ?: return@forEachIndexed
            val labelled = ANSWER_PREFIX.find(following)
            val answerText = if (labelled != null) {
                following.removeRange(labelled.range).trim()
            } else {
                // An unlabelled next run is only taken when the question carried a "Q" label.
                // A numbered line on its own ("3. Mitosis") is far more often a list item than a
                // question, and guessing wrong there produces nonsense with high confidence.
                if (question.value.trimStart().startsWith("Q", ignoreCase = true)) following.trim()
                else return@forEachIndexed
            }
            if (answerText.length < MIN_ANSWER_LENGTH) return@forEachIndexed

            out += CardCandidate(
                front = questionText.ensureQuestionMark(),
                back = answerText,
                sourceText = "$run $following".trim(),
                kind = CardKind.QA,
                confidence = if (labelled != null) 0.98f else 0.90f
            )
            consumed += index
            consumed += index + 1
        }
        return QaPass(out, consumed)
    }

    // ------------------------------------------------------------------------------ definitions

    private fun definitions(sentences: List<String>): List<CardCandidate> =
        sentences.mapNotNull { colonDefinition(it) ?: copulaDefinition(it) }

    /**
     * "Photosynthesis: the process by which green plants make food."
     *
     * The guard that matters is the length of the left-hand side. Prose is full of colons -
     * "There are three reasons:" - and only a short, term-shaped left side is actually a
     * definition rather than a lead-in.
     */
    private fun colonDefinition(sentence: String): CardCandidate? {
        val colon = sentence.indexOf(':').takeIf { it > 0 } ?: return null
        val term = sentence.take(colon).trim()
        val definition = sentence.drop(colon + 1).trim().trimEnd('.')

        if (term.wordCount() !in 1..MAX_TERM_WORDS) return null
        if (definition.length < MIN_ANSWER_LENGTH) return null
        // "There are three reasons: ..." - a left side that is a sentence, not a term.
        if (term.lowercase().startsWithAny(LEAD_IN_STARTS)) return null
        if (term.last() in ",;") return null

        return CardCandidate(
            front = term.asDefinitionQuestion(),
            back = definition,
            sourceText = sentence,
            kind = CardKind.DEFINITION,
            confidence = 0.85f
        )
    }

    /**
     * "The mitochondrion is the powerhouse of the cell."
     *
     * Inverting a copula only works when the subject is a short noun phrase at the very start of
     * the sentence. Anything longer is a claim about something, not a definition of it, and turns
     * into a question nobody could answer.
     */
    private fun copulaDefinition(sentence: String): CardCandidate? {
        val match = COPULA.find(sentence) ?: return null
        val subject = sentence.take(match.range.first).trim().trimStart('.', ',')
        val predicate = sentence.drop(match.range.last + 1).trim().trimEnd('.')

        if (subject.wordCount() !in 1..MAX_TERM_WORDS) return null
        if (predicate.length < MIN_ANSWER_LENGTH) return null
        // "It is...", "This is...", "There are..." define nothing without their antecedent.
        if (subject.lowercase().startsWithAny(PRONOUN_STARTS)) return null

        val verb = match.value.trim().lowercase()
        return CardCandidate(
            front = subject.asDefinitionQuestion(plural = verb.startsWith("are")),
            back = predicate,
            sourceText = sentence,
            // Lower than a colon definition: the colon was the author being explicit, whereas
            // this is us reading a grammatical pattern and hoping it meant what it looks like.
            confidence = 0.72f,
            kind = CardKind.DEFINITION
        )
    }

    // ----------------------------------------------------------------------------------- clozes

    /**
     * Blank out the one value in a sentence most likely to be the thing worth remembering.
     *
     * Dates and figures first, because a sentence that contains one is almost always *about* it.
     * Otherwise the most distinctive term - a mid-sentence capitalised word, which after OCR is
     * the best available proxy for a proper noun or a technical term.
     */
    private fun clozes(sentences: List<String>): List<CardCandidate> =
        sentences.mapNotNull { sentence ->
            if (sentence.wordCount() !in MIN_CLOZE_WORDS..MAX_CLOZE_WORDS) return@mapNotNull null

            val target = NUMERIC.find(sentence)?.let { it.value to 0.68f }
                ?: salientTerm(sentence)?.let { it to 0.55f }
                ?: return@mapNotNull null

            val (term, confidence) = target
            // Blanking a word that appears twice would leave the answer sitting in the question.
            if (sentence.countOccurrences(term) != 1) return@mapNotNull null

            CardCandidate(
                front = sentence.replace(term, BLANK),
                back = term,
                sourceText = sentence,
                kind = CardKind.CLOZE,
                confidence = confidence
            )
        }

    /**
     * The most distinctive word in the sentence: capitalised but not sentence-initial, not a
     * stopword, and long enough to be worth recalling. Ties break towards the longer word, which
     * is a crude but effective stand-in for "more technical".
     */
    private fun salientTerm(sentence: String): String? =
        sentence.split(WHITESPACE)
            .drop(1) // the first word is capitalised because it starts the sentence
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= MIN_TERM_LENGTH }
            .filter { it.first().isUpperCase() }
            .filterNot { it.lowercase() in STOPWORDS }
            .maxByOrNull { it.length }

    // ----------------------------------------------------------------------------------- helpers

    private fun String.wordCount(): Int = split(WHITESPACE).count { it.isNotBlank() }

    private fun String.countOccurrences(needle: String): Int =
        split(needle).size - 1

    private fun String.startsWithAny(prefixes: List<String>): Boolean =
        prefixes.any { this == it || startsWith("$it ") }

    private fun String.ensureQuestionMark(): String =
        if (endsWith("?")) this else "$this?"

    /**
     * Turn a term into the question that asks for it.
     *
     * [plural] is the sentence's own verb when there was one ("Stomata **are**"), and that beats
     * any amount of guessing from the noun - it is what stops "Photosynthesis is..." becoming
     * "What are Photosynthesis?". Only a colon definition, which has no verb to read, falls back
     * to the suffix test, and that test has to exclude the endings that merely look plural:
     * -sis, -is, -us, -ss and -ous all end in s without being one.
     */
    private fun String.asDefinitionQuestion(plural: Boolean? = null): String {
        val looksPlural = plural
            ?: (endsWith("s") && NOT_ACTUALLY_PLURAL.none { lowercase().endsWith(it) })
        return if (looksPlural) "What are $this?" else "What is $this?"
    }

    private companion object {
        const val MAX_CARDS_PER_PAGE = 12
        const val MAX_TERM_WORDS = 6
        const val MIN_ANSWER_LENGTH = 15
        const val MIN_QUESTION_LENGTH = 10
        const val MIN_TERM_LENGTH = 4
        const val MIN_CLOZE_WORDS = 6
        const val MAX_CLOZE_WORDS = 34
        const val BLANK = "_____"

        val WHITESPACE = Regex("""\s+""")

        /** "Q.", "Q:", "Q1)", "1." at the start of a line, or any sentence ending in "?". */
        val QUESTION_PREFIX = Regex("""^\s*(Q\s*\d*\s*[.):]|\d{1,2}\s*[.)])\s*""", RegexOption.IGNORE_CASE)
        val ANSWER_PREFIX = Regex("""^\s*(A\s*\d*\s*[.):]|Ans\.?\s*[:.]?)\s*""", RegexOption.IGNORE_CASE)

        /**
         * The copulas worth inverting. Ordered longest-first so "is defined as" wins over the
         * bare "is" that sits inside it - Regex alternation is first-match, not longest-match,
         * and getting this backwards silently truncates every definition to the word "defined".
         */
        val COPULA = Regex(
            """\s+(is defined as|are defined as|is known as|are known as|is called|are called|refers to|refer to|means|is|are)\s+"""
        )

        /**
         * "was" and "were" are deliberately absent. Past tense is narrative, not definitional -
         * "The process was first described by Jan Ingenhousz in 1779" is a fact about a thing,
         * not a definition of it, and inverting it yields the useless "What is The process?".
         * Sentences like that fall through to the cloze pass instead, which blanks the date and
         * produces the card actually worth studying.
         */
        val NOT_ACTUALLY_PLURAL = listOf("ss", "us", "is", "sis", "ous")

        /** A year, or any figure with a unit or percentage attached. */
        val NUMERIC = Regex("""\b(1[0-9]{3}|20[0-9]{2})\b|\b\d+(\.\d+)?\s?%|\b\d+(\.\d+)?\s?[a-zA-Z]{1,4}\b""")

        val LEAD_IN_STARTS = listOf(
            "there", "these", "those", "the following", "following", "note", "example",
            "examples", "for example", "steps", "reasons", "types", "kinds"
        )

        val PRONOUN_STARTS = listOf(
            "it", "this", "that", "they", "these", "those", "there", "he", "she", "we", "you",
            "which", "who", "what", "here"
        )

        /**
         * Capitalised words that carry no meaning worth testing. Deliberately short - the goal is
         * to skip sentence connectives and honorifics, not to filter vocabulary.
         */
        val STOPWORDS = setOf(
            "the", "this", "that", "these", "those", "there", "then", "thus", "hence",
            "however", "therefore", "moreover", "although", "because", "since", "while",
            "when", "where", "which", "what", "who", "whom", "whose", "with", "without",
            "from", "into", "onto", "upon", "about", "after", "before", "during", "under",
            "over", "between", "among", "also", "such", "some", "many", "most", "more",
            "less", "than", "they", "them", "their", "have", "has", "had", "been", "being",
            "mr", "mrs", "dr", "prof", "fig", "figure", "table", "chapter", "page", "note"
        )
    }
}

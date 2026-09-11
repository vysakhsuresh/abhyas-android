package com.layerbit.abhyas.data.generate

import com.layerbit.abhyas.data.ocr.PageText
import com.layerbit.abhyas.data.ocr.ScriptProfile

/**
 * Writes cards from a page without a language model.
 *
 * The passes below run in descending order of how much the page told us. An explicit "Q./A." pair
 * is the page handing over a finished card; a definition is the page stating a fact in a shape we
 * can invert; a cloze is us guessing which word mattered. Everything is scored so the confident
 * suggestions sort to the top of the review screen.
 *
 * Every language-specific rule - where a sentence ends, which verbs define, how to word the
 * question, which word is worth blanking - comes from the page's [ScriptProfile] rather than
 * being written in here. That is what lets the same passes serve English, Hindi and CJK, and it
 * is also why a script can support only some of them: [ScriptProfile.copula] is null for Hindi
 * because its verb sits at the end of the clause, and [ScriptProfile.salientTerm] is null for CJK
 * because whitespace does not mark word boundaries there.
 *
 * The deliberate bias is towards **fewer, better** cards. A student who sees six good suggestions
 * accepts them all; one who sees forty mediocre ones closes the app. So every pass refuses more
 * often than it fires, and [MAX_CARDS_PER_PAGE] caps the result even when they all fire at once.
 */
class HeuristicCardGenerator : CardGenerator {

    override suspend fun generate(page: PageText): List<CardCandidate> {
        val profile = page.profile
        val runs = page.runs()
        if (runs.isEmpty()) return emptyList()

        // Explicit Q&A has to run before sentence splitting, which destroys the very "Q1." and
        // "Ans." labels it keys off - splitting reads the stop in "Q1." as a sentence end and
        // discards the fragment. The runs it consumes are then withheld from the later passes so
        // one worked exercise cannot also come back as a mangled cloze of its own question.
        val qa = explicitQa(runs, profile)
        val sentences = runs
            .filterIndexed { index, _ -> index !in qa.consumedRuns }
            .flatMap { page.sentencesOf(it) }

        val candidates = buildList {
            addAll(qa.candidates)
            addAll(definitions(sentences, profile))
            addAll(clozes(sentences, profile))
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
    private fun explicitQa(runs: List<String>, profile: ScriptProfile): QaPass {
        val out = mutableListOf<CardCandidate>()
        val consumed = mutableSetOf<Int>()

        runs.forEachIndexed { index, run ->
            // A run already claimed as somebody's answer is not also the next question.
            if (index in consumed) return@forEachIndexed

            val question = profile.questionPrefix.find(run) ?: return@forEachIndexed
            val questionText = run.removeRange(question.range).trim()
            if (questionText.length < profile.minQuestionChars) return@forEachIndexed

            val following = runs.getOrNull(index + 1) ?: return@forEachIndexed
            val labelled = profile.answerPrefix.find(following)
            val answerText = if (labelled != null) {
                following.removeRange(labelled.range).trim()
            } else {
                // An unlabelled next run is only taken when the question carried a real question
                // label. A numbered line on its own ("3. Mitosis") is far more often a list item
                // than a question, and guessing wrong there produces nonsense with high
                // confidence - the worst combination available.
                if (looksLikeQuestionLabel(question.value)) following.trim()
                else return@forEachIndexed
            }
            if (answerText.length < profile.minAnswerChars) return@forEachIndexed

            out += CardCandidate(
                front = questionText.ensureQuestionMark(profile),
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

    /** A "Q", or the word for question in one of the supported scripts - not a bare number. */
    private fun looksLikeQuestionLabel(label: String): Boolean {
        val trimmed = label.trimStart()
        return QUESTION_WORDS.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    // ------------------------------------------------------------------------------ definitions

    private fun definitions(sentences: List<String>, profile: ScriptProfile): List<CardCandidate> =
        sentences.mapNotNull {
            colonDefinition(it, profile)
                ?: copulaDefinition(it, profile)
                ?: meansDefinition(it, profile)
        }

    /**
     * "Photosynthesis: the process by which green plants make food."
     *
     * The guard that matters is the length of the left-hand side. Prose is full of colons -
     * "There are three reasons:" - and only a short, term-shaped left side is actually a
     * definition rather than a lead-in.
     *
     * This is the one pass that works in every script, which is what carries Hindi and CJK.
     */
    private fun colonDefinition(sentence: String, profile: ScriptProfile): CardCandidate? {
        val colon = sentence.indexOfFirst { it in COLONS }.takeIf { it > 0 } ?: return null
        val term = sentence.take(colon).trim()
        val definition = sentence.drop(colon + 1).trim().trimEnd('.')

        if (term.isEmpty()) return null
        // "Chapter 4: Photosynthesis" is a title that happens to contain a colon.
        if (profile.headingPrefix?.containsMatchIn(term) == true) return null
        if (profile.units(term) !in 1..profile.maxTermUnits) return null
        if (definition.length < profile.minAnswerChars) return null
        // "There are three reasons: ..." - a left side that is a sentence, not a term.
        if (term.lowercase().startsWithAny(profile.leadInStarts)) return null
        if (term.last() in ",;") return null

        return CardCandidate(
            front = profile.definitionQuestion(term),
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
     * into a question nobody could answer. Skipped entirely for scripts whose grammar does not
     * put the verb between the two halves.
     */
    private fun copulaDefinition(sentence: String, profile: ScriptProfile): CardCandidate? {
        val copula = profile.copula ?: return null
        val match = copula.find(sentence) ?: return null
        val subject = sentence.take(match.range.first).trim().trimStart('.', ',')
        val predicate = sentence.drop(match.range.last + 1).trim().trimEnd('.')

        if (profile.units(subject) !in 1..profile.maxTermUnits) return null
        if (predicate.length < profile.minAnswerChars) return null
        // "It is...", "This is...", "There are..." define nothing without their antecedent.
        if (subject.lowercase().startsWithAny(profile.pronounStarts)) return null

        val verb = match.value.trim().lowercase()
        return CardCandidate(
            front = profile.definitionQuestion(subject, plural = verb.startsWith("are")),
            back = predicate,
            sourceText = sentence,
            // Lower than a colon definition: the colon was the author being explicit, whereas
            // this is us reading a grammatical pattern and hoping it meant what it looks like.
            confidence = 0.72f,
            kind = CardKind.DEFINITION
        )
    }

    /**
     * Hindi's "X ka arth hai Y" - X means Y.
     *
     * General copula inversion is off for Devanagari because Hindi puts its verb at the end of
     * the clause, so the Latin trick would put the whole definition on the left and nothing on
     * the right. This fixed phrase is the exception worth special-casing: it is unambiguous, the
     * definition genuinely follows it, and it is very common in textbook prose.
     */
    private fun meansDefinition(sentence: String, profile: ScriptProfile): CardCandidate? {
        if (profile !is ScriptProfile.Devanagari) return null
        val match = ScriptProfile.Devanagari.meansPattern.find(sentence) ?: return null

        val term = sentence.take(match.range.first).trim()
        val meaning = sentence.drop(match.range.last + 1).trim().trimEnd('.')

        if (profile.units(term) !in 1..profile.maxTermUnits) return null
        if (meaning.length < profile.minAnswerChars) return null
        if (term.startsWithAny(profile.pronounStarts)) return null

        return CardCandidate(
            front = profile.definitionQuestion(term),
            back = meaning,
            sourceText = sentence,
            kind = CardKind.DEFINITION,
            confidence = 0.80f
        )
    }

    // ----------------------------------------------------------------------------------- clozes

    /**
     * Blank out the one value in a sentence most likely to be the thing worth remembering.
     *
     * Dates and figures first, because a sentence that contains one is almost always *about* it,
     * and because digits look the same in every script here. Otherwise whatever the script's own
     * [ScriptProfile.salientTerm] picks - which is nothing at all for CJK, where choosing a term
     * would mean guessing at word boundaries that whitespace does not mark.
     */
    private fun clozes(sentences: List<String>, profile: ScriptProfile): List<CardCandidate> =
        sentences.mapNotNull { sentence ->
            if (profile.units(sentence) !in profile.clozeRange) return@mapNotNull null

            val target = NUMERIC.find(sentence)?.let { it.value to 0.68f }
                ?: profile.salientTerm(sentence)?.let { it to 0.55f }
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

    // ----------------------------------------------------------------------------------- helpers

    private fun String.countOccurrences(needle: String): Int = split(needle).size - 1

    private fun String.startsWithAny(prefixes: List<String>): Boolean =
        prefixes.any { this == it || startsWith("$it ") }

    /** Add the script's own question mark, if the text does not already end in one. */
    private fun String.ensureQuestionMark(profile: ScriptProfile): String {
        val last = trimEnd().lastOrNull()
        if (last != null && last in QUESTION_MARKS) return this
        return this + if (profile.wordSpaced) "?" else "？"
    }

    private companion object {
        const val MAX_CARDS_PER_PAGE = 12
        const val BLANK = "_____"

        /** ASCII and fullwidth colons both introduce a definition. */
        const val COLONS = ":：﹕"
        const val QUESTION_MARKS = "?？"

        /** Labels that genuinely mean "a question follows", across the supported scripts. */
        val QUESTION_WORDS = listOf("Q", "प्रश्न", "प्र", "問", "问", "문제")

        /**
         * A year, or any figure with a unit or percentage attached. Digits are written the same
         * way in every script Abhyas supports, so this one pattern is genuinely universal - which
         * is exactly why it is the fallback that keeps CJK clozes working at all.
         */
        val NUMERIC = Regex("""\b(1[0-9]{3}|20[0-9]{2})\b|\b\d+(\.\d+)?\s?%|\b\d+(\.\d+)?\s?[a-zA-Z]{1,4}\b""")
    }
}

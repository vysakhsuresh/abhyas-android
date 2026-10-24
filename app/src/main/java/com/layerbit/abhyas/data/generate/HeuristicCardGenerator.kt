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
        // Lists are claimed first. A bullet under a heading is the single most card-shaped thing
        // on a textbook page and the other passes can make nothing of it - they used to produce
        // fragments like "- _____ toxins from the blood" from the leftovers.
        val lists = listPass(runs, profile)
        val claimed = qaOrListClaimed(lists)

        val qa = explicitQa(runs, profile, claimed)

        val sentences = runs
            .filterIndexed { index, _ -> index !in qa.consumedRuns && index !in claimed }
            // Strip the bullet marker: it is layout, and leaving it in put a dash on the front
            // of the card.
            .flatMap { page.sentencesOf(stripListMarker(it)) }

        val candidates = buildList {
            addAll(lists.map { it.candidate })
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
    private fun explicitQa(
        runs: List<String>,
        profile: ScriptProfile,
        claimed: Set<Int>
    ): QaPass {
        val out = mutableListOf<CardCandidate>()
        val consumed = mutableSetOf<Int>()

        runs.forEachIndexed { index, run ->
            // A run already claimed as somebody's answer, or as part of a list, is not also the
            // next question.
            if (index in consumed || index in claimed) return@forEachIndexed

            val question = profile.questionPrefix.find(run) ?: return@forEachIndexed
            val questionText = run.removeRange(question.range).trim()
            if (questionText.length < profile.minQuestionChars) return@forEachIndexed

            // Look for an explicitly labelled answer first, over a short window rather than
            // only the very next run. OCR does not guarantee reading order, so an "Ans." can
            // arrive a run later than it appears on the page - and pairing a question with
            // whatever came back next produced a card asking where photosynthesis happens and
            // answering "the mitochondrion is the powerhouse of the cell".
            val labelledAt = (index + 1..index + ANSWER_LOOKAHEAD)
                .firstOrNull { at ->
                    val candidate = runs.getOrNull(at) ?: return@firstOrNull false
                    at !in consumed && profile.answerPrefix.containsMatchIn(candidate)
                }

            val answerAt = labelledAt ?: (index + 1)
            // The lookahead above skips runs already claimed, but the fallback does not - so when
            // the window turned up nothing new, answerAt landed back on the answer an earlier
            // question had already taken. Because that run is still labelled, both guards below live
            // in the `labelled == null` branch and were skipped, and the duplicate shipped at the
            // top confidence. Two questions, the same answer, 0.98 each.
            if (answerAt in consumed) return@forEachIndexed
            val following = runs.getOrNull(answerAt) ?: return@forEachIndexed
            val labelled = profile.answerPrefix.find(following)

            val answerText = if (labelled != null) {
                following.removeRange(labelled.range).trim()
            } else {
                // An unlabelled next run is only taken when the question carried a real question
                // label. A numbered line on its own ("3. Mitosis") is far more often a list item
                // than a question, and guessing wrong there produces nonsense with high
                // confidence - the worst combination available.
                if (!looksLikeQuestionLabel(question.value)) return@forEachIndexed
                // ...and never when that run is plainly the next question rather than an answer.
                if (profile.questionPrefix.containsMatchIn(following)) return@forEachIndexed
                following.trim()
            }
            if (answerText.length !in profile.minAnswerChars..profile.maxAnswerChars) {
                return@forEachIndexed
            }

            out += CardCandidate(
                front = questionText.ensureQuestionMark(profile),
                back = answerText,
                sourceText = "$run $following".trim(),
                kind = CardKind.QA,
                confidence = if (labelled != null) 0.98f else 0.90f
            )
            consumed += index
            consumed += answerAt
        }
        return QaPass(out, consumed)
    }

    /** A "Q", or the word for question in one of the supported scripts - not a bare number. */
    private fun looksLikeQuestionLabel(label: String): Boolean {
        val trimmed = label.trimStart()
        return QUESTION_WORDS.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    // ----------------------------------------------------------------------------------- lists

    /** A list card, plus which runs it used up. */
    private class ListHit(val candidate: CardCandidate, val runIndices: Set<Int>)

    private fun qaOrListClaimed(lists: List<ListHit>): Set<Int> =
        lists.flatMapTo(mutableSetOf()) { it.runIndices }

    /**
     * A heading or lead-in followed by bullet points.
     *
     * Textbooks are full of these and the engine used to produce *nothing* from them - worse,
     * the lead-in guard on the definition pass explicitly threw away "There are three types of
     * rock:" as prose, and the cloze pass turned the leftover bullets into fragments with the
     * dash still attached.
     *
     * Two ways in. A lead-in ending in a colon is unambiguous and works in every script. A bare
     * heading is only usable where the language gives a dependable test that it names a plural
     * category - see [ScriptProfile.listSubjectInfix] - because getting that wrong turns
     * "Photosynthesis" into "What are the three photosynthesis?".
     */
    private fun listPass(runs: List<String>, profile: ScriptProfile): List<ListHit> {
        val out = mutableListOf<ListHit>()
        var index = 0

        while (index < runs.size) {
            if (!isListItem(runs[index])) {
                index++
                continue
            }

            var end = index
            while (end < runs.size && isListItem(runs[end])) end++

            val items = (index until end).map { stripListMarker(runs[it]) }
                .filter { it.isNotBlank() }

            val introAt = index - 1
            val intro = runs.getOrNull(introAt)
            val subject = intro?.let { listSubject(it, profile) }

            if (items.size >= MIN_LIST_ITEMS && subject != null) {
                val answer = items.joinToString(", ")
                if (answer.length in profile.minAnswerChars..profile.maxAnswerChars) {
                    out += ListHit(
                        candidate = CardCandidate(
                            front = profile.listQuestion(subject, countWord(items.size, subject, profile)),
                            back = answer,
                            sourceText = (listOf(intro) + runs.subList(index, end)).joinToString(" "),
                            kind = CardKind.LIST,
                            // Higher than any definition: the page laid this out as a list, so
                            // nothing is being inferred about what belongs together.
                            confidence = 0.92f
                        ),
                        runIndices = (introAt..end - 1).toSet()
                    )
                }
            }
            index = end
        }
        return out
    }

    /** What the list is a list *of*, phrased so it can be dropped into a question. */
    private fun listSubject(intro: String, profile: ScriptProfile): String? {
        val trimmed = intro.trim()

        if (trimmed.lastOrNull()?.let { it in COLONS } == true) {
            var subject = trimmed.dropLast(1).trim()
            if (subject.isEmpty()) return null
            if (profile.headingPrefix?.containsMatchIn(subject) == true) return null

            // "There are three types of rock:" -> "the three types of rock"
            THERE_IS_ARE.find(subject)?.let { subject = "the " + subject.removeRange(it.range) }
            // "The three types of rock are:" -> "the three types of rock"
            TRAILING_COPULA.find(subject)?.let { subject = subject.removeRange(it.range).trim() }

            if (profile.units(subject) !in 1..MAX_LIST_SUBJECT_UNITS) return null
            return if (profile.wordSpaced) subject.replaceFirstChar { it.lowercase() } else subject
        }

        // A bare heading. Only where the script can tell a plural category from a single term.
        val infix = profile.listSubjectInfix ?: return null
        if (trimmed.lastOrNull()?.let { it in profile.sentenceEnders } == true) return null
        if (profile.headingPrefix?.containsMatchIn(trimmed) == true) return null
        if (profile.units(trimmed) !in 1..MAX_LIST_SUBJECT_UNITS) return null

        val lowered = trimmed.lowercase()
        val readsPlural = lowered.contains(infix) ||
            (lowered.endsWith("s") && NOT_ACTUALLY_PLURAL.none { lowered.endsWith(it) })
        return if (readsPlural) lowered else null
    }

    /**
     * "three " when the subject does not already say how many, blank when it does - otherwise a
     * page reading "The three main types of rock:" asks "What are the three three main types".
     */
    private fun countWord(count: Int, subject: String, profile: ScriptProfile): String {
        val words = profile.numberWords
        if (count !in 1..words.size) return ""
        if (words.any { subject.contains(it, ignoreCase = true) }) return ""
        if (DIGITS.containsMatchIn(subject)) return ""
        return words[count - 1] + if (profile.wordSpaced) " " else ""
    }

    private fun isListItem(run: String): Boolean = LIST_MARKER.containsMatchIn(run)

    private fun stripListMarker(run: String): String =
        LIST_MARKER.find(run)?.let { run.removeRange(it.range).trim() } ?: run

    // ------------------------------------------------------------------------------ definitions

    private fun definitions(sentences: List<String>, profile: ScriptProfile): List<CardCandidate> =
        sentences.mapNotNull { original ->
            // Strip the list marker before the passes see the sentence, and put the original back as
            // the source text. A marker that contains a full stop is already removed by sentence
            // splitting, but "•", "-" and "a)" are not, and they were landing inside the question:
            // "What is • Chlorophyll?". Worse, every guard in these passes is anchored at the start
            // of the string, so a bullet blinded them - `isDefiniteGeneric` tests `startsWith("the ")`
            // and so passed "• The generator" straight through the generic-noun rejection it exists
            // to enforce.
            val clean = original.replaceFirst(PageText.BULLET, "").trim()
            (colonDefinition(clean, profile)
                ?: copulaDefinition(clean, profile)
                ?: meansDefinition(clean, profile))
                ?.copy(sourceText = original)
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
        if (definition.length !in profile.minAnswerChars..profile.maxAnswerChars) return null
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
        if (predicate.length !in profile.minAnswerChars..profile.maxAnswerChars) return null

        val lowered = subject.lowercase()
        // "It is...", "This is...", "There are..." define nothing without their antecedent.
        if (lowered.startsWithAny(profile.pronounStarts)) return null
        // A subordinator ANYWHERE in the subject means this is a clause, not a term. Anchoring
        // the test at the start was not enough: "Metamorphic rocks form when existing rock is
        // changed by heat" has its "when" in the middle, and the pass happily produced
        // "What is Metamorphic rocks form when existing rock?".
        if (profile.subordinatorStarts.any { " $lowered ".contains(" $it ") }) return null
        // A subject that still carries a comma is a clause, not a term.
        if (subject.contains(',')) return null
        // "The generator is..." expects the reader to know which generator already.
        if (isDefiniteGeneric(lowered, profile)) return null

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
        if (meaning.length !in profile.minAnswerChars..profile.maxAnswerChars) return null
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
    /**
     * Blank everything after a connective: "Igneous rocks form when _____."
     *
     * This is the pass that reaches process and cause-effect writing, which is most of a science
     * textbook and which nothing else here can touch. Those sentences have no colon and no
     * invertible copula, and trying to invert one produced the genuinely bad card "What is
     * Metamorphic rocks form when existing rock?".
     *
     * It is deliberately grammar-free. No subject is extracted, no verb agreed, no question
     * worded - the clause after the connective simply *is* the fact being taught, so the card
     * cannot come out ungrammatical however odd the sentence around it.
     *
     * Scored above a plain cloze because the blank is chosen rather than guessed: a connective
     * marks the fact, where [clozes] is picking the most distinctive-looking word and hoping.
     */
    private fun connectiveCloze(sentence: String, profile: ScriptProfile): CardCandidate? {
        val lowered = sentence.lowercase()

        profile.connectives.forEach { connective ->
            val at = lowered.indexOf(connective)
            if (at <= 0) return@forEach

            val head = sentence.take(at + connective.length)
            val tail = sentence.drop(at + connective.length).trim().trimEnd('.')

            // A connective at the very start leaves nothing to hang the blank on.
            if (profile.units(head) < MIN_CONNECTIVE_HEAD_UNITS) return@forEach
            if (tail.length !in profile.minAnswerChars..profile.maxAnswerChars) return@forEach

            return CardCandidate(
                front = head + BLANK + ".",
                back = tail,
                sourceText = sentence,
                kind = CardKind.CLOZE,
                confidence = 0.75f
            )
        }
        return null
    }

    private fun clozes(sentences: List<String>, profile: ScriptProfile): List<CardCandidate> =
        sentences.mapNotNull { sentence ->
            // A chosen blank beats a guessed one, so this is tried first and is not bounded by
            // the cloze length range - a process sentence is often longer than a factual one.
            connectiveCloze(sentence, profile)?.let { return@mapNotNull it }

            if (profile.units(sentence) !in profile.clozeRange) return@mapNotNull null

            val target = NUMERIC.find(sentence)?.let { it.value to 0.68f }
                ?: properNounRun(sentence)?.let { it to 0.60f }
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

    /** "The <generic noun>" - a reference back to something, not a definable term. */
    private fun isDefiniteGeneric(lowered: String, profile: ScriptProfile): Boolean {
        if (!lowered.startsWith("the ")) return false
        val rest = lowered.removePrefix("the ").trim()
        return rest.isNotEmpty() && rest.split(' ').size == 1 && rest in profile.genericNouns
    }

    private fun String.countOccurrences(needle: String): Int = split(needle).size - 1

    private fun String.startsWithAny(prefixes: List<String>): Boolean =
        prefixes.any { this == it || startsWith("$it ") }

    /** Add the script's own question mark, if the text does not already end in one. */
    /**
     * End the question in a question mark, replacing whatever terminator it already had.
     *
     * The old version only checked for an existing question mark and concatenated otherwise, which
     * is wrong for most of what a textbook actually prints. Exercises are overwhelmingly imperative:
     * "Q2. Define osmosis." becomes "Define osmosis." once the label is stripped, and that shipped
     * as "Define osmosis.?". Hindi was the same with the danda - "... दीजिए।?" - since that is not a
     * question mark either. It also returned the untrimmed receiver on the early-return path, so
     * trailing whitespace from the OCR survived onto the front of the card.
     */
    private fun String.ensureQuestionMark(profile: ScriptProfile): String {
        val trimmed = trimEnd()
        val last = trimmed.lastOrNull() ?: return trimmed
        if (last in QUESTION_MARKS) return trimmed

        val body = trimmed.trimEnd { it in profile.sentenceEnders }
        return body.ifEmpty { trimmed } + if (profile.wordSpaced) "?" else "？"
    }

    /**
     * A run of capitalised words - "Jan Ingenhousz", not "Ingenhousz".
     *
     * Blanking half a name leaves the other half sitting in the question, which is not a harder
     * card so much as a nonsensical one.
     */
    private fun properNounRun(sentence: String): String? {
        val afterFirstWord = sentence.substringAfter(' ', "")
        return PROPER_NOUN_RUN.find(afterFirstWord)?.value
    }

    private companion object {
        const val MAX_CARDS_PER_PAGE = 12

        /** One bullet is a stray dash; two is a list. */
        const val MIN_LIST_ITEMS = 2

        /** A lead-in longer than this is prose that happens to end in a colon. */
        const val MAX_LIST_SUBJECT_UNITS = 10

        /** Enough of a sentence before the connective to make the blank mean something. */
        const val MIN_CONNECTIVE_HEAD_UNITS = 3

        val LIST_MARKER = Regex("""^\s*([-*\u2022\u25CF\u25A0]|\(?\d{1,2}[.)]|[a-z][.)])\s+""")
        val DIGITS = Regex("""\d""")
        val THERE_IS_ARE = Regex("""^(there\s+(are|is)\s+)""", RegexOption.IGNORE_CASE)
        val TRAILING_COPULA = Regex("""\s+(are|is|include|includes)$""", RegexOption.IGNORE_CASE)
        val PROPER_NOUN_RUN = Regex("""\b[A-Z][a-z]{2,}(?:\s+[A-Z][a-z]{2,})+\b""")

        /**
         * How far past a question to look for a labelled answer. Small on purpose: far enough to
         * survive one stray run landing between them, short enough that it can never reach into
         * the next exercise and steal its answer.
         */
        const val ANSWER_LOOKAHEAD = 3
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
         *
         * The units are listed rather than described as "a short word after a number", which is what
         * this used to do and which failed in both directions at once. `[a-zA-Z]{1,4}` cannot reach a
         * word boundary inside a longer word, so "3 hours" - the exact case it was written for - did
         * not match; but it happily matched a number plus any function word, so "The process takes
         * 2 to 3 hours" was blanked as "2 to". And because this branch is tried before the salient
         * term, that nonsense pre-empted the better candidate rather than merely joining it.
         */
        val NUMERIC = Regex(
            """\b(1[0-9]{3}|20[0-9]{2})\b""" +
                """|\b\d+(\.\d+)?\s?%""" +
                """|\b\d+(\.\d+)?\s?(°[CF]?|km/h|kg|mg|km|cm|mm|nm|ml|ms|min|hrs?|hours?""" +
                """|minutes?|seconds?|days?|years?|kJ|Hz|[gmlsJNWVA])\b""",
            RegexOption.IGNORE_CASE
        )
    }
}

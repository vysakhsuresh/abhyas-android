package com.layerbit.abhyas.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.EnumMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the text off a photographed page, in whichever script the deck is written in.
 *
 * Every recogniser here is the **bundled** build - the models ship inside the APK, so this works
 * with no network and no Play Services model download. See the AndroidManifest comment for why
 * that is not negotiable, and the README for what it costs in APK size.
 *
 * Recognisers are created on demand and cached, because each one allocates a native model and
 * most users will only ever touch one of them.
 */
class PageTextReader {

    private val recognizers = EnumMap<ScriptOption, TextRecognizer>(ScriptOption::class.java)

    private fun recognizerFor(script: ScriptOption): TextRecognizer =
        recognizers.getOrPut(script) {
            TextRecognition.getClient(
                when (script) {
                    ScriptOption.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                    ScriptOption.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                    ScriptOption.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                    ScriptOption.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                    ScriptOption.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                }
            )
        }

    suspend fun read(context: Context, uri: Uri, script: ScriptOption): PageText =
        recognise(InputImage.fromFilePath(context, uri), script)

    suspend fun read(bitmap: Bitmap, script: ScriptOption): PageText =
        recognise(InputImage.fromBitmap(bitmap, 0), script)

    private suspend fun recognise(image: InputImage, script: ScriptOption): PageText =
        suspendCancellableCoroutine { continuation ->
            recognizerFor(script).process(image)
                .addOnSuccessListener { result ->
                    // ML Kit hands back blocks of lines laid out on the page. Reading order
                    // *within* a block is dependable; the order of the blocks themselves is not
                    // - it follows the detector's own grouping, not the page. So each block
                    // keeps the box it was found in, and everything downstream reasons about
                    // the page from those rather than from list position.
                    val blocks = result.textBlocks
                        .map { block ->
                            PageBlock(
                                lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() },
                                box = block.boundingBox?.let {
                                    TextBox(it.left, it.top, it.right, it.bottom)
                                }
                            )
                        }
                        .filter { it.lines.isNotEmpty() }
                        // Top then left: correct for a single column, and no worse than the
                        // detector's own order for two. Real multi-column pages need column
                        // detection, which is not attempted here.
                        .sortedWith(
                            compareBy(
                                { it.box?.top ?: Int.MAX_VALUE },
                                { it.box?.left ?: Int.MAX_VALUE }
                            )
                        )
                    continuation.resume(PageText(blocks, ScriptProfile.of(script)))
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}

/**
 * The recognised page, plus the rules of the script it is written in.
 *
 * The [profile] is carried on the page rather than passed around separately because every
 * decision downstream - where a sentence ends, what a heading looks like, which word is worth
 * blanking - depends on it, and a page with the wrong profile would fail quietly.
 */
data class PageText(
    val blocks: List<PageBlock>,
    val profile: ScriptProfile = ScriptProfile.Latin
) {

    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Every line, flattened - used for the "here is what was read" preview. */
    val lines: List<String> get() = blocks.flatMap { it.lines }

    val characterCount: Int get() = lines.sumOf { it.length }

    /**
     * Rebuild sentences from OCR lines.
     *
     * A camera sees line breaks, not sentences, so a paragraph arrives pre-shredded at whatever
     * width the page happened to be typeset at. Joining it back up is what lets every generator
     * downstream work on whole thoughts instead of fragments, and it is the single biggest lever
     * on card quality in the whole pipeline.
     */
    fun sentences(): List<String> = runs().flatMap { sentencesOf(it) }

    /**
     * Every run of continuous text on the page - lines joined, but not yet split into sentences.
     *
     * Some structure only survives at this level. A "Q1." or "Ans." label sits at the start of a
     * run and is destroyed by sentence splitting, which quite reasonably reads the full stop in
     * "Q1." as the end of a sentence and throws the fragment away for being too short. Anything
     * that needs those labels has to work on runs instead.
     */
    fun runs(): List<String> = stitchAcrossBlocks().flatMap { it.runs }

    // --------------------------------------------------------------------------------- stitching

    /** A block's runs, kept with its box so the next block can be judged against it. */
    private class Stitched(val runs: MutableList<String>, val box: TextBox?, val lineCount: Int)

    /**
     * Rejoin paragraphs that a block boundary split mid-sentence.
     *
     * ML Kit's blocks are its own grouping, not the page's: it routinely breaks one paragraph in
     * two, and joining only within a block truncates the sentence. "Photosynthesis: the process
     * by which green plants / make their own food using sunlight." produced a card whose answer
     * stopped at "green plants".
     *
     * The first attempt at this joined any two consecutive runs where the text looked like it
     * continued. That is not enough, and it made things worse: photographing a page on a screen
     * put "File Edit View" from the window's menu bar next to a continuation line, and the two
     * were welded into "File _____ View make their own food using sunlight." while the real
     * sentence stayed truncated.
     *
     * So the text evidence now has to be backed by the geometry - the next block must sit
     * directly beneath this one, within about one line's gap, and overlap it horizontally. That
     * test is independent of what order the blocks arrived in, which is the point: reading order
     * is the thing that cannot be trusted, so nothing load-bearing should rest on it.
     */
    private fun stitchAcrossBlocks(): List<Stitched> {
        val out = mutableListOf<Stitched>()

        blocks.forEach { block ->
            val runs = runsIn(block.lines)
            if (runs.isEmpty()) return@forEach

            val previous = out.lastOrNull()
            val previousRun = previous?.runs?.lastOrNull()

            val continues = previousRun != null &&
                profile.wordSpaced &&
                !endsSentence(previousRun) &&
                startsLowerCase(runs.first()) &&
                sitsDirectlyBelow(previous, block.box)

            if (continues) {
                previous.runs[previous.runs.lastIndex] = "$previousRun ${runs.first()}"
                previous.runs += runs.drop(1)
            } else {
                out += Stitched(runs.toMutableList(), block.box, block.lines.size)
            }
        }
        return out
    }

    /**
     * Whether [box] is the next line or two of [previous], rather than something else on the page.
     *
     * Both boxes are required. A missing one means the layout is unknown, and the honest reading
     * of "unknown" is to leave the blocks separate - a truncated answer is a worse card, but a
     * sentence welded to a menu bar is a broken one.
     */
    private fun sitsDirectlyBelow(previous: Stitched, box: TextBox?): Boolean {
        val above = previous.box ?: return false
        val below = box ?: return false

        val lineHeight = (above.height.toFloat() / previous.lineCount.coerceAtLeast(1))
        if (lineHeight <= 0f) return false

        val gap = above.gapBelow(below)
        if (gap < -lineHeight) return false                       // overlapping or above
        if (gap > lineHeight * MAX_GAP_IN_LINES) return false     // a paragraph break or further

        return above.horizontalOverlapWith(below) >= MIN_HORIZONTAL_OVERLAP
    }

    private fun startsLowerCase(text: String): Boolean =
        text.firstOrNull()?.isLowerCase() == true

    // ------------------------------------------------------------------------ within one block

    /** Split one run into sentences, dropping fragments too short to make a card out of. */
    fun sentencesOf(run: String): List<String> = splitIntoSentences(run)
        .map { it.trim() }
        .filter { it.length >= profile.minSentenceLength }

    /**
     * Glue a block's lines back into continuous runs of text.
     *
     * Two joins:
     *   - a line ending in a hyphen is a word split across lines, so join with nothing
     *   - a line that does not finish a sentence, followed by one that does not begin a new
     *     thought, is a wrap, so join with the script's own separator
     *
     * Anything else starts a new run, which is how headings and bullet items stay separate.
     */
    private fun runsIn(blockLines: List<String>): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        // CJK does not put spaces between words, so inserting one at every wrapped line would
        // leave a gap in the middle of a word that was never broken in the first place.
        val joiner = if (profile.wordSpaced) " " else ""

        for (line in blockLines) {
            if (current.isEmpty()) {
                current.append(line)
                continue
            }
            val previous = current.toString()
            when {
                previous.endsWith("-") -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                continues(previous, line) -> current.append(joiner).append(line)
                else -> {
                    runs += previous
                    current.setLength(0)
                    current.append(line)
                }
            }
        }
        if (current.isNotEmpty()) runs += current.toString()
        return runs
    }

    private fun continues(previous: String, next: String): Boolean =
        !endsSentence(previous) && !startsNewThought(next)

    private fun endsSentence(text: String): Boolean =
        text.trimEnd().lastOrNull()?.let { it in profile.sentenceEnders } == true

    /**
     * A line that is clearly its own thought rather than the continuation of one: a bullet, a
     * numbered item, or whatever the script's own heading test recognises.
     */
    private fun startsNewThought(line: String): Boolean =
        BULLET.containsMatchIn(line) || profile.startsNewThought(line)

    /** One joined run can still hold several sentences; split them back apart. */
    private fun splitIntoSentences(run: String): List<String> =
        profile.sentenceBoundary.split(run).filter { it.isNotBlank() }

    companion object {
        /**
         * Build a page from bare lines, with no layout information.
         *
         * A factory rather than a second constructor: both would erase to `List` on the JVM and
         * clash. Blocks made this way have no box, so they are never stitched together - which
         * is the correct reading of "the layout is unknown".
         */
        fun ofLines(lines: List<List<String>>, profile: ScriptProfile = ScriptProfile.Latin) =
            PageText(lines.map { PageBlock(it) }, profile)

        /** Bullet and list markers, which look the same in every script this app supports. */
        val BULLET = Regex("""^\s*([-*•●■]|\(?\d{1,2}[.)]|[a-z][.)])\s+""")

        /**
         * How far below a block the next one may start and still be the same paragraph, measured
         * in line heights. Just over one allows for the slack in OCR boxes; much more would let a
         * blank line through, and a blank line is exactly where a paragraph ends.
         */
        const val MAX_GAP_IN_LINES = 1.2f

        /** How much of the narrower block must sit under the other to count as the same column. */
        const val MIN_HORIZONTAL_OVERLAP = 0.5f
    }
}

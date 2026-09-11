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
                    // - it follows the detector's own grouping, not the page.
                    //
                    // Sorting them by where they actually sit is what stops an answer being
                    // paired with whatever block happened to come back next. Without this, a
                    // page reading "...the cell. / Q1. Where...? / Ans. ..." produced a card
                    // asking where photosynthesis happens and answering "the mitochondrion is
                    // the powerhouse of the cell", because that block was simply next in the
                    // list.
                    //
                    // Top then left, which is right for a single column and no worse than the
                    // original order for two. Real multi-column handling needs column detection
                    // and is not attempted here.
                    val blocks = result.textBlocks
                        .sortedWith(
                            compareBy(
                                { it.boundingBox?.top ?: 0 },
                                { it.boundingBox?.left ?: 0 }
                            )
                        )
                        .map { block -> block.lines.map { it.text.trim() }.filter { it.isNotEmpty() } }
                        .filter { it.isNotEmpty() }
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
 * The recognised page, as blocks of lines in reading order, plus the rules of the script it is
 * written in.
 *
 * The [profile] is carried on the page rather than passed around separately because every
 * decision downstream - where a sentence ends, what a heading looks like, which word is worth
 * blanking - depends on it, and a page and the wrong profile would fail quietly.
 */
data class PageText(
    val blocks: List<List<String>>,
    val profile: ScriptProfile = ScriptProfile.Latin
) {

    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Every line, flattened - used for the "here is what was read" preview. */
    val lines: List<String> get() = blocks.flatten()

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
    fun runs(): List<String> = stitch(blocks.flatMap { runsIn(it) })

    /**
     * Rejoin runs that a block boundary split in the middle of a sentence.
     *
     * Blocks are ML Kit's grouping, not the page's. It routinely breaks one paragraph into two
     * blocks, and joining only within a block then truncates the sentence: a page reading
     * "Photosynthesis: the process by which green plants / make their own food using sunlight."
     * produced a card whose answer stopped at "green plants".
     *
     * The join is only made on strong evidence - the previous run does not finish a sentence AND
     * the next one opens with a lower-case letter. A caption or heading never starts lower-case,
     * so this recovers split paragraphs without welding unrelated blocks together, which is the
     * failure the per-block rule was there to prevent in the first place.
     *
     * Skipped entirely for scripts with no letter case, where the signal does not exist.
     */
    private fun stitch(runs: List<String>): List<String> {
        if (!profile.wordSpaced) return runs

        val stitched = mutableListOf<String>()
        runs.forEach { run ->
            val previous = stitched.lastOrNull()
            if (previous != null && !endsSentence(previous) && startsLowerCase(run)) {
                stitched[stitched.lastIndex] = "$previous $run"
            } else {
                stitched += run
            }
        }
        return stitched
    }

    private fun startsLowerCase(text: String): Boolean =
        text.firstOrNull()?.isLowerCase() == true

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
    private fun runsIn(block: List<String>): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        // CJK does not put spaces between words, so inserting one at every wrapped line would
        // leave a gap in the middle of a word that was never broken in the first place.
        val joiner = if (profile.wordSpaced) " " else ""

        for (line in block) {
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

    private companion object {
        /** Bullet and list markers, which look the same in every script this app supports. */
        val BULLET = Regex("""^\s*([-*•●■]|\(?\d{1,2}[.)]|[a-z][.)])\s+""")
    }
}

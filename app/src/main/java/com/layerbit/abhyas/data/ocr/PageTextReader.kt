package com.layerbit.abhyas.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the text off a photographed page.
 *
 * Uses ML Kit's **bundled** Latin recogniser - the model ships inside the APK, so this works with
 * no network and no Play Services model download. See the AndroidManifest comment for why that is
 * not negotiable.
 */
class PageTextReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(context: Context, uri: Uri): PageText =
        recognise(InputImage.fromFilePath(context, uri))

    suspend fun read(bitmap: Bitmap): PageText =
        recognise(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recognise(image: InputImage): PageText =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // ML Kit hands back blocks of lines laid out on the page. Reading order
                    // within a block is dependable; across blocks it is not, so blocks stay
                    // separate and sentence joining never runs across a block boundary. That is
                    // what stops a caption being welded onto the end of a paragraph.
                    val blocks = result.textBlocks
                        .map { block -> block.lines.map { it.text.trim() }.filter { it.isNotEmpty() } }
                        .filter { it.isNotEmpty() }
                    continuation.resume(PageText(blocks))
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }

    fun close() = recognizer.close()
}

/** The recognised page, as blocks of lines in reading order. */
data class PageText(val blocks: List<List<String>>) {

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
    fun runs(): List<String> = blocks.flatMap { runsIn(it) }

    /** Split one run into sentences, dropping fragments too short to make a card out of. */
    fun sentencesOf(run: String): List<String> = splitIntoSentences(run)
        .map { it.trim() }
        .filter { it.length >= MIN_SENTENCE_LENGTH }

    /**
     * Glue a block's lines back into continuous runs of text.
     *
     * Two joins:
     *   - a line ending in a hyphen is a word split across lines, so join with nothing
     *   - a line that does not finish a sentence, followed by one that does not begin a new
     *     thought, is a wrap, so join with a space
     *
     * Anything else starts a new run, which is how headings and bullet items stay separate.
     */
    private fun runsIn(block: List<String>): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()

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
                continues(previous, line) -> current.append(' ').append(line)
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
        text.trimEnd().lastOrNull()?.let { it in SENTENCE_ENDERS } == true

    /**
     * A line that is clearly its own thought rather than the continuation of one: a bullet, a
     * numbered item, or a short capitalised line with no terminator, which is what a heading
     * looks like once the formatting has been thrown away by OCR.
     */
    private fun startsNewThought(line: String): Boolean {
        if (BULLET.containsMatchIn(line)) return true
        val first = line.firstOrNull() ?: return false
        return first.isUpperCase() && line.length < HEADING_MAX_LENGTH && !endsSentence(line)
    }

    /** One joined run can still hold several sentences; split them back apart. */
    private fun splitIntoSentences(run: String): List<String> =
        SENTENCE_BOUNDARY.split(run).filter { it.isNotBlank() }

    private companion object {
        const val MIN_SENTENCE_LENGTH = 12
        const val HEADING_MAX_LENGTH = 60
        const val SENTENCE_ENDERS = ".?!:;"

        val BULLET = Regex("""^\s*([-*•●■]|\(?\d{1,2}[.)]|[a-z][.)])\s+""")

        /**
         * Split after . ? or ! when the next sentence starts with a capital or a Devanagari
         * letter (ऀ-ॿ). Requiring that lookahead is what keeps "Dr. Bose" and "3.5 kg"
         * in one piece instead of shattering on every full stop.
         */
        val SENTENCE_BOUNDARY = Regex("""(?<=[.?!])\s+(?=[A-Zऀ-ॿ])""")
    }
}

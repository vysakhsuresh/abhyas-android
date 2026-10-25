package com.layerbit.abhyas.data.occlusion

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.layerbit.abhyas.data.ocr.ScriptOption
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Finds the labels already printed on a diagram and proposes a mask over each one.
 *
 * This is what makes occlusion from a phone practical. Drawing twenty rectangles with a thumb is the
 * tedious part of every occlusion editor; a labelled diagram already says where its labels are, and
 * the same offline recogniser that reads a page can find them. The user then deletes the ones they
 * do not want and keeps the rest, each already carrying the label's text as its answer.
 */
object LabelDetector {

    /** Labels are short. A line longer than this is a caption or body text, not a label. */
    private const val MAX_LABEL_CHARACTERS = 40

    suspend fun detect(context: Context, imageName: String, script: ScriptOption): List<Region> {
        val file = ImageStore.file(context, imageName)?.takeIf { it.exists() } ?: return emptyList()
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) } ?: return emptyList()
        val recognizer = TextRecognition.getClient(
            when (script) {
                ScriptOption.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                ScriptOption.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                ScriptOption.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                ScriptOption.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                ScriptOption.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            }
        )
        return try {
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            } ?: return emptyList()

            text.textBlocks.flatMap { it.lines }
                .filter { line -> line.text.trim().length in 1..MAX_LABEL_CHARACTERS }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    // A little padding, so the mask covers the ascenders and descenders the
                    // recogniser's tight box leaves showing - a visible tail of a letter is a hint.
                    val padX = box.height() * 0.25f
                    val padY = box.height() * 0.2f
                    Region(
                        left = (box.left - padX) / width,
                        top = (box.top - padY) / height,
                        right = (box.right + padX) / width,
                        bottom = (box.bottom + padY) / height,
                        label = line.text.trim()
                    ).normalised()
                }
                .filterNot { it.isTooSmall }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}

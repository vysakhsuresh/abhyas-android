package com.layerbit.abhyas.ui.capture

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.generate.CardKind
import com.layerbit.abhyas.data.generate.HeuristicCardGenerator
import com.layerbit.abhyas.data.ocr.PageTextReader
import com.layerbit.abhyas.data.repo.AbhyasRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One proposed card on the review list, with the edits the user has made to it. */
data class ReviewItem(
    val id: Int,
    val front: String,
    val back: String,
    val sourceText: String,
    val kind: CardKind,
    val keep: Boolean = true
)

sealed interface CaptureStep {
    data object Camera : CaptureStep
    data object Reading : CaptureStep
    data class Review(val items: List<ReviewItem>) : CaptureStep
    data class Empty(val reason: String) : CaptureStep
    data class Saved(val count: Int) : CaptureStep
}

class CaptureViewModel(
    private val repository: AbhyasRepository,
    private val appContext: Context,
    private val deckId: Long
) : ViewModel() {

    private val reader = PageTextReader()
    private val generator = HeuristicCardGenerator()

    private val _step = MutableStateFlow<CaptureStep>(CaptureStep.Camera)
    val step: StateFlow<CaptureStep> = _step.asStateFlow()

    /**
     * The in-flight read and the in-flight save.
     *
     * Both exist because [CaptureStep] alone cannot gate them. A step is only reassigned *after*
     * the suspending work finishes, so for the whole duration of that work the state still says
     * "Review" - and a second tap passes exactly the same guard the first one did. Two reads race
     * to overwrite the review list; two saves insert every kept card twice, which the user then has
     * to delete one at a time.
     */
    private var reading: Job? = null
    private var saving: Job? = null

    /**
     * Read a photographed page and propose cards from it.
     *
     * The two failure modes are told apart deliberately. "Nothing readable" means the photo was
     * blurred, dark or angled, and the fix is to take it again. "Read the page but could not make
     * cards" means the OCR worked and the text simply was not card-shaped - a diagram, a page of
     * equations - and retaking the same photo will not help. Collapsing both into one message
     * would send half the users into a loop of identical retakes.
     */
    fun process(uri: Uri) {
        // A second shutter tap while the first page is still being read would start a second
        // recognition, and whichever finished last would win - so the user could be shown
        // suggestions from the photo they did not keep.
        if (reading?.isActive == true) return

        _step.value = CaptureStep.Reading
        reading = viewModelScope.launch {
            // Read the deck's script at capture time rather than caching it, so changing the
            // script on the deck screen takes effect on the very next photo.
            val script = repository.deckScript(deckId)
            val page = try {
                reader.read(appContext, uri, script)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Throwable, not Exception. Decoding a photograph is the one place in this app that
                // can plausibly exhaust the heap, and OutOfMemoryError is an Error - it would pass
                // straight through `catch (e: Exception)` and kill the process. PageTextReader now
                // caps the decode so this should not be reachable, but "should not" is not a reason
                // to let the failure be a crash instead of a message.
                _step.value = CaptureStep.Empty("That photo could not be read. Try again.")
                return@launch
            } finally {
                // The photo has been read, or has failed to be; either way the text is what gets
                // kept and a full-resolution JPEG of someone's notes should not outlive it in the
                // cache. Only ever deletes a file this app wrote - a gallery pick is left alone.
                discardIfCaptured(uri)
            }

            if (page.isEmpty || page.characterCount < MIN_USEFUL_CHARACTERS) {
                // Naming the script matters here: a page that reads as blank is very often a
                // deck left on the wrong recogniser, and without this the user just retakes the
                // same photo until they give up.
                _step.value = CaptureStep.Empty(
                    "No ${script.label} text found on that page. Hold steady, fill the frame, " +
                        "and check the deck is set to the right script."
                )
                return@launch
            }

            val candidates = generator.generate(page)
            if (candidates.isEmpty()) {
                _step.value = CaptureStep.Empty(
                    "The text came through, but nothing on this page turned into a good " +
                        "question. Pages of definitions and explanations work best."
                )
                return@launch
            }

            _step.value = CaptureStep.Review(
                candidates.mapIndexed { index, candidate -> candidate.toReviewItem(index) }
            )
        }
    }

    fun toggleKeep(id: Int) = updateItem(id) { it.copy(keep = !it.keep) }

    /**
     * Keep or drop everything at once.
     *
     * Suggestions arrive sorted by confidence, so the common shapes are "these are all good" and
     * "only the first few are". Both are one tap with this and a dozen without it, and a review
     * screen that takes a dozen taps to clear is one people stop opening.
     */
    fun setAllKept(keep: Boolean) {
        val review = _step.value as? CaptureStep.Review ?: return
        _step.value = CaptureStep.Review(review.items.map { it.copy(keep = keep) })
    }

    fun edit(id: Int, front: String, back: String) =
        updateItem(id) { it.copy(front = front, back = back) }

    fun retake() {
        _step.value = CaptureStep.Camera
    }

    /** Commit the kept cards. Anything with an emptied side is dropped rather than saved blank. */
    fun save() {
        if (saving?.isActive == true) return
        val review = _step.value as? CaptureStep.Review ?: return
        val kept = review.items
            .filter { it.keep && it.front.isNotBlank() && it.back.isNotBlank() }
            .map {
                CardCandidate(
                    front = it.front,
                    back = it.back,
                    sourceText = it.sourceText,
                    kind = it.kind,
                    confidence = 1f
                )
            }

        saving = viewModelScope.launch {
            val saved = repository.addCards(deckId, kept)
            _step.value = CaptureStep.Saved(saved)
        }
    }

    /**
     * Delete [uri] if, and only if, it is a page this app photographed into its own cache.
     *
     * Gallery imports arrive as `content://` documents the user owns, and deleting one would be
     * destroying their file. Captures arrive as `file://` paths inside `cacheDir`, which is why the
     * check is on both the scheme and the directory rather than on the name alone.
     */
    private fun discardIfCaptured(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        runCatching {
            val file = File(path)
            if (file.parentFile?.canonicalPath == appContext.cacheDir.canonicalPath) file.delete()
        }
    }

    private fun updateItem(id: Int, transform: (ReviewItem) -> ReviewItem) {
        val review = _step.value as? CaptureStep.Review ?: return
        _step.value = CaptureStep.Review(
            review.items.map { if (it.id == id) transform(it) else it }
        )
    }

    override fun onCleared() {
        super.onCleared()
        // ML Kit holds a native recogniser open until it is told otherwise.
        reader.close()
    }

    private fun CardCandidate.toReviewItem(index: Int) = ReviewItem(
        id = index,
        front = front,
        back = back,
        sourceText = sourceText,
        kind = kind
    )

    private companion object {
        /**
         * Below this much text the page is a photo of something, not a page of notes. Catches the
         * accidental shot of a desk before it becomes three nonsense cards.
         */
        const val MIN_USEFUL_CHARACTERS = 60
    }
}

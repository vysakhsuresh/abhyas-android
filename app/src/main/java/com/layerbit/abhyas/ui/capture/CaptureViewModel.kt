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
     * Read a photographed page and propose cards from it.
     *
     * The two failure modes are told apart deliberately. "Nothing readable" means the photo was
     * blurred, dark or angled, and the fix is to take it again. "Read the page but could not make
     * cards" means the OCR worked and the text simply was not card-shaped - a diagram, a page of
     * equations - and retaking the same photo will not help. Collapsing both into one message
     * would send half the users into a loop of identical retakes.
     */
    fun process(uri: Uri) {
        _step.value = CaptureStep.Reading
        viewModelScope.launch {
            val page = try {
                reader.read(appContext, uri)
            } catch (e: Exception) {
                _step.value = CaptureStep.Empty("That photo could not be read. Try again.")
                return@launch
            }

            if (page.isEmpty || page.characterCount < MIN_USEFUL_CHARACTERS) {
                _step.value = CaptureStep.Empty(
                    "No text found on that page. Hold steady, fill the frame with the page, " +
                        "and make sure it is well lit."
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

    fun edit(id: Int, front: String, back: String) =
        updateItem(id) { it.copy(front = front, back = back) }

    fun retake() {
        _step.value = CaptureStep.Camera
    }

    /** Commit the kept cards. Anything with an emptied side is dropped rather than saved blank. */
    fun save() {
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

        viewModelScope.launch {
            val saved = repository.addCards(deckId, kept)
            _step.value = CaptureStep.Saved(saved)
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

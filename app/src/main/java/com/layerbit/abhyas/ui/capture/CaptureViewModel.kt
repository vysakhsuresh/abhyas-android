package com.layerbit.abhyas.ui.capture

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.AppPreferences
import com.layerbit.abhyas.data.generate.CardCandidate
import com.layerbit.abhyas.data.generate.CardKind
import com.layerbit.abhyas.data.generate.HeuristicCardGenerator
import com.layerbit.abhyas.data.ocr.PageTextReader
import com.layerbit.abhyas.data.ocr.ScriptOption
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
    val keep: Boolean = true,
    /** Which photographed page this came from, 1-based, so a long batch can be told apart. */
    val page: Int = 1
)

/**
 * Why a page produced nothing, told apart because the fixes differ. See [CaptureViewModel.process].
 * Carried as a value rather than a sentence so the screen can say it in the app's language.
 */
sealed interface PageProblem {
    /** The photo itself could not be decoded or read. */
    data object Unreadable : PageProblem

    /** Too little text came back - blurred, dark, or a deck left on the wrong script. */
    data class NoText(val script: ScriptOption) : PageProblem

    /** The text was read but nothing in it was card-shaped. */
    data object NothingCardShaped : PageProblem

    /** Every suggestion is already in the deck - the page was done before. */
    data object AlreadyInDeck : PageProblem
}

sealed interface CaptureStep {
    /**
     * The viewfinder. In a batch, it also carries the running tally and what happened to the page
     * just read, so the user can keep shooting without a review screen between every page.
     */
    data class Camera(
        val pages: Int = 0,
        val suggestions: Int = 0,
        val lastPageAdded: Int? = null,
        val lastPageProblem: PageProblem? = null
    ) : CaptureStep

    /** Reading [page] of [of]; [of] is above one only for a multi-photo pick from the gallery. */
    data class Reading(val page: Int, val of: Int = 1) : CaptureStep
    data class Review(val items: List<ReviewItem>) : CaptureStep
    data class Empty(val problem: PageProblem) : CaptureStep
    data class Saved(val count: Int) : CaptureStep
}

class CaptureViewModel(
    private val repository: AbhyasRepository,
    private val appContext: Context,
    private val deckId: Long
) : ViewModel() {

    private val reader = PageTextReader()
    private val generator = HeuristicCardGenerator()
    private val prefs = AppPreferences(appContext)

    private val _step = MutableStateFlow<CaptureStep>(CaptureStep.Camera())
    val step: StateFlow<CaptureStep> = _step.asStateFlow()

    /**
     * Collect page after page on the camera, and review them all at the end.
     *
     * Off by default - one page at a time is what a first-time user expects - and remembered once
     * switched on, because the student photographing a whole chapter does it every time.
     */
    private val _batch = MutableStateFlow(prefs.batchCapture)
    val batch: StateFlow<Boolean> = _batch.asStateFlow()

    /** Every suggestion collected so far, across every page of this sitting, with the user's edits. */
    private var items: List<ReviewItem> = emptyList()
    private var pagesRead = 0
    private var nextId = 0

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

    fun setBatch(enabled: Boolean) {
        prefs.batchCapture = enabled
        _batch.value = enabled
    }

    fun process(uri: Uri) = processAll(listOf(uri))

    /**
     * Read one or more photographed pages and propose cards from them.
     *
     * The failure modes are told apart deliberately. "Nothing readable" means the photo was
     * blurred, dark or angled, and the fix is to take it again. "Read the page but could not make
     * cards" means the OCR worked and the text simply was not card-shaped - a diagram, a page of
     * equations - and retaking the same photo will not help. Collapsing both into one message
     * would send half the users into a loop of identical retakes.
     *
     * Several photos picked from the gallery at once are read one after another, and always land
     * on the review screen together - that is the whole chapter, photographed earlier.
     */
    fun processAll(uris: List<Uri>) {
        // A second shutter tap while the first page is still being read would start a second
        // recognition, and whichever finished last would win - so the user could be shown
        // suggestions from the photo they did not keep.
        if (reading?.isActive == true || uris.isEmpty()) return

        reading = viewModelScope.launch {
            // Read the deck's script at capture time rather than caching it, so changing the
            // script on the deck screen takes effect on the very next photo.
            val script = repository.deckScript(deckId)
            var lastProblem: PageProblem? = null
            var lastAdded: Int? = null

            uris.forEachIndexed { index, uri ->
                _step.value = CaptureStep.Reading(page = pagesRead + 1, of = if (uris.size > 1) uris.size else 1)
                when (val outcome = readPage(uri, script)) {
                    is PageOutcome.Problem -> {
                        lastProblem = outcome.problem
                        lastAdded = null
                    }
                    is PageOutcome.Added -> {
                        lastProblem = null
                        lastAdded = outcome.count
                    }
                }
            }

            val multiPick = uris.size > 1
            _step.value = when {
                // A batch, or a multi-photo pick that found something: the tally decides.
                _batch.value && !multiPick -> CaptureStep.Camera(
                    pages = pagesRead,
                    suggestions = items.size,
                    lastPageAdded = lastAdded,
                    lastPageProblem = lastProblem
                )
                items.isNotEmpty() -> CaptureStep.Review(items)
                else -> CaptureStep.Empty(lastProblem ?: PageProblem.NothingCardShaped)
            }
        }
    }

    private sealed interface PageOutcome {
        data class Added(val count: Int) : PageOutcome
        data class Problem(val problem: PageProblem) : PageOutcome
    }

    private suspend fun readPage(uri: Uri, script: ScriptOption): PageOutcome {
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
            return PageOutcome.Problem(PageProblem.Unreadable)
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
            return PageOutcome.Problem(PageProblem.NoText(script))
        }

        // Already in the deck, or already suggested by an earlier page of this batch - a chapter's
        // pages repeat their headings and summaries, and the same card twice is worse than once.
        val already = repository.existingQuestions(deckId)
        val pending = items.mapTo(HashSet()) { AbhyasRepository.normaliseQuestion(it.front) }
        val candidates = generator.generate(page).filterNot {
            val key = AbhyasRepository.normaliseQuestion(it.front)
            key in already || key in pending
        }

        if (candidates.isEmpty()) {
            // Told apart from "nothing here is card-shaped", because re-shooting a page you
            // already did is ordinary and the right response is to move on, not to retake.
            return PageOutcome.Problem(
                if (already.isEmpty() && pending.isEmpty()) PageProblem.NothingCardShaped
                else PageProblem.AlreadyInDeck
            )
        }

        pagesRead += 1
        items = items + candidates.map { it.toReviewItem(pagesRead) }
        return PageOutcome.Added(candidates.size)
    }

    /** Leave the camera for the review list, with everything collected so far. */
    fun review() {
        if (items.isNotEmpty()) _step.value = CaptureStep.Review(items)
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
        items = items.map { it.copy(keep = keep) }
        publishReview()
    }

    fun edit(id: Int, front: String, back: String) =
        updateItem(id) { it.copy(front = front, back = back) }

    /**
     * Back to the camera for another page, keeping every suggestion and edit so far. Before batch
     * capture this threw the review list away, which made "photograph another page" mean "start
     * again" - the opposite of what someone working through a chapter wants.
     */
    fun addAnotherPage() {
        _step.value = CaptureStep.Camera(pages = pagesRead, suggestions = items.size)
    }

    /** From a problem screen: back to the camera, still holding anything collected earlier. */
    fun retake() = addAnotherPage()

    /** Commit the kept cards. Anything with an emptied side is dropped rather than saved blank. */
    fun save(tags: String) {
        if (saving?.isActive == true) return
        if (_step.value !is CaptureStep.Review) return
        val kept = items
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
            val saved = repository.addCards(deckId, kept, tags)
            items = emptyList()
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
        items = items.map { if (it.id == id) transform(it) else it }
        publishReview()
    }

    private fun publishReview() {
        if (_step.value is CaptureStep.Review) _step.value = CaptureStep.Review(items)
    }

    override fun onCleared() {
        super.onCleared()
        // ML Kit holds a native recogniser open until it is told otherwise.
        reader.close()
    }

    private fun CardCandidate.toReviewItem(page: Int) = ReviewItem(
        id = nextId++,
        front = front,
        back = back,
        sourceText = sourceText,
        kind = kind,
        page = page
    )

    private companion object {
        /**
         * Below this much text the page is a photo of something, not a page of notes. Catches the
         * accidental shot of a desk before it becomes three nonsense cards.
         */
        const val MIN_USEFUL_CHARACTERS = 60
    }
}

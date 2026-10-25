package com.layerbit.abhyas.ui.occlusion

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.occlusion.ImageStore
import com.layerbit.abhyas.data.occlusion.LabelDetector
import com.layerbit.abhyas.data.occlusion.Region
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.capture.CameraViewfinder
import com.layerbit.abhyas.ui.components.AbhyasTextField
import com.layerbit.abhyas.ui.components.FieldLabel
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SecondaryButton
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OcclusionStep {
    data class Source(val failed: Boolean = false) : OcclusionStep
    data object Preparing : OcclusionStep
    data class Editing(
        val imageName: String,
        val regions: List<Region> = emptyList(),
        val selected: Int? = null,
        val detecting: Boolean = false,
        /** Set after a detection pass, so "none found" is said rather than nothing happening. */
        val detectedCount: Int? = null
    ) : OcclusionStep
    data class Saved(val count: Int) : OcclusionStep
}

class OcclusionViewModel(
    private val repository: AbhyasRepository,
    private val appContext: Context,
    private val deckId: Long
) : ViewModel() {

    private val _step = MutableStateFlow<OcclusionStep>(OcclusionStep.Source())
    val step: StateFlow<OcclusionStep> = _step.asStateFlow()

    private var work: Job? = null

    /** Copy the photo into private storage. The camera's cache file is removed straight after. */
    fun use(uri: Uri) {
        if (work?.isActive == true) return
        _step.value = OcclusionStep.Preparing
        work = viewModelScope.launch {
            val name = try {
                ImageStore.save(appContext, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            } finally {
                if (uri.scheme == "file") uri.path?.let { runCatching { java.io.File(it).delete() } }
            }
            _step.value = if (name == null) OcclusionStep.Source(failed = true) else OcclusionStep.Editing(name)
        }
    }

    fun addRegion(region: Region) = edit { it.copy(regions = it.regions + region, selected = it.regions.size) }

    fun select(index: Int?) = edit { it.copy(selected = index) }

    fun label(index: Int, text: String) = edit { state ->
        state.copy(regions = state.regions.mapIndexed { i, r -> if (i == index) r.copy(label = text) else r })
    }

    fun remove(index: Int) = edit { state ->
        state.copy(regions = state.regions.filterIndexed { i, _ -> i != index }, selected = null)
    }

    /** Propose a mask over every label the recogniser finds, keeping any the user already drew. */
    fun detectLabels() {
        val editing = _step.value as? OcclusionStep.Editing ?: return
        if (work?.isActive == true) return
        _step.value = editing.copy(detecting = true)
        work = viewModelScope.launch {
            val found = runCatching {
                LabelDetector.detect(appContext, editing.imageName, repository.deckScript(deckId))
            }.getOrDefault(emptyList())
            // A detected box mostly over a region already drawn is the same label twice.
            val fresh = found.filter { candidate ->
                editing.regions.none { overlap(it, candidate) > 0.5f }
            }
            edit { it.copy(regions = it.regions + fresh, detecting = false, detectedCount = fresh.size) }
        }
    }

    fun save(title: String, tags: String, fallbackTitle: String, fallbackLabel: (Int) -> String) {
        val editing = _step.value as? OcclusionStep.Editing ?: return
        if (work?.isActive == true || editing.regions.isEmpty()) return
        work = viewModelScope.launch {
            val count = repository.addOcclusionCards(
                deckId = deckId,
                imageName = editing.imageName,
                title = title.ifBlank { fallbackTitle },
                regions = editing.regions,
                tags = tags,
                fallbackLabel = fallbackLabel
            )
            _step.value = OcclusionStep.Saved(count)
        }
    }

    /** Leaving without saving: the picture was copied in for nothing, so it goes. */
    fun discard() {
        val editing = _step.value as? OcclusionStep.Editing ?: return
        ImageStore.file(appContext, editing.imageName)?.delete()
        _step.value = OcclusionStep.Source()
    }

    override fun onCleared() {
        super.onCleared()
        (_step.value as? OcclusionStep.Editing)?.let { ImageStore.file(appContext, it.imageName)?.delete() }
    }

    private fun edit(transform: (OcclusionStep.Editing) -> OcclusionStep.Editing) {
        val editing = _step.value as? OcclusionStep.Editing ?: return
        _step.value = transform(editing)
    }

    private fun overlap(a: Region, b: Region): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        return (w * h) / minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1e-6f)
    }
}

@Composable
fun OcclusionScreen(deckId: Long, onDone: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = repositoryViewModel(key = "occlusion-$deckId") {
        OcclusionViewModel(it, appContext, deckId)
    }
    val step by viewModel.step.collectAsStateWithLifecycle()

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::use) }

    when (val current = step) {
        is OcclusionStep.Source -> CameraViewfinder(
            title = stringResource(R.string.occlusion_title),
            subtitle = stringResource(R.string.occlusion_subtitle),
            pickLabel = stringResource(R.string.capture_choose_photo),
            onCaptured = viewModel::use,
            onPickImage = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCancel = onDone,
            extras = {
                if (current.failed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.problem_unreadable),
                        color = AbhyasColors.Again,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )

        OcclusionStep.Preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AbhyasColors.Saffron)
        }

        is OcclusionStep.Editing -> {
            BackHandler { viewModel.discard() }
            Editor(state = current, viewModel = viewModel)
        }

        is OcclusionStep.Saved -> Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                pluralStringResource(R.plurals.saved_count, current.count, current.count),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.saved_ready), color = AbhyasColors.Muted, fontSize = 14.5.sp)
            Spacer(Modifier.height(26.dp))
            PrimaryButton(stringResource(R.string.action_back_to_deck), onClick = onDone)
        }
    }
}

@Composable
private fun Editor(state: OcclusionStep.Editing, viewModel: OcclusionViewModel) {
    var title by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    val fallbackTitle = stringResource(R.string.occlusion_default_title)
    val labelFormat = stringResource(R.string.occlusion_label_n)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding())
    ) {
        TextLink(stringResource(R.string.occlusion_retake), AbhyasColors.Muted, viewModel::discard)
        Spacer(Modifier.height(8.dp))
        ScreenTitle(stringResource(R.string.occlusion_edit_title), stringResource(R.string.occlusion_edit_hint))

        DrawingSurface(
            imageName = state.imageName,
            regions = state.regions,
            selected = state.selected,
            onAdd = viewModel::addRegion,
            onSelect = viewModel::select
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextLink(
                text = stringResource(
                    if (state.detecting) R.string.occlusion_detecting else R.string.occlusion_detect
                ),
                color = AbhyasColors.Saffron,
                onClick = viewModel::detectLabels
            )
            state.detectedCount?.let { found ->
                Text(
                    text = if (found == 0) stringResource(R.string.occlusion_detected_none)
                    else pluralStringResource(R.plurals.occlusion_detected, found, found),
                    color = AbhyasColors.Dim,
                    fontSize = 12.5.sp
                )
            }
        }

        state.selected?.let { index ->
            val region = state.regions.getOrNull(index) ?: return@let
            Spacer(Modifier.height(8.dp))
            FieldLabel(stringResource(R.string.occlusion_hidden_label, index + 1))
            Spacer(Modifier.height(4.dp))
            AbhyasTextField(
                value = region.label,
                onValueChange = { viewModel.label(index, it) },
                placeholder = stringResource(R.string.occlusion_label_placeholder),
                singleLine = true
            )
            TextLink(
                text = stringResource(R.string.occlusion_remove),
                color = AbhyasColors.Again,
                onClick = { viewModel.remove(index) },
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        FieldLabel(stringResource(R.string.occlusion_card_title))
        Spacer(Modifier.height(4.dp))
        AbhyasTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = stringResource(R.string.occlusion_card_title_placeholder),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel(stringResource(R.string.review_tags_label))
        Spacer(Modifier.height(4.dp))
        AbhyasTextField(
            value = tags,
            onValueChange = { tags = it },
            placeholder = stringResource(R.string.tags_placeholder),
            singleLine = true
        )

        Spacer(Modifier.height(20.dp))
        val count = state.regions.size
        PrimaryButton(
            text = if (count == 0) stringResource(R.string.occlusion_draw_first)
            else pluralStringResource(R.plurals.review_add, count, count),
            enabled = count > 0,
            onClick = {
                viewModel.save(title, tags, fallbackTitle) { n -> labelFormat.format(n) }
            }
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(stringResource(R.string.action_cancel), onClick = viewModel::discard)
    }
}

/**
 * The picture, with a drag drawing a new mask and a tap selecting an existing one.
 *
 * Drawn at fit-to-width, capped in height so a tall portrait diagram still leaves the label field
 * on screen. Coordinates are converted to fractions of the drawn size the moment a gesture ends, so
 * nothing downstream ever sees a pixel.
 */
@Composable
private fun DrawingSurface(
    imageName: String,
    regions: List<Region>,
    selected: Int?,
    onAdd: (Region) -> Unit,
    onSelect: (Int?) -> Unit
) {
    val context = LocalContext.current
    val description = stringResource(R.string.a11y_occlusion_surface, regions.size)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<Bitmap?>(null, imageName) {
            value = loadStoredImage(context, imageName, widthPx)
        }
        val image = bitmap ?: run {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AbhyasColors.Saffron, modifier = Modifier.size(28.dp))
            }
            return@BoxWithConstraints
        }

        val ratio = image.width.toFloat() / image.height
        val width = minOf(maxWidth, 520.dp * ratio)
        var dragStart by remember { mutableStateOf<Offset?>(null) }
        var dragEnd by remember { mutableStateOf<Offset?>(null) }

        Box(
            modifier = Modifier
                .size(width, width / ratio)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = description }
                .pointerInput(regions) {
                    detectTapGestures { tap ->
                        val x = tap.x / size.width
                        val y = tap.y / size.height
                        // The last-drawn region wins where they overlap - it is the one on top.
                        onSelect(regions.indices.lastOrNull { regions[it].contains(x, y) })
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, amount ->
                            change.consume()
                            dragEnd = (dragEnd ?: change.position) + amount
                        },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                val region = Region(
                                    start.x / size.width, start.y / size.height,
                                    end.x / size.width, end.y / size.height
                                ).normalised()
                                if (!region.isTooSmall) onAdd(region)
                            }
                            dragStart = null
                            dragEnd = null
                        },
                        onDragCancel = { dragStart = null; dragEnd = null }
                    )
                }
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                regions.forEachIndexed { index, region ->
                    val (topLeft, rectSize) = place(region)
                    drawRoundRect(
                        AbhyasColors.Saffron.copy(alpha = if (index == selected) 0.85f else 0.6f),
                        topLeft, rectSize, CornerRadius(4.dp.toPx())
                    )
                    if (index == selected) {
                        drawRoundRect(
                            AbhyasColors.SaffronBright, topLeft, rectSize, CornerRadius(4.dp.toPx()),
                            style = Stroke(2.5.dp.toPx())
                        )
                    }
                }
                val start = dragStart
                val end = dragEnd
                if (start != null && end != null) {
                    drawRect(
                        AbhyasColors.SaffronBright,
                        topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                        size = androidx.compose.ui.geometry.Size(
                            kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)
                        ),
                        style = Stroke(2.dp.toPx())
                    )
                }
            }
        }
    }
}

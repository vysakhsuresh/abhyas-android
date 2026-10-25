package com.layerbit.abhyas.ui.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.R
import com.layerbit.abhyas.ui.components.AbhyasTextField
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.FieldLabel
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SecondaryButton
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.labelRes
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors

/** The most photos a single gallery pick reads. A chapter, not a camera roll. */
private const val MAX_PICKED_PAGES = 30

@Composable
fun CaptureScreen(deckId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel = repositoryViewModel(key = "capture-$deckId") {
        CaptureViewModel(it, appContext, deckId)
    }
    val step by viewModel.step.collectAsStateWithLifecycle()
    val batch by viewModel.batch.collectAsStateWithLifecycle()

    // The photo picker needs no storage permission at all - the system picker returns exactly the
    // images the user chose, which is why Abhyas never asks for gallery access.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::process) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_PAGES)
    ) { uris -> viewModel.processAll(uris) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = step) {
            is CaptureStep.Camera -> CameraViewfinder(
                title = stringResource(R.string.capture_title),
                subtitle = stringResource(R.string.capture_subtitle),
                pickLabel = stringResource(
                    if (batch) R.string.capture_choose_photos else R.string.capture_choose_photo
                ),
                onCaptured = viewModel::process,
                onPickImage = {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (batch) pickImages.launch(request) else pickImage.launch(request)
                },
                onCancel = onDone,
                extras = {
                    BatchBar(
                        batch = batch,
                        camera = current,
                        onToggle = viewModel::setBatch,
                        onReview = viewModel::review
                    )
                }
            )

            is CaptureStep.Reading -> ReadingStep(current)

            is CaptureStep.Empty -> ProblemStep(
                problem = current.problem,
                onRetake = viewModel::retake,
                onCancel = onDone
            )

            is CaptureStep.Review -> ReviewStep(
                items = current.items,
                onToggle = viewModel::toggleKeep,
                onEdit = viewModel::edit,
                onSetAll = viewModel::setAllKept,
                onAddPage = viewModel::addAnotherPage,
                onSave = viewModel::save
            )

            is CaptureStep.Saved -> SavedStep(count = current.count, onDone = onDone)
        }
    }
}

// ----------------------------------------------------------------------------------- batching

/**
 * The batch switch, and - once pages are collected - the tally and the way out to the review.
 *
 * What happened to the page just read is said here, on the camera, because in a batch the user
 * never leaves it: a page that came back empty has to be visible before they turn to the next one.
 */
@Composable
private fun BatchBar(
    batch: Boolean,
    camera: CaptureStep.Camera,
    onToggle: (Boolean) -> Unit,
    onReview: () -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = batch, role = Role.Switch, onValueChange = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmallCheck(checked = batch)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.capture_batch_label), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.capture_batch_hint),
                color = AbhyasColors.Dim,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }

    camera.lastPageProblem?.let { problem ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = problemText(problem),
            color = AbhyasColors.Again,
            fontSize = 12.5.sp,
            lineHeight = 17.sp
        )
    }
    camera.lastPageAdded?.let { added ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = pluralStringResource(R.plurals.capture_page_added, added, camera.pages, added),
            color = AbhyasColors.Good,
            fontSize = 12.5.sp
        )
    }

    if (camera.suggestions > 0) {
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = pluralStringResource(R.plurals.capture_review_all, camera.suggestions, camera.suggestions),
            onClick = onReview
        )
    }
}

@Composable
private fun SmallCheck(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) AbhyasColors.Saffron else AbhyasColors.SurfaceDim)
            .border(
                1.dp,
                if (checked) AbhyasColors.Saffron else AbhyasColors.BorderStrong,
                RoundedCornerShape(6.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, null, tint = AbhyasColors.OnSaffron, modifier = Modifier.size(15.dp))
        }
    }
}

// ------------------------------------------------------------------------------------ progress

@Composable
private fun ReadingStep(step: CaptureStep.Reading) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AbhyasColors.Saffron)
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (step.of > 1) {
                stringResource(R.string.capture_reading_of, step.page, step.of)
            } else {
                stringResource(R.string.capture_reading)
            },
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.capture_on_device),
            color = AbhyasColors.Muted,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun problemText(problem: PageProblem): String = when (problem) {
    PageProblem.Unreadable -> stringResource(R.string.problem_unreadable)
    is PageProblem.NoText -> stringResource(R.string.problem_no_text, stringResource(problem.script.labelRes()))
    PageProblem.NothingCardShaped -> stringResource(R.string.problem_nothing_card_shaped)
    PageProblem.AlreadyInDeck -> stringResource(R.string.problem_already_in_deck)
}

@Composable
private fun ProblemStep(problem: PageProblem, onRetake: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyState(title = stringResource(R.string.problem_title), message = problemText(problem))
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.action_try_another_page), onClick = onRetake)
        Spacer(Modifier.height(10.dp))
        SecondaryButton(stringResource(R.string.action_back_to_deck), onClick = onCancel)
    }
}

@Composable
private fun SavedStep(count: Int, onDone: () -> Unit) {
    // Landing straight back on the deck would leave the user unsure anything happened, so the
    // count is stated plainly before the screen closes.
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (count == 0) {
                stringResource(R.string.saved_none)
            } else {
                pluralStringResource(R.plurals.saved_count, count, count)
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (count == 0) R.string.saved_none_detail else R.string.saved_ready),
            color = AbhyasColors.Muted,
            fontSize = 14.5.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton(stringResource(R.string.action_back_to_deck), onClick = onDone)
    }
}

// -------------------------------------------------------------------------------------- review

@Composable
private fun ReviewStep(
    items: List<ReviewItem>,
    onToggle: (Int) -> Unit,
    onEdit: (Int, String, String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onAddPage: () -> Unit,
    onSave: (String) -> Unit
) {
    val keptCount = items.count { it.keep }
    val pages = items.maxOfOrNull { it.page } ?: 1
    var tags by rememberSaveable { mutableStateOf("") }

    // Clearing the navigation bar is the outer Column's job here: the list scrolls, but the
    // footer below it is fixed, and without this the "Add cards" button sits under the bar.
    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenTitle(
                    pluralStringResource(R.plurals.review_title, items.size, items.size),
                    if (pages > 1) {
                        pluralStringResource(R.plurals.review_subtitle_pages, pages, pages)
                    } else {
                        stringResource(R.string.review_subtitle)
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextLink(
                        text = stringResource(R.string.review_keep_all),
                        color = AbhyasColors.Saffron,
                        onClick = { onSetAll(true) },
                        fontSize = 13.5.sp
                    )
                    TextLink(
                        text = stringResource(R.string.review_drop_all),
                        color = AbhyasColors.Muted,
                        onClick = { onSetAll(false) },
                        fontSize = 13.5.sp
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                ReviewCard(
                    item = item,
                    showPage = pages > 1,
                    onToggle = { onToggle(item.id) },
                    onEdit = { front, back -> onEdit(item.id, front, back) }
                )
            }
            item {
                // Tags for the whole page at once - the moment a student knows which chapter and
                // which exam these cards belong to is the moment they photographed them.
                Spacer(Modifier.height(4.dp))
                FieldLabel(stringResource(R.string.review_tags_label))
                Spacer(Modifier.height(4.dp))
                AbhyasTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    placeholder = stringResource(R.string.tags_placeholder),
                    singleLine = true
                )
            }
        }

        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            PrimaryButton(
                text = if (keptCount == 0) {
                    stringResource(R.string.review_nothing_selected)
                } else {
                    pluralStringResource(R.plurals.review_add, keptCount, keptCount)
                },
                enabled = keptCount > 0,
                onClick = { onSave(tags) }
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton(stringResource(R.string.review_add_page), onClick = onAddPage)
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    showPage: Boolean,
    onToggle: () -> Unit,
    onEdit: (String, String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }

    // Each side keeps its own text, rather than reading it back out of the ViewModel's StateFlow.
    //
    // Two problems with the round trip. A keystroke went to the ViewModel and came back through
    // collectAsStateWithLifecycle, which resumes on the next frame - so the field was being handed a
    // value one frame behind what the user had just typed, which the text field then pushes back to
    // the IME as a correction. And because `onEdit` takes both sides, each lambda had to resend its
    // sibling from the captured `item`: edit the question against a stale `item` and the pre-edit
    // answer was written back over whatever the answer had become.
    //
    // Keyed on item.id, so the state follows the right row through the keyed list and is rebuilt
    // when setAllKept replaces every ReviewItem instance.
    var front by rememberSaveable(item.id) { mutableStateOf(item.front) }
    var back by rememberSaveable(item.id) { mutableStateOf(item.back) }

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(item.kind.labelRes()), if (item.keep) AbhyasColors.Saffron else AbhyasColors.Dim)
                if (showPage) Pill(stringResource(R.string.review_page, item.page), AbhyasColors.Dim)
            }
            Checkbox(checked = item.keep, onClick = onToggle)
        }

        Spacer(Modifier.height(12.dp))

        if (editing) {
            FieldLabel(stringResource(R.string.label_question))
            Spacer(Modifier.height(4.dp))
            AbhyasTextField(value = front, onValueChange = {
                front = it
                onEdit(it, back)
            })
            Spacer(Modifier.height(10.dp))
            FieldLabel(stringResource(R.string.label_answer))
            Spacer(Modifier.height(4.dp))
            AbhyasTextField(value = back, onValueChange = {
                back = it
                onEdit(front, it)
            })
            Spacer(Modifier.height(10.dp))
            TextLink(
                text = stringResource(R.string.review_done_editing),
                color = AbhyasColors.Saffron,
                onClick = { editing = false },
                fontSize = 13.5.sp
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.a11y_edit_card)
                    ) { editing = true }
            ) {
                Text(
                    text = front,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.keep) AbhyasColors.Text else AbhyasColors.Dim,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = back,
                    fontSize = 14.sp,
                    color = if (item.keep) AbhyasColors.Muted else AbhyasColors.Dim,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * A plain square checkbox - Material3's has a ripple and padding that unbalance the card header.
 *
 * Two nested boxes on purpose. The 26dp square is the design; the 48dp box around it is the hit
 * area and the only node a screen reader sees. `toggleable` rather than `clickable` is what gives it
 * a checked state to announce - as a bare clickable it was an anonymous, stateless focus stop, so
 * there was no way to tell which suggestions were being kept.
 */
@Composable
private fun Checkbox(checked: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.a11y_keep_card)
    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        CheckboxFace(checked)
    }
}

@Composable
private fun CheckboxFace(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) AbhyasColors.Saffron else AbhyasColors.SurfaceDim)
            .border(
                width = 1.dp,
                color = if (checked) AbhyasColors.Saffron else AbhyasColors.BorderStrong,
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AbhyasColors.OnSaffron,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

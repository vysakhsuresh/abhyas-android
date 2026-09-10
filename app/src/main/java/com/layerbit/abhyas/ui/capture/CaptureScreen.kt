package com.layerbit.abhyas.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SecondaryButton
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors

@Composable
fun CaptureScreen(deckId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel = repositoryViewModel(key = "capture-$deckId") {
        CaptureViewModel(it, appContext, deckId)
    }
    val step by viewModel.step.collectAsStateWithLifecycle()

    // The photo picker needs no storage permission at all - the system picker returns exactly the
    // one image the user chose, which is why Abhyas never asks for gallery access.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::process) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = step) {
            is CaptureStep.Camera -> CameraStep(
                onCaptured = viewModel::process,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onCancel = onDone
            )

            is CaptureStep.Reading -> ReadingStep()

            is CaptureStep.Empty -> ProblemStep(
                message = current.reason,
                onRetake = viewModel::retake,
                onCancel = onDone
            )

            is CaptureStep.Review -> ReviewStep(
                items = current.items,
                onToggle = viewModel::toggleKeep,
                onEdit = viewModel::edit,
                onRetake = viewModel::retake,
                onSave = viewModel::save
            )

            is CaptureStep.Saved -> SavedStep(count = current.count, onDone = onDone)
        }
    }
}

// ------------------------------------------------------------------------------------- camera

@Composable
private fun CameraStep(
    onCaptured: (android.net.Uri) -> Unit,
    onPickImage: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { CameraCapture() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 28.dp)
    ) {
        Text(
            text = "Cancel",
            color = AbhyasColors.Muted,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onCancel)
        )
        Spacer(Modifier.height(18.dp))
        ScreenTitle("Photograph a page", "Fill the frame with the text. Good light helps.")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(AbhyasColors.SurfaceDim)
                .border(1.dp, AbhyasColors.Border, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (hasPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            camera.bind(ctx, lifecycleOwner, view)
                        }
                    }
                )
            } else {
                Text(
                    text = "Camera access is needed to photograph a page.\n" +
                        "You can also pick an existing photo instead.",
                    color = AbhyasColors.Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(28.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Choose photo",
                color = AbhyasColors.Muted,
                fontSize = 14.5.sp,
                modifier = Modifier.clickable(onClick = onPickImage)
            )

            ShutterButton(enabled = hasPermission) {
                camera.takePicture(context) { uri -> uri?.let(onCaptured) }
            }

            // Balances the shutter in the centre without adding a second action competing with it.
            Spacer(Modifier.size(88.dp, 1.dp))
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (enabled) AbhyasColors.Saffron else AbhyasColors.SaffronDim)
            .clickable(enabled = enabled, onClick = onClick)
    )
}

// ------------------------------------------------------------------------------------ progress

@Composable
private fun ReadingStep() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AbhyasColors.Saffron)
        Spacer(Modifier.height(20.dp))
        Text("Reading the page", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "This happens on your phone. Nothing is uploaded.",
            color = AbhyasColors.Muted,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProblemStep(message: String, onRetake: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyState(title = "Nothing to make cards from", message = message)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Try another page", onClick = onRetake)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Back to deck", onClick = onCancel)
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
            text = when (count) {
                0 -> "Nothing added"
                1 -> "1 card added"
                else -> "$count cards added"
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (count == 0) "You kept none of the suggestions."
            else "They are ready to study now.",
            color = AbhyasColors.Muted,
            fontSize = 14.5.sp
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton("Back to deck", onClick = onDone)
    }
}

// -------------------------------------------------------------------------------------- review

@Composable
private fun ReviewStep(
    items: List<ReviewItem>,
    onToggle: (Int) -> Unit,
    onEdit: (Int, String, String) -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit
) {
    val keptCount = items.count { it.keep }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 56.dp, bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenTitle(
                    "${items.size} suggestions",
                    "Uncheck what you do not want. Tap any card to edit it."
                )
            }
            items(items, key = { it.id }) { item ->
                ReviewCard(
                    item = item,
                    onToggle = { onToggle(item.id) },
                    onEdit = { front, back -> onEdit(item.id, front, back) }
                )
            }
        }

        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            PrimaryButton(
                text = when (keptCount) {
                    0 -> "Nothing selected"
                    1 -> "Add 1 card"
                    else -> "Add $keptCount cards"
                },
                enabled = keptCount > 0,
                onClick = onSave
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Photograph another page", onClick = onRetake)
        }
    }
}

@Composable
private fun ReviewCard(item: ReviewItem, onToggle: () -> Unit, onEdit: (String, String) -> Unit) {
    var editing by remember { mutableStateOf(false) }

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill(item.kind.label, if (item.keep) AbhyasColors.Saffron else AbhyasColors.Dim)
            Checkbox(checked = item.keep, onClick = onToggle)
        }

        Spacer(Modifier.height(12.dp))

        if (editing) {
            EditableSide(label = "Question", value = item.front) { onEdit(it, item.back) }
            Spacer(Modifier.height(10.dp))
            EditableSide(label = "Answer", value = item.back) { onEdit(item.front, it) }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Done editing",
                color = AbhyasColors.Saffron,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { editing = false }
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().clickable { editing = true }) {
                Text(
                    text = item.front,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.keep) AbhyasColors.Text else AbhyasColors.Dim,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = item.back,
                    fontSize = 14.sp,
                    color = if (item.keep) AbhyasColors.Muted else AbhyasColors.Dim,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun EditableSide(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label.uppercase(), color = AbhyasColors.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AbhyasColors.SurfaceDim,
                unfocusedContainerColor = AbhyasColors.SurfaceDim,
                focusedIndicatorColor = AbhyasColors.Saffron,
                unfocusedIndicatorColor = AbhyasColors.Border
            )
        )
    }
}

/** A plain square checkbox - Material3's has a ripple and padding that unbalance the card header. */
@Composable
private fun Checkbox(checked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) AbhyasColors.Saffron else AbhyasColors.SurfaceDim)
            .border(
                width = 1.dp,
                color = if (checked) AbhyasColors.Saffron else AbhyasColors.BorderStrong,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
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

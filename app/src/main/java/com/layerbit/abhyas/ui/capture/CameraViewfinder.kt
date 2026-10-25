package com.layerbit.abhyas.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.layerbit.abhyas.R
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.theme.AbhyasColors

/**
 * The viewfinder, shutter and gallery link - shared by page capture and by image-occlusion cards,
 * which both start from "photograph something".
 *
 * [extras] sits between the viewfinder and the shutter row: the batch tally on the capture screen,
 * nothing on the occlusion one.
 */
@Composable
fun CameraViewfinder(
    title: String,
    subtitle: String,
    pickLabel: String,
    onCaptured: (Uri) -> Unit,
    onPickImage: () -> Unit,
    onCancel: () -> Unit,
    extras: @Composable ColumnScope.() -> Unit = {}
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

    var captureFailed by remember { mutableStateOf(false) }

    // Release the camera when this step leaves composition.
    //
    // bindToLifecycle ties the session to the NavBackStackEntry, which stays RESUMED for the whole
    // capture route - so without this the sensor kept streaming through OCR and through the entire
    // review screen, where the user may be reading and editing for minutes, into a PreviewView that
    // is no longer attached to anything. It was only released when the route was finally popped.
    // Retaking rebinds, which is what the camera step does on re-entry anyway.
    DisposableEffect(camera) {
        onDispose { camera.unbind() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenPadding(extraBottom = 28.dp))
    ) {
        TextLink(
            text = stringResource(R.string.action_cancel),
            color = AbhyasColors.Muted,
            onClick = onCancel,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(18.dp))
        ScreenTitle(title, subtitle)

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
                    text = stringResource(R.string.capture_camera_needed),
                    color = AbhyasColors.Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(28.dp)
                )
            }
        }

        if (captureFailed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.capture_save_failed),
                color = AbhyasColors.Again,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        extras()

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextLink(
                text = pickLabel,
                color = AbhyasColors.Muted,
                onClick = onPickImage,
                fontSize = 14.5.sp
            )

            // Enabled on a bound camera, not merely on the permission - which is granted well
            // before the provider resolves, leaving a live shutter that called through to nothing.
            ShutterButton(enabled = hasPermission && camera.isReady) {
                captureFailed = false
                camera.takePicture(context) { uri ->
                    // A capture can fail for reasons the user can do something about - no storage
                    // left, the camera grabbed by another app - and failing in silence makes the
                    // shutter look broken. They tap it again, and again.
                    if (uri == null) captureFailed = true else onCaptured(uri)
                }
            }

            // Balances the shutter in the centre without adding a second action competing with it.
            Spacer(Modifier.size(88.dp, 1.dp))
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.capture_take_photo)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (enabled) AbhyasColors.Saffron else AbhyasColors.SaffronDim)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // The shutter has no icon and no label - it is a saffron circle, which is the right
            // design and leaves nothing for a screen reader to read out. Naming it here is the only
            // thing that makes the app's primary action usable with TalkBack. `disabled()` is what
            // gets it announced as "Take photo, disabled" rather than silently dropped from the
            // tree, since clickable(enabled = false) removes the click action and with it the node.
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
    )
}

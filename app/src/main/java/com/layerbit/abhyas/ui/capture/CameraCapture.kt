package com.layerbit.abhyas.ui.capture

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * The CameraX plumbing, kept away from the Compose code so the capture screen stays readable.
 *
 * One [ImageCapture] use case is held for the life of the screen and rebound whenever the
 * lifecycle owner changes, which is what stops the preview going black after the app is
 * backgrounded and returned to.
 */
class CameraCapture {

    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null

    /**
     * Held only to read the current display rotation at shutter time. Same lifetime as this object
     * - both are remembered by the capture composable - and cleared in [unbind].
     */
    private var previewView: PreviewView? = null

    /**
     * Set by [unbind], checked once the provider future resolves.
     *
     * [bind] is asynchronous: the provider arrives on a listener some frames later. A screen that is
     * dismissed quickly - a mistaken tap on "Add cards", straight back out - can therefore unbind
     * before there is anything to unbind, and the listener would then bind a session with nobody left
     * to release it, holding the camera until the process died.
     */
    private var released = false

    fun bind(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        released = false
        this.previewView = previewView
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            // Everything is inside the try, including the `get()`. A device whose camera stack
            // failed to initialise makes that call throw ExecutionException, and this listener runs
            // on the main thread - so an uncaught throw here is not a camera that does not work,
            // it is the app disappearing the moment the user taps "Add cards".
            try {
                val cameraProvider = providerFuture.get()
                if (released) return@addListener
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    // A page of notes is a detail shot, not a snapshot: legible small print matters
                    // far more here than shutter latency does.
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    // Quality, but not an unbounded amount of it. With no resolution asked for,
                    // CameraX takes the largest JPEG the sensor offers - 50 megapixels and up on a
                    // current mid-range phone - and a page of A4 gains nothing from it: the text is
                    // already resolved several times over at 3 MP, and the recognisers ask for
                    // 1280x720. What it costs is real: a dozen megabytes written to the cache for
                    // every shot, a slow encode, and a file the reader then has to sub-sample back
                    // down. RESOLUTION_STRATEGY_FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER so a sensor
                    // with no mode near this still gives us its nearest.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(2560, 1920),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                imageCapture = capture
            } catch (_: Exception) {
                // No usable back camera, another app holds it, or the camera service never came
                // up. The screen keeps its gallery import path, which is why this is survivable
                // rather than fatal.
                imageCapture = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Release the camera.
     *
     * Binding to the lifecycle only unbinds when the lifecycle itself stops, and the capture screen
     * outlives its camera step by a long way - OCR, then a review list the user may sit on for
     * minutes, editing card after card. For all of that the sensor stays powered and streaming
     * frames into a preview nobody is looking at, which is a visible battery cost and keeps the
     * camera unavailable to every other app on the phone.
     */
    fun unbind() {
        released = true
        provider?.unbindAll()
        provider = null
        imageCapture = null
        previewView = null
    }

    val isReady: Boolean get() = imageCapture != null

    /**
     * Take the photo into the cache directory and hand back its Uri.
     *
     * The cache is the right home for it: the image only has to survive long enough to be read by
     * OCR, the text is what gets kept, and leaving a copy of someone's notes in shared storage
     * would be a privacy cost with no benefit.
     */
    fun takePicture(context: Context, onResult: (Uri?) -> Unit) {
        val capture = imageCapture ?: run {
            onResult(null)
            return
        }

        // Re-read the rotation on every shot. The Activity declares configChanges for orientation,
        // so it is never recreated and the camera is never rebound - which means a targetRotation
        // fixed at bind time describes however the phone was held when the screen opened. Turn the
        // phone sideways to fit a wide diagram and the EXIF would claim portrait, and the page would
        // reach the recogniser lying on its side.
        previewView?.display?.rotation?.let { capture.targetRotation = it }

        val file = File.createTempFile("page-", ".jpg", context.cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onResult(output.savedUri ?: Uri.fromFile(file))
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    onResult(null)
                }
            }
        )
    }
}

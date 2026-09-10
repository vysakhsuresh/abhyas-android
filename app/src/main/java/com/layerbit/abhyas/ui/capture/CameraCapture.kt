package com.layerbit.abhyas.ui.capture

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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

    fun bind(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                // A page of notes is a detail shot, not a snapshot: legible small print matters
                // far more here than shutter latency does.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                imageCapture = capture
            } catch (_: Exception) {
                // No usable back camera, or another app holds it. The screen keeps its gallery
                // import path, which is why this is survivable rather than fatal.
                imageCapture = null
            }
        }, ContextCompat.getMainExecutor(context))
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

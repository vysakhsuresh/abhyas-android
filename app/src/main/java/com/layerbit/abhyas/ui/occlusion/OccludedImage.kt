package com.layerbit.abhyas.ui.occlusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.layerbit.abhyas.data.occlusion.ImageStore
import com.layerbit.abhyas.data.occlusion.Occlusion
import com.layerbit.abhyas.data.occlusion.Region
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Load a stored picture, sub-sampled to roughly the size it will be drawn at.
 *
 * A 2048px picture decoded whole is 16 MB of pixels to fill a 400dp-wide box; sampling down to the
 * box keeps a long study session of diagram cards from growing the heap one picture at a time.
 */
suspend fun loadStoredImage(context: Context, name: String, targetWidthPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val file = ImageStore.file(context, name)?.takeIf { it.exists() } ?: return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidthPx.coerceAtLeast(1)) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

/**
 * A stored picture with its masks drawn over it.
 *
 * Unrevealed, the region being asked about is a solid saffron block and every other region is only
 * outlined, so the user can see where the other labels sit without being shown the one in question.
 * Revealed, the block lifts and the region keeps a bright outline so the eye goes straight to the
 * answer.
 */
@Composable
fun OccludedImage(
    imageName: String,
    occlusion: Occlusion,
    revealed: Boolean,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 460.dp
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<Bitmap?>(initialValue = null, imageName, widthPx) {
            value = loadStoredImage(context, imageName, widthPx)
        }

        val image = bitmap
        if (image == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp)
                    .clip(RoundedCornerShape(14.dp)).background(AbhyasColors.SurfaceDim),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AbhyasColors.Saffron, modifier = Modifier.size(28.dp))
            }
            return@BoxWithConstraints
        }

        val ratio = image.width.toFloat() / image.height
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(modifier = Modifier.size(width, width / ratio).clip(RoundedCornerShape(12.dp))) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                occlusion.regions.forEachIndexed { index, region ->
                    if (index == occlusion.target) {
                        if (revealed) outline(region, AbhyasColors.SaffronBright, 3.dp.toPx())
                        else mask(region)
                    } else {
                        outline(region, AbhyasColors.Muted.copy(alpha = 0.85f), 1.5.dp.toPx())
                    }
                }
            }
        }
    }
}

internal fun DrawScope.mask(region: Region) {
    val (topLeft, rectSize) = place(region)
    drawRoundRect(AbhyasColors.Saffron, topLeft, rectSize, CornerRadius(4.dp.toPx()))
    drawRoundRect(
        AbhyasColors.OnSaffron.copy(alpha = 0.5f), topLeft, rectSize, CornerRadius(4.dp.toPx()),
        style = Stroke(1.dp.toPx())
    )
}

internal fun DrawScope.outline(region: Region, color: androidx.compose.ui.graphics.Color, width: Float) {
    val (topLeft, rectSize) = place(region)
    drawRoundRect(color, topLeft, rectSize, CornerRadius(4.dp.toPx()), style = Stroke(width))
}

internal fun DrawScope.place(region: Region): Pair<Offset, Size> =
    Offset(region.left * size.width, region.top * size.height) to
        Size(region.width * size.width, region.height * size.height)

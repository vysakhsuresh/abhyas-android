package com.layerbit.abhyas.data.occlusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures behind image-occlusion cards, kept in the app's own private storage.
 *
 * `filesDir`, not MediaStore and not the cache. The gallery would make a photo of someone's anatomy
 * notes visible to every app with photo access and to the user's cloud backup; the cache can be
 * cleared by the system at any moment, which would blank every diagram card the user has. A text
 * card keeps the text and not the page; a picture card has no text to keep, so here the picture is
 * the card and is looked after like one.
 *
 * Files are named by a random id and referenced by name alone - never by path - so a backup restored
 * on another phone, whose `filesDir` is somewhere else, still resolves.
 */
object ImageStore {

    private const val DIRECTORY = "images"

    /**
     * Long side of a stored picture. A diagram is read on a phone screen, and 2048 px is sharper
     * than any of them while keeping a typical photo near 300 KB - which matters because every one
     * travels inside the backup file.
     */
    private const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 85

    fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** The file for a stored name, or null for a name that could escape the directory. */
    fun file(context: Context, name: String): File? =
        if (isSafeName(name)) File(directory(context), name) else null

    /**
     * Only names this store could have produced. A backup file is untrusted input, and a name like
     * `../databases/abhyas.db` written back verbatim would overwrite the collection itself.
     */
    fun isSafeName(name: String): Boolean = ImageNames.isSafe(name)

    /**
     * Copy the picture at [uri] into the store, upright and bounded, and return its name.
     *
     * EXIF rotation is baked in here, unlike the OCR path: masks are drawn in the picture's own
     * coordinates, so the stored pixels have to be the way up the user saw them while drawing.
     */
    suspend fun save(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image: $uri")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Could not decode: $uri")

        val rotation = runCatching {
            resolver.openInputStream(uri).use { stream ->
                stream?.let {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }
        }.getOrNull()

        val upright = scaleAndRotate(decoded, degreesFor(rotation))
        val name = UUID.randomUUID().toString() + ".jpg"
        try {
            File(directory(context), name).outputStream().use { out ->
                if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("Could not encode picture")
                }
            }
        } finally {
            upright.recycle()
        }
        name
    }

    /** Write bytes from a backup under their original name, unless it is unsafe or already here. */
    fun restore(context: Context, name: String, bytes: ByteArray): Boolean {
        val target = file(context, name) ?: return false
        if (target.exists()) return true
        return runCatching { target.writeBytes(bytes); true }.getOrDefault(false)
    }

    fun read(context: Context, name: String): ByteArray? =
        file(context, name)?.takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Delete every picture no card refers to any more.
     *
     * Run after deletes and on start-up rather than at each delete site, because a deck delete
     * cascades in SQLite where no Kotlin code sees the individual cards go - and a sweep against what
     * is still referenced cannot get that wrong.
     */
    fun sweep(context: Context, referenced: Set<String>) {
        runCatching {
            directory(context).listFiles()?.forEach { file ->
                if (file.name !in referenced) file.delete()
            }
        }
    }

    private fun degreesFor(orientation: Int?): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun scaleAndRotate(source: Bitmap, degrees: Int): Bitmap {
        val longSide = maxOf(source.width, source.height)
        val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        if (scale == 1f && degrees == 0) return source
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees.toFloat())
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source) source.recycle()
        return result
    }
}

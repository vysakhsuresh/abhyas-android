package com.layerbit.abhyas.data.diagnostics

import android.content.Context
import android.os.Build
import com.layerbit.abhyas.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes, written to a file on the phone and nowhere else.
 *
 * Abhyas has no INTERNET permission and so no crash reporter, which left two ways to learn the app
 * had crashed: the user writing in, or a one-star review. This keeps the no-network guarantee and
 * still gives the user something to send: the stack trace is appended to a private file, and the
 * only way it leaves the phone is the user opening Settings, reading it, and choosing to email it
 * from their own mail app. Nothing here ever sends anything.
 *
 * What is written is the trace and the device - never card text, deck names or anything else
 * from the collection. A trace can still quote an exception message, which is why the user reads
 * the whole file before it goes anywhere.
 */
object CrashLog {

    private const val FILE_NAME = "crash-log.txt"

    /**
     * The file is capped so a crash loop cannot fill the phone. The newest reports matter most, so
     * when it grows past this the oldest half is dropped.
     */
    private const val MAX_BYTES = 96 * 1024

    private const val SEPARATOR = "------------------------------------------------------------"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Record every uncaught exception, then hand it on to whatever handler was there before - the
     * platform's, which shows the "app has stopped" dialog and ends the process. Swallowing it
     * instead would leave the app running in whatever broken state threw.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Must not throw: an exception from inside the handler replaces the original crash,
            // and the report the user would have sent describes the logger instead of the bug.
            runCatching { append(appContext, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Written synchronously - the process is about to die, so there is no later to defer to. */
    internal fun append(context: Context, threadName: String, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            appendLine(SEPARATOR)
            appendLine("Time:    " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            appendLine("App:     Abhyas ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread:  $threadName")
            appendLine()
            append(trace)
        }

        val file = file(context)
        val existing = if (file.exists()) file.readText() else ""
        var combined = existing + entry
        if (combined.length > MAX_BYTES) {
            // Cut at a report boundary, so the file never starts half way through a trace.
            val cut = combined.indexOf(SEPARATOR, combined.length - MAX_BYTES / 2)
            combined = if (cut > 0) combined.substring(cut) else combined.takeLast(MAX_BYTES / 2)
        }
        file.writeText(combined)
    }

    /** The whole log, or null if nothing has crashed. */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() && it.length() > 0 }?.let { runCatching { it.readText() }.getOrNull() }

    /** How many crashes the file holds, for the Settings row. */
    fun count(context: Context): Int =
        read(context)?.split(SEPARATOR)?.count { it.isNotBlank() } ?: 0

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { File(context.cacheDir, SHARE_DIRECTORY).deleteRecursively() }
    }

    /**
     * A copy of the log where the FileProvider can serve it, and the content Uri for it.
     *
     * A copy, so the provider never has to expose `filesDir` - where the database lives - even
     * read-only. Overwritten on each send and deleted with the log.
     */
    fun shareableUri(context: Context): android.net.Uri? {
        val text = read(context) ?: return null
        return runCatching {
            val dir = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
            val copy = File(dir, FILE_NAME).apply { writeText(text) }
            androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".diagnostics", copy
            )
        }.getOrNull()
    }

    private const val SHARE_DIRECTORY = "diagnostics"
}

package com.layerbit.abhyas

import android.app.Application
import com.layerbit.abhyas.data.reminder.ReminderPreferences
import com.layerbit.abhyas.data.reminder.ReminderScheduler
import com.layerbit.abhyas.data.repo.AbhyasRepository

class AbhyasApplication : Application() {
    /**
     * One repository for the process. Screens reach it through their ViewModel rather than
     * holding a reference, so nothing in the UI ever touches Room directly.
     */
    val repository: AbhyasRepository by lazy { AbhyasRepository(this) }

    override fun onCreate() {
        super.onCreate()
        rearmReminder()
        sweepOrphanedPages()
    }

    /**
     * Delete any photographed page left in the cache by a previous run.
     *
     * The capture flow deletes each photo as soon as OCR has read it, which covers every ordinary
     * path including the failures. What it cannot cover is the process dying in between - a crash,
     * or Android reclaiming memory while the user was mid-review - and what is left behind then is a
     * full-resolution photograph of somebody's notes sitting in storage indefinitely. Abhyas keeps
     * the text and not the page on purpose; this is what makes that true after a crash as well.
     *
     * Cheap enough to do on the main thread: `listFiles` on one directory, matching a prefix.
     */
    private fun sweepOrphanedPages() {
        runCatching {
            cacheDir.listFiles { file -> file.name.startsWith("page-") && file.extension == "jpg" }
                ?.forEach { it.delete() }
        }
    }

    /**
     * Re-arm the reminder chain if the user has one switched on.
     *
     * The chain is a sequence of one-shot jobs, each scheduling the next, which means a single lost
     * link ends it for good - and the toggle in Settings would still read "on", so nobody would
     * think to look. Jobs do get lost: a force-stop, a crash during the run, "clear data" on the
     * WorkManager database, or an OEM battery manager deciding the app has been idle long enough.
     *
     * Re-anchoring here is cheap and idempotent - it leaves exactly one pending job at the next
     * occurrence of the chosen time - and it doubles as the fix for wall-clock drift, since a
     * device that has crossed a timezone or a DST boundary gets its delay recomputed on next
     * launch. Consent is not checked here on purpose: the worker re-checks it at fire time and
     * declines to re-arm if it has gone, which keeps one gate rather than two that can disagree.
     */
    private fun rearmReminder() {
        val prefs = ReminderPreferences(this)
        if (prefs.enabled) ReminderScheduler.schedule(this, prefs.hour, prefs.minute)
    }
}

package com.layerbit.abhyas.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.backup.BackupCodec
import com.layerbit.abhyas.data.reminder.ReminderPreferences
import com.layerbit.abhyas.data.reminder.ReminderScheduler
import com.layerbit.abhyas.data.repo.AbhyasRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val remindersEnabled: Boolean = false,
    val hour: Int = 19,
    val minute: Int = 0,
    /** Set after an export, restore or failure, and cleared once the UI has shown it. */
    val message: String? = null
)

class SettingsViewModel(
    private val repository: AbhyasRepository,
    private val appContext: Context
) : ViewModel() {

    private val prefs = ReminderPreferences(appContext)

    private val _state = MutableStateFlow(
        SettingsState(
            remindersEnabled = prefs.enabled,
            hour = prefs.hour,
            minute = prefs.minute
        )
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /**
     * Turn reminders on or off.
     *
     * The caller has already obtained the notification permission if one was needed - this is
     * only ever reached with consent in hand. Switching off cancels the pending job immediately
     * rather than letting one last reminder through, because "off" has to mean off now.
     */
    fun setRemindersEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        _state.value = _state.value.copy(remindersEnabled = enabled)

        if (enabled) {
            ReminderScheduler.schedule(appContext, prefs.hour, prefs.minute)
        } else {
            ReminderScheduler.cancel(appContext)
        }
    }

    fun setTime(hour: Int, minute: Int) {
        prefs.hour = hour
        prefs.minute = minute
        _state.value = _state.value.copy(hour = hour, minute = minute)

        // Re-anchor the pending job, but only if reminders are actually on. Scheduling here
        // regardless would quietly switch them on for someone who was just browsing the picker.
        if (prefs.enabled) ReminderScheduler.schedule(appContext, hour, minute)
    }

    /**
     * Reminders can be revoked from system settings without the app ever being opened, so the
     * stored flag can outlive the permission. Called when Settings is shown.
     */
    fun reconcilePermission(granted: Boolean) {
        if (!granted && prefs.enabled) {
            prefs.enabled = false
            ReminderScheduler.cancel(appContext)
            _state.value = _state.value.copy(
                remindersEnabled = false,
                message = "Reminders were turned off because notification permission was revoked."
            )
        }
    }

    // ------------------------------------------------------------------------------- backup

    /**
     * The in-flight export or restore. One at a time, and never two of either: both are whole-
     * collection operations, and a second tap while the first is still running would duplicate a
     * restore into the collection.
     */
    private var busy: Job? = null

    fun exportTo(write: (String) -> Boolean) {
        if (busy?.isActive == true) return
        busy = viewModelScope.launch {
            val message = try {
                val backup = repository.exportBackup()
                // Encoding and writing both move off the main thread. A thousand cards is a
                // megabyte of JSON built by string concatenation and then pushed through a SAF
                // stream that may be backed by cloud storage - on the main thread that is a frozen
                // UI and, for a large collection, an ANR.
                val ok = withContext(Dispatchers.IO) { write(BackupCodec.encode(backup)) }
                if (ok) {
                    "Exported ${count(backup.decks.size, "deck")} and ${count(backup.cards.size, "card")}."
                } else {
                    "Could not write the backup file."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Throwable: a full disk surfaces as an IOException, but encoding a very large
                // collection can exhaust the heap, and an OutOfMemoryError escaping here would take
                // the process down while the user watched a file dialog.
                "Could not write the backup file."
            }
            _state.value = _state.value.copy(message = message)
        }
    }

    fun restoreFrom(read: () -> String?) {
        if (busy?.isActive == true) return
        busy = viewModelScope.launch {
            val message = try {
                val text = withContext(Dispatchers.IO) { read() }
                when {
                    text.isNullOrBlank() -> "Could not read that file."
                    else -> restore(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The restore itself is one transaction, so a failure part-way through has rolled
                // back - which is what lets this promise the collection is untouched.
                "That backup could not be restored. Nothing was changed."
            }
            _state.value = _state.value.copy(message = message)
        }
    }

    private suspend fun restore(text: String): String {
        val backup = withContext(Dispatchers.IO) {
            runCatching { BackupCodec.decode(text) }.getOrNull()
        }

        return when {
            // Told apart on purpose. A file that will not parse is damaged - truncated by a full
            // disk, or cut short by a sync that stopped half way - and the honest advice is to try
            // another copy. A file that parses to nothing is the wrong file. Reporting both as
            // "that does not look like an Abhyas backup" sent people to look for a better file when
            // what they had was the right file, broken.
            backup == null ->
                "That file is damaged or incomplete, so nothing was restored. Try another copy."
            backup.decks.isEmpty() -> "That does not look like an Abhyas backup."
            else -> {
                val (decks, cards) = repository.restoreBackup(backup)
                "Restored ${count(decks, "deck")} and ${count(cards, "card")} alongside what you had."
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/** "1 deck", "3 decks" - the backup messages read as a sentence a person wrote. */
private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

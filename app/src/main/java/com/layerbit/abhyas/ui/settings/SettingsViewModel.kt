package com.layerbit.abhyas.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.backup.BackupCodec
import com.layerbit.abhyas.data.reminder.ReminderPreferences
import com.layerbit.abhyas.data.reminder.ReminderScheduler
import com.layerbit.abhyas.data.repo.AbhyasRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun exportTo(write: (String) -> Boolean) {
        viewModelScope.launch {
            val backup = repository.exportBackup()
            val ok = runCatching { write(BackupCodec.encode(backup)) }.getOrDefault(false)
            _state.value = _state.value.copy(
                message = if (ok) {
                    "Exported ${count(backup.decks.size, "deck")} and ${count(backup.cards.size, "card")}."
                } else {
                    "Could not write the backup file."
                }
            )
        }
    }

    fun restoreFrom(read: () -> String?) {
        viewModelScope.launch {
            val text = runCatching { read() }.getOrNull()
            if (text.isNullOrBlank()) {
                _state.value = _state.value.copy(message = "Could not read that file.")
                return@launch
            }
            val backup = runCatching { BackupCodec.decode(text) }.getOrNull()
            if (backup == null || backup.decks.isEmpty()) {
                _state.value = _state.value.copy(
                    message = "That does not look like an Abhyas backup."
                )
                return@launch
            }
            val (decks, cards) = repository.restoreBackup(backup)
            _state.value = _state.value.copy(
                message = "Restored ${count(decks, "deck")} and ${count(cards, "card")} alongside what you had."
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/** "1 deck", "3 decks" - the backup messages read as a sentence a person wrote. */
private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

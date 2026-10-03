package com.layerbit.abhyas.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.data.backup.BackupCodec
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SectionLabel
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel = repositoryViewModel { SettingsViewModel(it, appContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    // The permission can be taken away in system settings while the app is closed, leaving the
    // stored preference claiming reminders are on when nothing can ever be posted.
    LaunchedEffect(Unit) { viewModel.reconcilePermission(notificationsAllowed()) }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Only a granted permission switches reminders on. A denial leaves them off and says so,
        // rather than storing an intention the system will never honour.
        if (granted) viewModel.setRemindersEnabled(true)
    }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            viewModel.exportTo { text ->
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
                    } != null
                }.getOrDefault(false)
            }
        }
    }

    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.restoreFrom {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }
        }
    }

    var pickingTime by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextLink("Back", AbhyasColors.Muted, onBack)
            Spacer(Modifier.height(4.dp))
            ScreenTitle("Settings")
        }

        item {
            Card {
                // The whole row is the switch, not the 50x30dp box at the end of it.
                //
                // With the interaction on the box, Compose emitted two unrelated nodes: a labelled
                // static text and an anonymous clickable with no role and no on/off state. A screen
                // reader could reach the control but could not say what it was or whether it was on,
                // and the 50x30dp box was also the entire touch target - foundation's `clickable`
                // does not expand one. `toggleable` with Role.Switch on the row merges the label
                // with the state, so it reads "Daily reminder, off" and is 55dp tall.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.remindersEnabled,
                            role = Role.Switch,
                            onValueChange = { wanted ->
                                if (!wanted) {
                                    viewModel.setRemindersEnabled(false)
                                } else if (notificationsAllowed()) {
                                    viewModel.setRemindersEnabled(true)
                                } else {
                                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Daily reminder", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Off unless you turn it on.",
                            color = AbhyasColors.Muted,
                            fontSize = 13.sp
                        )
                    }
                    Toggle(checked = state.remindersEnabled)
                }

                if (state.remindersEnabled) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { pickingTime = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Remind me at", color = AbhyasColors.Muted, fontSize = 14.sp)
                        Text(
                            text = formatTime(state.hour, state.minute),
                            color = AbhyasColors.Saffron,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "The reminder stays quiet when there is nothing due, and when you " +
                        "have already studied that day. It never leaves your phone - there is " +
                        "no server involved and nothing is counted anywhere else.",
                    color = AbhyasColors.Dim,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        item {
            Card {
                SectionLabel("Backup", fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Abhyas has no account and no sync, so this file is the only way your " +
                        "collection survives a lost phone. It carries your schedules too, not " +
                        "just the words.",
                    color = AbhyasColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(16.dp))
                ActionRow("Export to a file") {
                    exportFile.launch(BackupCodec.suggestedFileName(today()))
                }
                ActionRow("Restore from a file") {
                    importFile.launch(arrayOf(BackupCodec.MIME_TYPE, "text/plain", "*/*"))
                }
                Text(
                    text = "Restoring adds to what you have rather than replacing it, so the " +
                        "wrong file costs you a few decks to delete instead of everything.",
                    color = AbhyasColors.Dim,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        item {
            Card {
                ActionRow("About Abhyas", onAbout)
            }
        }

        state.message?.let { message ->
            item {
                Card {
                    Text(message, color = AbhyasColors.Text, fontSize = 13.5.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(10.dp))
                    TextLink("Dismiss", AbhyasColors.Saffron, { viewModel.clearMessage() }, fontSize = 13.sp)
                }
            }
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            hour = state.hour,
            minute = state.minute,
            onPick = { h, m ->
                viewModel.setTime(h, m)
                pickingTime = false
            },
            onDismiss = { pickingTime = false }
        )
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = AbhyasColors.Saffron,
        fontSize = 14.5.sp,
        fontWeight = FontWeight.Medium,
        // The 14dp of spacing below this row used to be a Spacer *outside* the clickable, which
        // separated the rows visually while leaving each one a ~20dp target. Folding it into the
        // padding inside the clickable spends the same pixels on something a finger can hit.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

/**
 * A plain switch - Material3's carries its own colour scheme and fights the palette.
 *
 * Purely a drawing. The toggling lives on the enclosing row so the label and the state are announced
 * together, and `clearAndSetSemantics` keeps this from also appearing as a second, nameless focus
 * stop for the same control.
 */
@Composable
private fun Toggle(checked: Boolean) {
    Box(
        modifier = Modifier
            .clearAndSetSemantics {}
            .width(50.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) AbhyasColors.Saffron else AbhyasColors.SurfaceDim)
            .border(
                width = 1.dp,
                color = if (checked) AbhyasColors.Saffron else AbhyasColors.BorderStrong,
                shape = RoundedCornerShape(999.dp)
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) AbhyasColors.OnSaffron else AbhyasColors.Muted)
        )
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val suffix = if (hour < 12) "am" else "pm"
    val display = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(display, minute, suffix)
}

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

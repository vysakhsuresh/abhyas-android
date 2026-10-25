package com.layerbit.abhyas.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.BuildConfig
import com.layerbit.abhyas.R
import com.layerbit.abhyas.brand.BrandLinks
import com.layerbit.abhyas.data.backup.BackupCodec
import com.layerbit.abhyas.data.diagnostics.CrashLog
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.DialogAction
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SectionLabel
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel = repositoryViewModel { SettingsViewModel(it, appContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
            viewModel.exportTo { context.contentResolver.openOutputStream(uri) }
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
    var viewingLog by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextLink(stringResource(R.string.action_back), AbhyasColors.Muted, onBack)
            Spacer(Modifier.height(4.dp))
            ScreenTitle(stringResource(R.string.settings_title))
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
                        Text(stringResource(R.string.reminder_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.reminder_off_default),
                            color = AbhyasColors.Muted,
                            fontSize = 13.sp
                        )
                    }
                    Toggle(checked = state.remindersEnabled)
                }

                if (state.remindersEnabled) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { pickingTime = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.reminder_at), color = AbhyasColors.Muted, fontSize = 14.sp)
                        Text(
                            text = formatTime(context, state.hour, state.minute),
                            color = AbhyasColors.Saffron,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.reminder_explainer),
                    color = AbhyasColors.Dim,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        item {
            Card {
                SectionLabel(stringResource(R.string.backup_title), fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.backup_explainer),
                    color = AbhyasColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(16.dp))
                ActionRow(stringResource(R.string.backup_export)) {
                    exportFile.launch(BackupCodec.suggestedFileName(today()))
                }
                ActionRow(stringResource(R.string.backup_restore)) {
                    importFile.launch(arrayOf(BackupCodec.MIME_TYPE, "text/plain", "*/*"))
                }
                Text(
                    text = stringResource(R.string.backup_restore_note),
                    color = AbhyasColors.Dim,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        item {
            // Crashes are recorded on the phone and go nowhere unless the user sends them. The
            // row always shows, so "nothing recorded" is a visible fact rather than a missing row.
            Card {
                SectionLabel(stringResource(R.string.diagnostics_title), fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.crashCount == 0) {
                        stringResource(R.string.diagnostics_none)
                    } else {
                        pluralStringResource(R.plurals.diagnostics_count, state.crashCount, state.crashCount)
                    },
                    color = AbhyasColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                if (state.crashCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    ActionRow(stringResource(R.string.diagnostics_send)) {
                        scope.launch { viewingLog = viewModel.readDiagnostics().orEmpty() }
                    }
                    ActionRow(stringResource(R.string.diagnostics_delete), color = AbhyasColors.Again) {
                        viewModel.deleteDiagnostics()
                    }
                }
                Text(
                    text = stringResource(R.string.diagnostics_explainer),
                    color = AbhyasColors.Dim,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        item {
            Card {
                ActionRow(stringResource(R.string.about_title), onClick = onAbout)
            }
        }

        state.message?.let { message ->
            item {
                Card {
                    Text(messageText(message), color = AbhyasColors.Text, fontSize = 13.5.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(10.dp))
                    TextLink(stringResource(R.string.action_dismiss), AbhyasColors.Saffron, { viewModel.clearMessage() }, fontSize = 13.sp)
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

    viewingLog?.let { log ->
        val subject = stringResource(R.string.diagnostics_email_subject, BuildConfig.VERSION_NAME)
        val body = stringResource(R.string.diagnostics_email_body)
        DiagnosticsDialog(
            log = log,
            onSend = {
                viewingLog = null
                // Attached, and pasted into the body as well: some mail apps drop attachments from
                // a share, and a report that arrives empty is worse than one that arrives twice.
                BrandLinks.sendEmail(
                    context,
                    subject = subject,
                    body = body + "\n\n" + log.take(MAX_BODY_CHARACTERS),
                    attachment = CrashLog.shareableUri(context)
                )
            },
            onDelete = {
                viewingLog = null
                viewModel.deleteDiagnostics()
            },
            onDismiss = { viewingLog = null }
        )
    }
}

/**
 * The crash log, shown in full before anything is sent. The user sees exactly what would leave
 * the phone, and the send goes through their own mail app - Abhyas cannot send it itself.
 */
@Composable
private fun DiagnosticsDialog(log: String, onSend: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(stringResource(R.string.diagnostics_view_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.diagnostics_view_hint),
                    color = AbhyasColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AbhyasColors.SurfaceDim)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = AbhyasColors.Muted, softWrap = false)
                }
            }
        },
        confirmButton = { DialogAction(stringResource(R.string.diagnostics_send_email), onSend) },
        dismissButton = {
            Row {
                DialogAction(stringResource(R.string.action_delete), onDelete, AbhyasColors.Again, emphasised = false)
                DialogAction(stringResource(R.string.action_close), onDismiss, AbhyasColors.Muted, emphasised = false)
            }
        }
    )
}

@Composable
private fun messageText(message: SettingsMessage): String = when (message) {
    SettingsMessage.RemindersRevoked -> stringResource(R.string.msg_reminders_revoked)
    is SettingsMessage.Exported -> stringResource(
        R.string.msg_exported,
        pluralStringResource(R.plurals.count_decks, message.decks, message.decks),
        pluralStringResource(R.plurals.count_cards, message.cards, message.cards)
    )
    SettingsMessage.ExportFailed -> stringResource(R.string.msg_export_failed)
    SettingsMessage.ReadFailed -> stringResource(R.string.msg_read_failed)
    SettingsMessage.RestoreFailed -> stringResource(R.string.msg_restore_failed)
    SettingsMessage.Damaged -> stringResource(R.string.msg_damaged)
    SettingsMessage.NotABackup -> stringResource(R.string.msg_not_a_backup)
    is SettingsMessage.Restored -> stringResource(
        R.string.msg_restored,
        pluralStringResource(R.plurals.count_decks, message.decks, message.decks),
        pluralStringResource(R.plurals.count_cards, message.cards, message.cards)
    )
    SettingsMessage.DiagnosticsDeleted -> stringResource(R.string.msg_diagnostics_deleted)
}

@Composable
private fun ActionRow(label: String, color: androidx.compose.ui.graphics.Color = AbhyasColors.Saffron, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
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

/** The reminder time in the phone's own format and language - 7:00 pm, 19:00, शाम 7:00. */
internal fun formatTime(context: android.content.Context, hour: Int, minute: Int): String {
    val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return DateFormat.getTimeFormat(context).format(at.time)
}

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** Mail apps truncate or reject very long bodies; the attachment carries the rest. */
private const val MAX_BODY_CHARACTERS = 20_000

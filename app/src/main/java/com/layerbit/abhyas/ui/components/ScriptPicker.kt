package com.layerbit.abhyas.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.ui.theme.AbhyasColors

/**
 * Choosing which recogniser reads this deck's pages.
 *
 * Each row leads with the script's own name, because someone looking for Devanagari recognises
 * "देवनागरी" faster than they recognise the English word for it, and lists the languages it
 * actually covers - the useful question is "will this read my textbook?", not "what is this
 * writing system called?".
 */
@Composable
fun ScriptPicker(
    selected: ScriptOption,
    onSelect: (ScriptOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScriptOption.entries.forEach { option ->
            ScriptRow(
                option = option,
                selected = option == selected,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun ScriptRow(option: ScriptOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AbhyasColors.SaffronDim else AbhyasColors.SurfaceDim)
            .border(
                width = 1.dp,
                color = if (selected) AbhyasColors.Saffron else AbhyasColors.Border,
                shape = RoundedCornerShape(12.dp)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = option.nativeLabel,
            color = if (selected) AbhyasColors.SaffronBright else AbhyasColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(76.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                color = if (selected) AbhyasColors.Text else AbhyasColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(option.covers, color = AbhyasColors.Dim, fontSize = 11.5.sp, lineHeight = 15.sp)
        }

        // A mark, not just a colour. `selectable` tells a screen reader which row is chosen, but
        // for a sighted user the only signal was a saffron tint against a dark one - and this is a
        // choice that decides whether OCR returns text or confident nonsense, so it should not rest
        // on being able to tell two dark backgrounds apart.
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AbhyasColors.SaffronBright,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The picker on its own, for changing a deck's script after it has been created. */
@Composable
fun ScriptPickerDialog(
    selected: ScriptOption,
    onSelect: (ScriptOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("Script", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Which writing system are this deck's pages in? Every option except " +
                        "Latin also reads English, so a mixed textbook is fine.",
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(14.dp))
                ScriptPicker(
                    selected = selected,
                    onSelect = {
                        onSelect(it)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            Text(
                text = "Done",
                color = AbhyasColors.Saffron,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp)
            )
        }
    )
}

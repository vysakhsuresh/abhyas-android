package com.layerbit.abhyas.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.abhyas.ui.theme.AbhyasColors

/**
 * Picking the reminder time.
 *
 * Two scrolling columns rather than Material3's TimePicker, which brings its own colour scheme
 * and clock face and would be the only thing in the app that does not look like the app. Minutes
 * step in fives: nobody needs to be reminded at 7:23, and forty-eight fewer rows makes the column
 * usable with a thumb.
 */
@Composable
fun TimePickerDialog(
    hour: Int,
    minute: Int,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHour by remember { mutableIntStateOf(hour) }
    var selectedMinute by remember { mutableIntStateOf(minute - minute % MINUTE_STEP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("Remind me at", fontWeight = FontWeight.Bold) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().height(210.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NumberColumn(
                    values = (0..23).toList(),
                    selected = selectedHour,
                    format = ::formatHour,
                    modifier = Modifier.weight(1f),
                    onSelect = { selectedHour = it }
                )
                NumberColumn(
                    values = (0 until 60 step MINUTE_STEP).toList(),
                    selected = selectedMinute,
                    format = { "%02d".format(it) },
                    modifier = Modifier.weight(1f),
                    onSelect = { selectedMinute = it }
                )
            }
        },
        confirmButton = {
            Text(
                text = "Set",
                color = AbhyasColors.Saffron,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onPick(selectedHour, selectedMinute) }
                    .padding(12.dp)
            )
        },
        dismissButton = {
            Text(
                text = "Cancel",
                color = AbhyasColors.Muted,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp)
            )
        }
    )
}

@Composable
private fun NumberColumn(
    values: List<Int>,
    selected: Int,
    format: (Int) -> String,
    modifier: Modifier,
    onSelect: (Int) -> Unit
) {
    // Open already scrolled to the current value, a couple of rows up so it is not jammed against
    // the top edge with no context above it.
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (values.indexOf(selected) - 2).coerceAtLeast(0)
    )

    LazyColumn(
        state = state,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(values) { value ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) AbhyasColors.SaffronDim else AbhyasColors.SurfaceDim)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) AbhyasColors.Saffron else AbhyasColors.Border,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = format(value),
                    color = if (isSelected) AbhyasColors.SaffronBright else AbhyasColors.Muted,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatHour(hour: Int): String {
    val suffix = if (hour < 12) "am" else "pm"
    val display = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$display $suffix"
}

private const val MINUTE_STEP = 5

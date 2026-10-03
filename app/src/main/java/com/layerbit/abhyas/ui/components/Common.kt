package com.layerbit.abhyas.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.abhyas.ui.theme.AbhyasColors

/** The panel every screen is built out of. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AbhyasColors.Surface)
            .border(1.dp, AbhyasColors.Border, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content
    )
}

/** The saffron call to action. One per screen - more than one and neither reads as the answer. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) AbhyasColors.Saffron else AbhyasColors.SaffronDim)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) AbhyasColors.OnSaffron else AbhyasColors.Dim,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** A quieter action that still has to look pressable. */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AbhyasColors.SurfaceDim)
            .border(1.dp, AbhyasColors.BorderStrong, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) AbhyasColors.Text else AbhyasColors.Dim,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** A small labelled count: due, new, total, or the kind of a generated card. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(bottom = 18.dp)) {
        Text(text = text, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, color = AbhyasColors.Muted, fontSize = 14.sp)
        }
    }
}

/**
 * What a screen shows when it has nothing yet.
 *
 * Deliberately says what to do next rather than only what is missing - an empty deck list is the
 * first thing a new user sees, and "No decks" on its own tells them nothing.
 */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AbhyasColors.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** A row of "12 / due" style figures, used on the deck list and the stats screen. */
@Composable
fun StatRow(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        stats.forEach { (value, label) ->
            Column {
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(label, color = AbhyasColors.Dim, fontSize = 11.5.sp)
            }
        }
    }
}

/**
 * The edge padding every screen uses.
 *
 * [MainActivity] draws edge to edge, so clearing the system bars is ours to do. A fixed `top =
 * 56.dp` only happened to clear the status bar on the phone it was written on, and a fixed bottom
 * put the study screen's grade buttons - the one control the whole app exists for - underneath
 * the navigation bar. Measuring the bars keeps the intended spacing on every device instead.
 *
 * [extraBottom] is the breathing room *below* the navigation bar inset, which is what the old
 * hardcoded bottom value was really expressing.
 */
@Composable
fun screenPadding(extraBottom: Dp = 32.dp, extraTop: Dp = 18.dp): PaddingValues {
    val bars = WindowInsets.systemBars.asPaddingValues()
    return PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = bars.calculateTopPadding() + extraTop,
        bottom = bars.calculateBottomPadding() + extraBottom
    )
}

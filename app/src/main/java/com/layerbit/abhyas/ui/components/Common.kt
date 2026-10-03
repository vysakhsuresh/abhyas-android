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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.abhyas.ui.theme.AbhyasColors

/** The panel every screen is built out of. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AbhyasColors.Surface)
            .border(1.dp, AbhyasColors.Border, RoundedCornerShape(18.dp))
            .then(
                if (onClick != null) {
                    // role and onClickLabel both matter to a screen reader and neither is inferred:
                    // foundation's clickable leaves the role null, so TalkBack announces a tappable
                    // card as though it were static text and never says it can be activated.
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
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
            // heightIn, not height. A fixed height with a clip after it hard-clips the label, and
            // at large system font scales "Add cards from a page" wraps to two lines and lost its
            // top and bottom to the 54dp box - on the one control the screen exists for.
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) AbhyasColors.Saffron else AbhyasColors.SaffronDim)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) AbhyasColors.OnSaffron else AbhyasColors.Dim,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
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
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AbhyasColors.SurfaceDim)
            .border(1.dp, AbhyasColors.BorderStrong, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) AbhyasColors.Text else AbhyasColors.Dim,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
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
fun ScreenTitle(text: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(bottom = 18.dp)) {
        Text(
            text = text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.1).sp,
            // Marking headings costs nothing visually and is how TalkBack users move through a
            // screen: without it, heading navigation finds nothing and the only way down a long
            // settings or insights screen is one swipe per line.
            modifier = Modifier.semantics { heading() }
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, color = AbhyasColors.Muted, fontSize = 14.sp)
        }
    }
}

/**
 * A section heading inside a screen - "Backup", "Support", "YOUR PRACTICE".
 *
 * Exists so the [heading] semantics are attached once rather than remembered at seven call sites.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AbhyasColors.Text,
    fontSize: TextUnit = 17.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    letterSpacing: TextUnit = TextUnit.Unspecified
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        modifier = modifier.semantics { heading() }
    )
}

/**
 * A text link with a real hit area.
 *
 * Every navigation affordance in the app was a bare `clickable` on a 14sp `Text`, which gives a
 * target one text line tall - about 18dp against the 48dp minimum - and no button role. The padding
 * is *inside* the clickable, which is the load-bearing detail: outside it, it moves the glyphs
 * without growing what a finger can actually hit.
 */
@Composable
fun TextLink(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    onClickLabel: String? = null
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp)
    )
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

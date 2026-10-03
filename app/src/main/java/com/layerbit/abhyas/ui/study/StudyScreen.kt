package com.layerbit.abhyas.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
fun StudyScreen(deckId: Long, onDone: () -> Unit) {
    val viewModel = repositoryViewModel(key = "study-$deckId") { StudyViewModel(it, deckId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenPadding(extraBottom = 24.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextLink("Close", AbhyasColors.Muted, onDone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canUndo) {
                    TextLink(
                        text = "Undo",
                        color = AbhyasColors.Saffron,
                        onClick = viewModel::undo,
                        onClickLabel = "Take back the last answer"
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (!state.finished && !state.loading) {
                    Text(
                        text = "${state.remaining} left",
                        color = AbhyasColors.Dim,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        when {
            state.loading -> Unit

            state.finished -> SessionComplete(
                answered = state.answered,
                nextDueAt = state.nextDueAt,
                onDone = onDone
            )

            state.card != null -> {
                val card = state.card!!

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Card {
                        Text(
                            text = card.front,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 28.sp
                        )
                    }

                    if (state.answerShown) {
                        // Revealing the answer replaced the button the user had just activated, so
                        // the node holding accessibility focus vanished and nothing announced what
                        // had appeared in its place - leaving a TalkBack user to hunt for the answer
                        // they had asked to see.
                        //
                        // announceForAccessibility is deprecated in favour of live regions, and the
                        // deprecation is kept suppressed rather than followed. A live region is
                        // announced when an *existing* node's content changes; this node does not
                        // exist until the answer is revealed, so there is no prior content to
                        // change from and nothing dependably fires. Revealing the answer is the one
                        // moment in this app where getting the announcement wrong means the content
                        // is simply never read out.
                        val view = LocalView.current
                        LaunchedEffect(card.id, state.answerShown) {
                            @Suppress("DEPRECATION")
                            view.announceForAccessibility(card.back)
                        }

                        Spacer(Modifier.height(12.dp))
                        Card(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Text(
                                text = card.back,
                                fontSize = 18.sp,
                                color = AbhyasColors.SaffronBright,
                                lineHeight = 26.sp
                            )
                            // The sentence the card came from. A generated question is only as
                            // good as its source, and being able to see that source is what lets
                            // a user tell a bad card from a genuine gap in their memory.
                            if (!card.sourceText.isNullOrBlank() && card.sourceText != card.back) {
                                Spacer(Modifier.height(14.dp))
                                Text("FROM YOUR NOTES", color = AbhyasColors.Dim, fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = card.sourceText,
                                    color = AbhyasColors.Muted,
                                    fontSize = 13.5.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (state.answerShown) {
                    GradeButtons(previews = state.previews, onGrade = viewModel::answer)
                } else {
                    PrimaryButton("Show answer", onClick = viewModel::showAnswer)
                }
            }
        }
    }

    state.leechWarning?.let { warning ->
        LeechNotice(message = warning, onDismiss = viewModel::dismissLeechWarning)
    }
}

@Composable
private fun GradeButtons(previews: Map<Grade, Long>, onGrade: (Grade) -> Unit) {
    // Again and Good sit at the outside edges, where thumbs land. They are the two answers that
    // account for nearly every review; Hard and Easy are the deliberate ones and can be reached.
    val grades = listOf(
        Grade.AGAIN to AbhyasColors.Again,
        Grade.HARD to AbhyasColors.Hard,
        Grade.GOOD to AbhyasColors.Good,
        Grade.EASY to AbhyasColors.Easy
    )

    // What each button actually costs. "Good" meaning three weeks and "Easy" meaning three months
    // is the choice the user is really making, and showing it is what turns a blind self-grade into
    // an informed one.
    val button: @Composable (Pair<Grade, Color>, Modifier) -> Unit = { (grade, color), modifier ->
        GradeButton(
            label = grade.label,
            interval = previews[grade]?.let(::shortInterval),
            color = color,
            modifier = modifier,
            onClick = { onGrade(grade) }
        )
    }

    // Four across a 360dp screen leaves each button about 74dp, and "Again" at a large font scale
    // needs more than that - so the label wrapped inside a button that also had to hold an interval
    // underneath it. Two rows of two give each button twice the width, on exactly the screens where
    // the text needs it. Below the threshold the familiar single row is untouched.
    if (LocalConfiguration.current.fontScale > 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grades.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    pair.forEach { button(it, Modifier.weight(1f)) }
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            grades.forEach { button(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun GradeButton(
    label: String,
    interval: String?,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            // heightIn, and the padding inside the clickable. A fixed 60dp with a clip after it
            // hard-clipped the contents: from about fontScale 1.8 the label and the interval need
            // some 61dp between them, so a centred Column overflowed the box and both lines lost
            // their top and bottom. These are the four buttons the whole app exists for.
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        if (interval != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = interval,
                color = color.copy(alpha = 0.72f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * "10m", "3d", "2w", "4mo", "1.5y" - short enough to sit under a button on a narrow phone.
 *
 * Minutes and hours matter as much as the longer units: a card in learning comes back in minutes,
 * and rounding that down to whole days printed "0d" under three of the four buttons, hiding the
 * very difference the preview exists to show.
 */
internal fun shortInterval(millis: Long): String {
    val minutes = millis / 60_000L
    val days = (millis / 86_400_000L).toInt()
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        // Floored, not rounded: rounding turns 23h30m into "24h", which is a unit the row never
        // otherwise prints and which plainly means a day. Understating by under an hour is the
        // cheaper error on a label this small.
        minutes < 1440 -> "${minutes / 60}h"
        days < 7 -> "${days}d"
        days < 30 -> "${(days / 7.0).roundToInt()}w"
        days < 365 -> "${(days / 30.0).roundToInt()}mo"
        else -> {
            val years = days / 365.0
            if (years < 10) "${"%.1f".format(years)}y" else "${years.roundToInt()}y"
        }
    }
}

@Composable
private fun LeechNotice(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("Set aside", fontWeight = FontWeight.Bold) },
        text = { Text(message, color = AbhyasColors.Muted, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            Text(
                text = "Got it",
                color = AbhyasColors.Saffron,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp)
            )
        }
    )
}

@Composable
private fun SessionComplete(answered: Int, nextDueAt: Long?, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (answered == 0) "Nothing due" else "Done",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                answered == 0 -> "This deck has nothing waiting for you right now."
                answered == 1 -> "1 card reviewed."
                else -> "$answered cards reviewed."
            },
            color = AbhyasColors.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        if (nextDueAt != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Next review ${relativeTime(nextDueAt)}.",
                color = AbhyasColors.Dim,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
            PrimaryButton("Back to deck", onClick = onDone)
        }
    }
}

/**
 * "in 9 minutes" / "tomorrow" / "in 12 days".
 *
 * Telling someone *when* to come back is what turns the end of a session from a dead end into an
 * appointment, and minutes matter here because a card still in its learning steps really is due
 * that soon.
 */
private fun relativeTime(at: Long): String {
    val delta = at - System.currentTimeMillis()
    if (delta <= 0) return "now"

    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)

    return when {
        minutes < 1L -> "in under a minute"
        minutes < 60L -> "in $minutes ${plural(minutes, "minute")}"
        hours < 24L -> "in $hours ${plural(hours, "hour")}"
        days < 2L -> "tomorrow"
        days < 60L -> "in $days days"
        else -> "in ${days / 30} months"
    }
}

private fun plural(count: Long, word: String) = if (count == 1L) word else "${word}s"

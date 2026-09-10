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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.util.concurrent.TimeUnit

@Composable
fun StudyScreen(deckId: Long, onDone: () -> Unit) {
    val viewModel = repositoryViewModel(key = "study-$deckId") { StudyViewModel(it, deckId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Close",
                color = AbhyasColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onDone)
            )
            if (!state.finished && !state.loading) {
                Text(
                    text = "${state.remaining} left",
                    color = AbhyasColors.Dim,
                    fontSize = 13.sp
                )
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
                        Spacer(Modifier.height(12.dp))
                        Card {
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
                    GradeButtons(onGrade = viewModel::answer)
                } else {
                    PrimaryButton("Show answer", onClick = viewModel::showAnswer)
                }
            }
        }
    }
}

@Composable
private fun GradeButtons(onGrade: (Grade) -> Unit) {
    // Again and Good sit at the outside edges, where thumbs land. They are the two answers that
    // account for nearly every review; Hard and Easy are the deliberate ones and can be reached.
    val grades = listOf(
        Grade.AGAIN to AbhyasColors.Again,
        Grade.HARD to AbhyasColors.Hard,
        Grade.GOOD to AbhyasColors.Good,
        Grade.EASY to AbhyasColors.Easy
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        grades.forEach { (grade, color) ->
            GradeButton(
                label = grade.label,
                color = color,
                modifier = Modifier.weight(1f),
                onClick = { onGrade(grade) }
            )
        }
    }
}

@Composable
private fun GradeButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
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

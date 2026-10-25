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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.model.CardType
import com.layerbit.abhyas.data.model.Grade
import com.layerbit.abhyas.data.occlusion.Occlusion
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.DialogAction
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.Speaker
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.rememberSpeaker
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.labelRes
import com.layerbit.abhyas.ui.occlusion.OccludedImage
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
fun StudyScreen(deckId: Long, onDone: () -> Unit, cram: CramFilter? = null) {
    val key = if (cram == null) "study-$deckId" else "cram-${cram.term}-${cram.tag}"
    val viewModel = repositoryViewModel(key = key) { StudyViewModel(it, deckId, cram) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()

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
            TextLink(stringResource(R.string.action_close), AbhyasColors.Muted, onDone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canUndo) {
                    TextLink(
                        text = stringResource(R.string.study_undo),
                        color = AbhyasColors.Saffron,
                        onClick = viewModel::undo,
                        onClickLabel = stringResource(R.string.a11y_undo)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (!state.finished && !state.loading) {
                    Text(
                        text = stringResource(R.string.study_left, state.remaining),
                        color = AbhyasColors.Dim,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (state.cram) {
            // Said on every card, not once: the whole value of cram mode is that it is safe, and a
            // student who forgot they were in it would worry their Again answers were costing them.
            Pill(stringResource(R.string.study_cram_badge), AbhyasColors.Easy)
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(20.dp))

        when {
            state.loading -> Unit

            state.finished -> SessionComplete(
                answered = state.answered,
                nextDueAt = state.nextDueAt,
                cram = state.cram,
                onDone = onDone
            )

            state.card != null -> {
                val card = state.card!!

                // Keyed on the card, so the scroll position is discarded with it. Grading keeps this
                // same `when` branch selected, so one ScrollState was being reused for the whole
                // sitting - scroll to the bottom of a long card, grade it, and the next card opened
                // already scrolled past its own question.
                val scroll = rememberScrollState()
                LaunchedEffect(card.id) {
                    scroll.scrollTo(0)
                    speaker.stop()
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(scroll)) {
                    CardFace(card = card, state = state, speaker = speaker)
                }

                Spacer(Modifier.height(16.dp))

                if (state.answerShown) {
                    GradeButtons(previews = state.previews, onGrade = viewModel::answer)
                } else {
                    PrimaryButton(stringResource(R.string.study_show_answer), onClick = viewModel::showAnswer)
                }
            }
        }
    }

    state.leechLapses?.let { lapses ->
        LeechNotice(lapses = lapses, onDismiss = viewModel::dismissLeechWarning)
    }
}

/** The question, and once revealed the answer - as words, or as a masked picture. */
@Composable
private fun CardFace(card: CardEntity, state: StudyState, speaker: Speaker) {
    val occlusion = remember(card.id, card.occlusion) {
        if (card.type == CardType.OCCLUSION) Occlusion.decode(card.occlusion) else null
    }
    val blank = stringResource(R.string.tts_blank)

    Card {
        if (occlusion != null && card.imagePath != null) {
            Text(card.front, color = AbhyasColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            OccludedImage(imageName = card.imagePath, occlusion = occlusion, revealed = state.answerShown)
        } else {
            Text(
                text = card.front,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 28.sp
            )
            SpeakLink(speaker) { speaker.speak(spoken(card.front, blank), state.script) }
        }
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
            SpeakLink(speaker) { speaker.speak(card.back, state.script) }
            // The sentence the card came from. A generated question is only as
            // good as its source, and being able to see that source is what lets
            // a user tell a bad card from a genuine gap in their memory.
            if (!card.sourceText.isNullOrBlank() && card.sourceText != card.back) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.from_your_notes),
                    color = AbhyasColors.Dim, fontSize = 10.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.sp
                )
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

/**
 * "Listen" under a side of the card, shown only once an engine is ready - a speak button that does
 * nothing on a phone without one would look broken.
 */
@Composable
private fun SpeakLink(speaker: Speaker, onSpeak: () -> Unit) {
    if (!speaker.isReady) return
    TextLink(
        text = stringResource(R.string.study_listen),
        color = AbhyasColors.Dim,
        onClick = onSpeak,
        fontSize = 12.5.sp,
        onClickLabel = stringResource(R.string.a11y_listen),
        modifier = Modifier.padding(top = 2.dp)
    )
}

/** A cloze's run of underscores read as the word "blank" rather than spelled out by the engine. */
private fun spoken(text: String, blank: String): String = text.replace(Regex("_{2,}"), " $blank ")

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
    val units = intervalUnits()

    // What each button actually costs. "Good" meaning three weeks and "Easy" meaning three months
    // is the choice the user is really making, and showing it is what turns a blind self-grade into
    // an informed one.
    val button: @Composable (Pair<Grade, Color>, Modifier) -> Unit = { (grade, color), modifier ->
        GradeButton(
            label = stringResource(grade.labelRes()),
            interval = previews[grade]?.let { shortInterval(it, units) },
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
 * The unit suffixes under the grade buttons. English by default, which is what keeps
 * [shortInterval] testable on the JVM; the screen passes the app language's own.
 */
data class IntervalUnits(
    val lessThanMinute: String = "<1m",
    val minutes: String = "%dm",
    val hours: String = "%dh",
    val days: String = "%dd",
    val weeks: String = "%dw",
    val months: String = "%dmo",
    val years: String = "%sy"
)

@Composable
private fun intervalUnits() = IntervalUnits(
    lessThanMinute = stringResource(R.string.interval_less_than_minute),
    minutes = stringResource(R.string.interval_minutes),
    hours = stringResource(R.string.interval_hours),
    days = stringResource(R.string.interval_days),
    weeks = stringResource(R.string.interval_weeks),
    months = stringResource(R.string.interval_months),
    years = stringResource(R.string.interval_years)
)

/**
 * "10m", "3d", "2w", "4mo", "1.5y" - short enough to sit under a button on a narrow phone.
 *
 * Minutes and hours matter as much as the longer units: a card in learning comes back in minutes,
 * and rounding that down to whole days printed "0d" under three of the four buttons, hiding the
 * very difference the preview exists to show.
 */
internal fun shortInterval(millis: Long, units: IntervalUnits = IntervalUnits()): String {
    val minutes = millis / 60_000L
    val days = (millis / 86_400_000L).toInt()
    return when {
        minutes < 1 -> units.lessThanMinute
        minutes < 60 -> units.minutes.format(minutes)
        // Floored, not rounded: rounding turns 23h30m into "24h", which is a unit the row never
        // otherwise prints and which plainly means a day. Understating by under an hour is the
        // cheaper error on a label this small.
        minutes < 1440 -> units.hours.format(minutes / 60)
        days < 7 -> units.days.format(days)
        days < 30 -> units.weeks.format((days / 7.0).roundToInt())
        days < 365 -> units.months.format((days / 30.0).roundToInt())
        else -> {
            val years = days / 365.0
            units.years.format(if (years < 10) "%.1f".format(years) else years.roundToInt().toString())
        }
    }
}

@Composable
private fun LeechNotice(lapses: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(stringResource(R.string.leech_title), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                pluralStringResource(R.plurals.leech_message, lapses, lapses),
                color = AbhyasColors.Muted, fontSize = 14.sp, lineHeight = 20.sp
            )
        },
        confirmButton = { DialogAction(stringResource(R.string.action_got_it), onDismiss) }
    )
}

@Composable
private fun SessionComplete(answered: Int, nextDueAt: Long?, cram: Boolean, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(if (answered == 0) R.string.study_nothing_due else R.string.study_done),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                answered == 0 && cram -> stringResource(R.string.cram_nothing_matches)
                answered == 0 -> stringResource(R.string.study_nothing_waiting)
                else -> pluralStringResource(R.plurals.study_reviewed, answered, answered)
            },
            color = AbhyasColors.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        if (cram && answered > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.cram_schedule_untouched),
                color = AbhyasColors.Dim, fontSize = 13.5.sp, textAlign = TextAlign.Center
            )
        }
        if (nextDueAt != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.study_next_review, relativeTime(nextDueAt)),
                color = AbhyasColors.Dim,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
            PrimaryButton(
                stringResource(if (cram) R.string.action_done else R.string.action_back_to_deck),
                onClick = onDone
            )
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
@Composable
private fun relativeTime(at: Long): String {
    val delta = at - System.currentTimeMillis()
    if (delta <= 0) return stringResource(R.string.when_now)

    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)

    return when {
        minutes < 1L -> stringResource(R.string.when_under_a_minute)
        minutes < 60L -> pluralStringResource(R.plurals.when_minutes, minutes.toInt(), minutes.toInt())
        hours < 24L -> pluralStringResource(R.plurals.when_hours, hours.toInt(), hours.toInt())
        days < 2L -> stringResource(R.string.when_tomorrow)
        days < 60L -> pluralStringResource(R.plurals.when_days, days.toInt(), days.toInt())
        else -> pluralStringResource(R.plurals.when_months, (days / 30).toInt(), (days / 30).toInt())
    }
}

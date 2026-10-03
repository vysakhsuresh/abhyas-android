package com.layerbit.abhyas.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.ForecastDay
import com.layerbit.abhyas.data.db.Maturity
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SectionLabel
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(onBack: () -> Unit) {
    val viewModel = repositoryViewModel { InsightsViewModel(it) }

    val retention by viewModel.retention.collectAsStateWithLifecycle()
    val maturity by viewModel.maturity.collectAsStateWithLifecycle()
    val forecast by viewModel.forecast.collectAsStateWithLifecycle()
    val leeches by viewModel.leeches.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextLink("Back", AbhyasColors.Muted, onBack)
            Spacer(Modifier.height(4.dp))
            ScreenTitle("Insights", "How the schedule is actually doing.")
        }

        item { RetentionTile(rate = retention, streak = streak) }
        item { MaturityCard(maturity) }
        item { ForecastCard(forecast) }

        if (leeches.isNotEmpty()) {
            item { LeechCard(leeches, onUnsuspend = viewModel::unsuspend) }
        }
    }
}

/**
 * Retention as a hero number, not a chart.
 *
 * It is one value with one job - "is the schedule working?" - and plotting a single number is
 * how dashboards end up unreadable. The target sits beside it because the figure means nothing
 * on its own: 88% is healthy, and would look like a failure without something to read it against.
 */
@Composable
private fun RetentionTile(rate: Float?, streak: Int) {
    Card {
        SectionLabel(
            "RECALLED, LAST 30 DAYS",
            color = AbhyasColors.Dim,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = rate?.let { "${(it * 100).roundToInt()}%" } ?: "--",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp,
                color = AbhyasColors.Text
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "target 90%",
                color = AbhyasColors.Dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 9.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                rate == null -> "Study a few cards and this fills in."
                rate >= 0.93f -> "Comfortably above target. The intervals could be longer - " +
                    "you are reviewing more than you need to."
                rate >= 0.85f -> "Right where it should be. The schedule is pitched correctly " +
                    "for you."
                else -> "Below target. Answer honestly rather than generously, and this " +
                    "corrects itself as the model learns which cards are hard for you."
            },
            color = AbhyasColors.Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        if (streak > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (streak == 1) "1 day streak" else "$streak day streak",
                color = AbhyasColors.SaffronBright,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Where the collection sits, as one stacked bar.
 *
 * Composition of a whole, so one bar rather than four. Every segment is directly labelled
 * underneath with its name and count, which is what keeps identity off colour alone - the four
 * hues separate well for the common colour-blindness types but are tight for tritan.
 */
@Composable
private fun MaturityCard(maturity: Maturity?) {
    val segments = listOf(
        Triple("Unseen", maturity?.unseen ?: 0, AbhyasColors.Unseen),
        Triple("Learning", maturity?.learning ?: 0, AbhyasColors.Learning),
        Triple("Under 3 weeks", maturity?.young ?: 0, AbhyasColors.Young),
        Triple("Sticking", maturity?.mature ?: 0, AbhyasColors.Mature)
    )
    val total = segments.sumOf { it.second }

    Card {
        SectionLabel(
            "WHERE YOUR CARDS ARE",
            color = AbhyasColors.Dim,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(14.dp))

        if (total == 0) {
            Text("No cards yet.", color = AbhyasColors.Muted, fontSize = 14.sp)
            return@Card
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp)),
            // A 2px gap of surface between segments, so touching fills stay distinguishable
            // without an outline on each.
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            segments.filter { it.second > 0 }.forEach { (_, count, color) ->
                Box(
                    modifier = Modifier
                        .weight(count.toFloat())
                        .fillMaxHeight()
                        .background(color)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        segments.forEach { (label, count, color) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
                Spacer(Modifier.width(10.dp))
                Text(label, color = AbhyasColors.Muted, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text(
                    text = count.toString(),
                    color = AbhyasColors.Text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * How much work is coming, for the next two weeks.
 *
 * One series, so one hue and no legend - the heading names it. Only the busiest day is labelled;
 * a number over every bar is noise, and the shape is what the user is reading. This is the part
 * of the app that lets somebody see a wall of reviews on Thursday while it is still Monday.
 */
@Composable
private fun ForecastCard(forecast: List<ForecastDay>) {
    val byDay = forecast.associate { it.dayOffset to it.count }
    val days = (0 until FORECAST_DAYS).map { byDay[it] ?: 0 }
    val peak = days.maxOrNull() ?: 0

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionLabel(
                "DUE OVER THE NEXT TWO WEEKS",
                color = AbhyasColors.Dim,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp
            )
            Text(
                text = "${days.sum()} total",
                color = AbhyasColors.Dim,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        if (peak == 0) {
            Text(
                "Nothing due in the next two weeks.",
                color = AbhyasColors.Muted,
                fontSize = 14.sp
            )
            return@Card
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                // The bars are Boxes with a background and a height, so they emit no semantics at
                // all - the chart was simply absent to a screen reader, apart from the single number
                // over the busiest bar. Reading the fourteen days out as one node is the whole
                // chart, in order, in the only form that survives being spoken.
                .semantics(mergeDescendants = true) {
                    contentDescription = days
                        .mapIndexed { index, count ->
                            when (index) {
                                0 -> "today $count"
                                1 -> "tomorrow $count"
                                else -> "in $index days $count"
                            }
                        }
                        .joinToString(", ")
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEachIndexed { index, count ->
                ForecastBar(
                    count = count,
                    peak = peak,
                    isPeak = count == peak && count > 0,
                    isToday = index == 0,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Today", color = AbhyasColors.Dim, fontSize = 11.sp)
            Text("In 2 weeks", color = AbhyasColors.Dim, fontSize = 11.sp)
        }

        // The shape of the chart in a sentence. Written for the screen reader and kept for everyone:
        // "your heaviest day is Thursday" is the thing a student actually wants off this card, and
        // reading it off fourteen bars is work even with good eyesight.
        Spacer(Modifier.height(12.dp))
        Text(
            text = busiestDay(days),
            color = AbhyasColors.Muted,
            fontSize = 13.sp
        )
    }
}

/** The forecast's one useful sentence: when the heaviest day falls, and how heavy. */
private fun busiestDay(days: List<Int>): String {
    val peak = days.maxOrNull() ?: 0
    // "scheduled" would be a lie: a card put away for a month is scheduled, it is just not in
    // this window, and the card above counts exactly fourteen days.
    if (peak == 0) return "Nothing due in the next two weeks."

    val cards = if (peak == 1) "1 card" else "$peak cards"
    return when (val index = days.indexOf(peak)) {
        0 -> "Busiest day is today, $cards."
        1 -> "Busiest day is tomorrow, $cards."
        else -> "Busiest day is in $index days, $cards."
    }
}

@Composable
private fun ForecastBar(
    count: Int,
    peak: Int,
    isPeak: Boolean,
    isToday: Boolean,
    modifier: Modifier
) {
    val fraction = if (peak == 0) 0f else count.toFloat() / peak
    val color = if (isToday) AbhyasColors.SaffronBright else AbhyasColors.Saffron

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Only the busiest day carries a number. Labelling every bar would bury the shape.
        if (isPeak) {
            Text(count.toString(), color = AbhyasColors.Muted, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // A day with nothing due still gets a hairline, so the gap reads as "none"
                    // rather than as a rendering failure.
                    .height(if (count == 0) 2.dp else (fraction * 72).dp.coerceAtLeast(4.dp))
                    // Rounded at the data end only; the baseline stays square so the bar is
                    // anchored rather than floating.
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (count == 0) AbhyasColors.Border else color)
            )
        }
    }
}

@Composable
private fun LeechCard(leeches: List<CardEntity>, onUnsuspend: (CardEntity) -> Unit) {
    Card {
        SectionLabel(
            "SET ASIDE",
            color = AbhyasColors.Dim,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "You have forgotten these too many times to keep repeating them. A card this " +
                "sticky is usually trying to hold too much at once - rewrite it as two simpler " +
                "ones, or put it back if you disagree.",
            color = AbhyasColors.Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(14.dp))
        leeches.take(10).forEach { card ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(card.front, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "forgotten ${card.lapses} times",
                        color = AbhyasColors.Again,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextLink(
                        text = "Put back",
                        color = AbhyasColors.Saffron,
                        onClick = { onUnsuspend(card) },
                        fontSize = 12.5.sp,
                        onClickLabel = "Put this card back into the deck"
                    )
                }
            }
        }
    }
}

private const val FORECAST_DAYS = 14

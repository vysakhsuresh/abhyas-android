package com.layerbit.abhyas.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.AppPreferences
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.data.stats.Streak
import com.layerbit.abhyas.ui.components.AbhyasTextField
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.DialogAction
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.FieldLabel
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.ScriptPicker
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the home screen: a top-level deck, or a subdeck drawn indented beneath its parent. */
data class DeckRowModel(val deck: DeckSummary, val isChild: Boolean)

class DecksViewModel(private val repository: AbhyasRepository) : ViewModel() {

    /**
     * Due counts are computed against the clock as it was when the screen was entered, so the list
     * does not silently re-sort under the user's finger while they are reaching for a deck - and they
     * are re-evaluated on the next entry, which is the moment the numbers actually matter.
     *
     * Arranged as a tree: each top-level deck in recent-use order, its subdecks directly beneath it.
     * A subject is ordered by the most recent use of anything inside it, so studying Chapter 4 brings
     * Biology to the top - which is what "the deck I was just using" means to a student.
     */
    val rows = repository.deckSummaries()
        .map { all -> arrange(all) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The current streak, in days.
     *
     * Ninety days of history is plenty: a streak longer than that is unbroken by definition, and
     * loading a user's entire review log to draw one number would get slower every month.
     */
    val streak = repository
        .dailyCountsInLast(days = 90)
        .map { counts ->
            Streak.current(Streak.parseDays(counts.map { it.day }), LocalDate.now())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun createDeck(name: String, script: ScriptOption) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createDeck(name, script) }
    }

    companion object {
        fun arrange(all: List<DeckSummary>): List<DeckRowModel> {
            val ids = all.mapTo(HashSet()) { it.id }
            // A subdeck whose parent has gone missing is shown at the top level rather than lost.
            val (children, roots) = all.partition { it.parentId != null && it.parentId in ids }
            val byParent = children.groupBy { it.parentId }
            fun recency(deck: DeckSummary): Long =
                maxOf(deck.lastUsedAt, byParent[deck.id].orEmpty().maxOfOrNull { it.lastUsedAt } ?: 0L)
            return roots.sortedByDescending(::recency).flatMap { root ->
                listOf(DeckRowModel(root, isChild = false)) +
                    byParent[root.id].orEmpty().sortedBy { it.name.lowercase() }.map { DeckRowModel(it, isChild = true) }
            }
        }
    }
}

@Composable
fun DecksScreen(
    onOpenDeck: (Long) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onInsights: () -> Unit,
    onSearch: () -> Unit
) {
    val viewModel = repositoryViewModel { DecksViewModel(it) }
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var showIntro by remember { mutableStateOf(!prefs.introSeen) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(extraTop = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Four destinations is the most a header can hold before it stops being scannable, so
            // About moves inside Settings rather than taking a fifth slot.
            val links: @Composable () -> Unit = {
                HeaderLink(stringResource(R.string.nav_find), onSearch)
                Spacer(Modifier.width(4.dp))
                HeaderLink(stringResource(R.string.nav_insights), onInsights)
                Spacer(Modifier.width(4.dp))
                HeaderLink(stringResource(R.string.nav_more), onSettings)
            }

            // Side by side the links are measured at their intrinsic width first and the title gets
            // whatever is left, because only the title is weighted - and the links cannot yield,
            // since a one-word destination that wraps is unreadable. Past about fontScale 1.45 that
            // left under 115dp for a 30sp title and Android broke "Abhyas" inside the word. So above
            // the threshold the header stacks instead: full-width title, links beneath it. Nothing
            // has to give way because they are no longer competing for the same row.
            val title = stringResource(R.string.app_name)
            val tagline = stringResource(R.string.tagline)
            if (LocalConfiguration.current.fontScale > 1.3f) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ScreenTitle(title, tagline)
                    Row(verticalAlignment = Alignment.CenterVertically) { links() }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f)) { ScreenTitle(title, tagline) }
                    Row(modifier = Modifier.padding(top = 2.dp)) { links() }
                }
            }

            // Only shown once there is one. A streak counter reading "0 days" on the first
            // launch is a scoreboard telling a new user they are already losing.
            if (streak > 0) {
                Spacer(Modifier.height(2.dp))
                Pill(
                    text = pluralStringResource(R.plurals.streak_days, streak, streak),
                    color = AbhyasColors.SaffronBright
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        if (showIntro) {
            item {
                FirstRunIntro(
                    onStart = {
                        prefs.introSeen = true
                        showIntro = false
                        creating = true
                    },
                    onDismiss = {
                        prefs.introSeen = true
                        showIntro = false
                    }
                )
            }
        }

        if (rows.isEmpty()) {
            if (!showIntro) {
                item {
                    EmptyState(
                        title = stringResource(R.string.decks_empty_title),
                        message = stringResource(R.string.decks_empty_message)
                    )
                }
            }
        } else {
            items(rows, key = { it.deck.id }) { row ->
                DeckRow(
                    deck = row.deck,
                    isChild = row.isChild,
                    onClick = { onOpenDeck(row.deck.id) }
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(stringResource(R.string.decks_new)) { creating = true }
        }
    }

    if (creating) {
        NewDeckDialog(
            onDismiss = { creating = false },
            onCreate = { name, script ->
                viewModel.createDeck(name, script)
                creating = false
            }
        )
    }
}

/**
 * The first thing a new user sees: what the app does, in three lines, and one card showing it.
 *
 * Before this the first screen was an empty list, and the whole idea - photograph your notes, the
 * app writes the questions - had to be inferred from a button reading "New deck". Shown once,
 * dismissible, and gone for good once dismissed.
 */
@Composable
private fun FirstRunIntro(onStart: () -> Unit, onDismiss: () -> Unit) {
    Card {
        Text(stringResource(R.string.intro_title), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        listOf(R.string.intro_step_1, R.string.intro_step_2, R.string.intro_step_3)
            .forEachIndexed { index, line ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "${index + 1}",
                        color = AbhyasColors.Saffron,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(stringResource(line), color = AbhyasColors.Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }

        Spacer(Modifier.height(14.dp))
        // The example card: a sentence as it might appear in someone's notes, and the card Abhyas
        // would write from it. Seeing the transformation once explains it better than any sentence.
        FieldLabel(stringResource(R.string.intro_example_from))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.intro_example_source),
            color = AbhyasColors.Dim,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Card {
                Text(stringResource(R.string.intro_example_front), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.intro_example_back), color = AbhyasColors.SaffronBright, fontSize = 14.5.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.intro_start), onClick = onStart)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextLink(stringResource(R.string.intro_dismiss), AbhyasColors.Muted, onDismiss)
        }
    }
}

@Composable
private fun DeckRow(deck: DeckSummary, isChild: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        // Indented, not nested in a box: the subdeck stays a full card with a full target, and the
        // indent alone says whose it is.
        modifier = if (isChild) Modifier.padding(start = 22.dp) else Modifier
    ) {
        Text(
            deck.name,
            fontSize = if (isChild) 15.5.sp else 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Only the counts that mean "there is work here" get a colour. A deck with nothing
            // due should look calm, not like it is nagging.
            if (deck.due > 0) Pill(stringResource(R.string.count_due, deck.due), AbhyasColors.Good)
            if (deck.newCount > 0) Pill(stringResource(R.string.count_new, deck.newCount), AbhyasColors.Saffron)
            if (deck.due == 0 && deck.newCount == 0) Pill(stringResource(R.string.deck_up_to_date), AbhyasColors.Muted)
            Pill(pluralStringResource(R.plurals.count_cards, deck.total, deck.total), AbhyasColors.Dim)
        }
    }
}

@Composable
internal fun NewDeckDialog(
    onDismiss: () -> Unit,
    onCreate: (String, ScriptOption) -> Unit,
    title: String = stringResource(R.string.decks_new),
    showScript: Boolean = true
) {
    var name by remember { mutableStateOf("") }
    // Asked once, here, rather than on every capture. It is a property of the textbook, so it
    // almost never changes after the deck exists - and it can still be changed on the deck.
    var script by remember { mutableStateOf(ScriptOption.DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.new_deck_hint),
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp
                )
                Spacer(Modifier.height(12.dp))
                AbhyasTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = stringResource(R.string.new_deck_placeholder),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onCreate(name, script) }
                    )
                )
                if (showScript) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(R.string.script_heading),
                        color = AbhyasColors.Dim,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    ScriptPicker(selected = script, onSelect = { script = it })
                }
            }
        },
        confirmButton = {
            DialogAction(
                stringResource(R.string.action_create),
                onClick = { onCreate(name, script) },
                enabled = name.isNotBlank()
            )
        },
        dismissButton = {
            DialogAction(stringResource(R.string.action_cancel), onDismiss, AbhyasColors.Muted, emphasised = false)
        }
    )
}

@Composable
private fun HeaderLink(label: String, onClick: () -> Unit) {
    // TextLink keeps the one-line behaviour - a one-word destination that wraps is unreadable - and
    // adds the padding inside the clickable that turns an 18dp glyph box into a real target.
    TextLink(text = label, color = AbhyasColors.Muted, onClick = onClick)
}

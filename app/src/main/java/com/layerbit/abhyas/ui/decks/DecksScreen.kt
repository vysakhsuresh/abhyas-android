package com.layerbit.abhyas.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.data.stats.Streak
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScriptPicker
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DecksViewModel(private val repository: AbhyasRepository) : ViewModel() {

    /**
     * Due counts are computed against a "now" captured when the flow is built, so the list does
     * not silently re-sort under the user's finger while they are reaching for a deck. It
     * refreshes when the screen is next entered, which is the moment the numbers actually matter.
     */
    val decks = repository.deckSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The current streak, in days.
     *
     * Ninety days of history is plenty: a streak longer than that is unbroken by definition, and
     * loading a user's entire review log to draw one number would get slower every month.
     */
    val streak = repository
        .dailyCounts(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90))
        .map { counts ->
            Streak.current(Streak.parseDays(counts.map { it.day }), LocalDate.now())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun createDeck(name: String, script: ScriptOption) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createDeck(name, script) }
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
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()

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
                HeaderLink("Find", onSearch)
                Spacer(Modifier.width(4.dp))
                HeaderLink("Insights", onInsights)
                Spacer(Modifier.width(4.dp))
                HeaderLink("More", onSettings)
            }

            // Side by side the links are measured at their intrinsic width first and the title gets
            // whatever is left, because only the title is weighted - and the links cannot yield,
            // since a one-word destination that wraps is unreadable. Past about fontScale 1.45 that
            // left under 115dp for a 30sp title and Android broke "Abhyas" inside the word. So above
            // the threshold the header stacks instead: full-width title, links beneath it. Nothing
            // has to give way because they are no longer competing for the same row.
            if (LocalConfiguration.current.fontScale > 1.3f) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ScreenTitle("Abhyas", "Your notes ask the questions.")
                    Row(verticalAlignment = Alignment.CenterVertically) { links() }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ScreenTitle("Abhyas", "Your notes ask the questions.")
                    }
                    Row(modifier = Modifier.padding(top = 2.dp)) { links() }
                }
            }

            // Only shown once there is one. A streak counter reading "0 days" on the first
            // launch is a scoreboard telling a new user they are already losing.
            if (streak > 0) {
                Spacer(Modifier.height(2.dp))
                Pill(
                    text = if (streak == 1) "1 day streak" else "$streak day streak",
                    color = AbhyasColors.SaffronBright
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        if (decks.isEmpty()) {
            item {
                EmptyState(
                    title = "No decks yet",
                    message = "Make a deck for a subject or a chapter, then photograph a page of " +
                        "notes. Abhyas reads it and writes the cards."
                )
            }
        } else {
            items(decks, key = { it.id }) { deck ->
                DeckRow(deck = deck, onClick = { onOpenDeck(deck.id) })
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            PrimaryButton("New deck") { creating = true }
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

@Composable
private fun DeckRow(deck: DeckSummary, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Text(deck.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Only the counts that mean "there is work here" get a colour. A deck with nothing
            // due should look calm, not like it is nagging.
            if (deck.due > 0) Pill("${deck.due} due", AbhyasColors.Good)
            if (deck.newCount > 0) Pill("${deck.newCount} new", AbhyasColors.Saffron)
            if (deck.due == 0 && deck.newCount == 0) Pill("Up to date", AbhyasColors.Muted)
            Pill("${deck.total} cards", AbhyasColors.Dim)
        }
    }
}

@Composable
private fun NewDeckDialog(onDismiss: () -> Unit, onCreate: (String, ScriptOption) -> Unit) {
    var name by remember { mutableStateOf("") }
    // Asked once, here, rather than on every capture. It is a property of the textbook, so it
    // almost never changes after the deck exists - and it can still be changed on the deck.
    var script by remember { mutableStateOf(ScriptOption.DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("New deck", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A subject, a chapter, or whatever you are revising.",
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Biology - Chapter 4", color = AbhyasColors.Dim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onCreate(name, script) }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = AbhyasColors.SurfaceDim,
                        unfocusedContainerColor = AbhyasColors.SurfaceDim,
                        focusedIndicatorColor = AbhyasColors.Saffron,
                        unfocusedIndicatorColor = AbhyasColors.Border
                    )
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "SCRIPT",
                    color = AbhyasColors.Dim,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(8.dp))
                ScriptPicker(selected = script, onSelect = { script = it })
            }
        },
        confirmButton = {
            Text(
                text = "Create",
                color = if (name.isBlank()) AbhyasColors.Dim else AbhyasColors.Saffron,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = name.isNotBlank()) { onCreate(name, script) }
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
private fun HeaderLink(label: String, onClick: () -> Unit) {
    // TextLink keeps the one-line behaviour - a one-word destination that wraps is unreadable - and
    // adds the padding inside the clickable that turns an 18dp glyph box into a real target.
    TextLink(text = label, color = AbhyasColors.Muted, onClick = onClick)
}

package com.layerbit.abhyas.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScriptPickerDialog
import com.layerbit.abhyas.ui.components.SecondaryButton
import com.layerbit.abhyas.ui.components.StatRow
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeckViewModel(
    private val repository: AbhyasRepository,
    private val deckId: Long
) : ViewModel() {

    val summary = repository.deckSummary(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cards = repository.cardsInDeck(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteCard(card: CardEntity) {
        viewModelScope.launch { repository.deleteCard(card) }
    }

    /** Other decks this one could be folded into. Never includes itself. */
    val otherDecks = repository.deckSummaries()
        .map { all -> all.filter { it.id != deckId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameDeck(deckId, name) }
    }

    fun mergeInto(destination: Long, onMerged: () -> Unit) {
        viewModelScope.launch {
            repository.mergeDecks(source = deckId, destination = destination)
            onMerged()
        }
    }

    fun setScript(script: ScriptOption) {
        viewModelScope.launch { repository.setDeckScript(deckId, script) }
    }

    /**
     * Correct a card's wording without disturbing its schedule.
     *
     * Only the two text fields are touched, deliberately. A typo in a question does not mean the
     * user has forgotten the fact, so rewriting it must not reset the interval, the ease or the
     * lapse count that months of reviews have established.
     */
    fun editCard(card: CardEntity, front: String, back: String) {
        if (front.isBlank() || back.isBlank()) return
        viewModelScope.launch {
            repository.updateCard(card.copy(front = front.trim(), back = back.trim()))
        }
    }

    fun deleteDeck(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteDeck(deckId)
            onDeleted()
        }
    }
}

@Composable
fun DeckScreen(
    deckId: Long,
    onStudy: () -> Unit,
    onAddCards: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val viewModel = repositoryViewModel(key = "deck-$deckId") { DeckViewModel(it, deckId) }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val otherDecks by viewModel.otherDecks.collectAsStateWithLifecycle()

    var confirmingDelete by remember { mutableStateOf(false) }
    var changingScript by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Back",
                    color = AbhyasColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onBack)
                )
                Row {
                    if (otherDecks.isNotEmpty()) {
                        Text(
                            text = "Merge",
                            color = AbhyasColors.Muted,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { merging = true }
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(
                        text = "Delete",
                        color = AbhyasColors.Again,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { confirmingDelete = true }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            // Tapping the title renames it - the obvious gesture, and it keeps a rename action
            // out of a header that already has two destructive-looking ones.
            Text(
                text = summary?.name ?: "",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                modifier = Modifier.clickable { renaming = true }
            )
        }

        item {
            DeckHeader(
                summary = summary,
                onStudy = onStudy,
                onAddCards = onAddCards,
                onChangeScript = { changingScript = true }
            )
        }

        if (cards.isEmpty()) {
            item {
                EmptyState(
                    title = "This deck is empty",
                    message = "Photograph a page of notes and Abhyas will suggest the cards."
                )
            }
        } else {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ALL CARDS",
                    color = AbhyasColors.Dim,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp
                )
            }
            items(cards, key = { it.id }) { card ->
                CardRow(
                    card = card,
                    onEdit = { editing = card },
                    onDelete = { viewModel.deleteCard(card) }
                )
            }
        }
    }

    if (renaming) {
        RenameDeckDialog(
            current = summary?.name.orEmpty(),
            onRename = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }

    if (merging) {
        MergeDeckDialog(
            sourceName = summary?.name.orEmpty(),
            destinations = otherDecks,
            onMerge = { destination ->
                merging = false
                viewModel.mergeInto(destination, onDeleted)
            },
            onDismiss = { merging = false }
        )
    }

    if (changingScript) {
        ScriptPickerDialog(
            selected = summary?.script ?: ScriptOption.DEFAULT,
            onSelect = viewModel::setScript,
            onDismiss = { changingScript = false }
        )
    }

    editing?.let { card ->
        EditCardDialog(
            card = card,
            onSave = { front, back ->
                viewModel.editCard(card, front, back)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = AbhyasColors.Surface,
            title = { Text("Delete this deck?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Its ${summary?.total ?: 0} cards and everything you have learned " +
                        "about them go too. This cannot be undone.",
                    color = AbhyasColors.Muted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Text(
                    text = "Delete",
                    color = AbhyasColors.Again,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            confirmingDelete = false
                            viewModel.deleteDeck(onDeleted)
                        }
                        .padding(12.dp)
                )
            },
            dismissButton = {
                Text(
                    text = "Cancel",
                    color = AbhyasColors.Muted,
                    modifier = Modifier
                        .clickable { confirmingDelete = false }
                        .padding(12.dp)
                )
            }
        )
    }
}

@Composable
private fun DeckHeader(
    summary: DeckSummary?,
    onStudy: () -> Unit,
    onAddCards: () -> Unit,
    onChangeScript: () -> Unit
) {
    val due = summary?.due ?: 0
    val newCount = summary?.newCount ?: 0
    val waiting = due + newCount

    Card {
        StatRow(
            listOf(
                due.toString() to "DUE",
                newCount.toString() to "NEW",
                (summary?.total ?: 0).toString() to "TOTAL"
            )
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (waiting > 0) "Study $waiting cards" else "Nothing due",
            enabled = waiting > 0,
            onClick = onStudy
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Add cards from a page", onClick = onAddCards)
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onChangeScript),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Reads pages in", color = AbhyasColors.Dim, fontSize = 12.5.sp)
            Text(
                text = "${summary?.script?.nativeLabel ?: ""}  ${summary?.script?.label ?: ""}",
                color = AbhyasColors.Saffron,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CardRow(card: CardEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = card.front,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Pill(card.state.shortLabel(), card.state.tint())
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(card.back, color = AbhyasColors.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.scheduleSummary(),
                    color = AbhyasColors.Dim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Edit",
                    color = AbhyasColors.Saffron,
                    fontSize = 12.5.sp,
                    modifier = Modifier.clickable(onClick = onEdit)
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    text = "Delete",
                    color = AbhyasColors.Again,
                    fontSize = 12.5.sp,
                    modifier = Modifier.clickable(onClick = onDelete)
                )
            }
        }
    }
}

private fun CardState.shortLabel(): String = when (this) {
    CardState.NEW -> "New"
    CardState.LEARNING -> "Learning"
    CardState.REVIEW -> "Review"
    CardState.RELEARNING -> "Relearning"
}

private fun CardState.tint() = when (this) {
    CardState.NEW -> AbhyasColors.Saffron
    CardState.LEARNING, CardState.RELEARNING -> AbhyasColors.Hard
    CardState.REVIEW -> AbhyasColors.Good
}

/** A plain-language summary of where this card sits, for the expanded row. */
private fun CardEntity.scheduleSummary(): String = when (state) {
    CardState.NEW -> "Not studied yet"
    CardState.LEARNING -> "Being learned"
    CardState.RELEARNING -> "Forgotten once, being relearned"
    CardState.REVIEW -> {
        val days = if (intervalDays == 1) "day" else "days"
        val lapseNote = when (lapses) {
            0 -> ""
            1 -> ", forgotten once"
            else -> ", forgotten $lapses times"
        }
        "Every $intervalDays $days$lapseNote"
    }
}

/**
 * Fix a card's wording.
 *
 * The schedule is deliberately not shown or touched here. Someone correcting a typo has not
 * forgotten the fact, so an edit must never cost them the interval, ease and lapse history that
 * months of reviews built up - and the surest way to guarantee that is to give the edit screen
 * no way to express it.
 */
@Composable
private fun EditCardDialog(
    card: CardEntity,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var front by remember(card.id) { mutableStateOf(card.front) }
    var back by remember(card.id) { mutableStateOf(card.back) }
    val valid = front.isNotBlank() && back.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("Edit card", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                EditField("Question", front) { front = it }
                Spacer(Modifier.height(12.dp))
                EditField("Answer", back) { back = it }

                if (!card.sourceText.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "FROM YOUR NOTES",
                        color = AbhyasColors.Dim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    // Shown but not editable: it is the record of what was actually on the page,
                    // and letting it drift from that would make it useless as a reference.
                    Text(
                        text = card.sourceText,
                        color = AbhyasColors.Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        },
        confirmButton = {
            Text(
                text = "Save",
                color = if (valid) AbhyasColors.Saffron else AbhyasColors.Dim,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = valid) { onSave(front, back) }
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
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(
            label.uppercase(),
            color = AbhyasColors.Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 15.sp, color = AbhyasColors.Text),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AbhyasColors.SurfaceDim,
                unfocusedContainerColor = AbhyasColors.SurfaceDim,
                focusedIndicatorColor = AbhyasColors.Saffron,
                unfocusedIndicatorColor = AbhyasColors.Border
            )
        )
    }
}

@Composable
private fun RenameDeckDialog(
    current: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("Rename deck", fontWeight = FontWeight.Bold) },
        text = { EditField("Name", name) { name = it } },
        confirmButton = {
            Text(
                text = "Rename",
                color = if (name.isBlank()) AbhyasColors.Dim else AbhyasColors.Saffron,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = name.isNotBlank()) { onRename(name) }
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

/**
 * Fold this deck into another one.
 *
 * Phrased as "move these cards into..." rather than "merge", because the outcome that matters to
 * the user is where their cards end up and which deck disappears. Cards keep their schedules;
 * only the filing changes.
 */
@Composable
private fun MergeDeckDialog(
    sourceName: String,
    destinations: List<DeckSummary>,
    onMerge: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text("Move cards into", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Every card in \"$sourceName\" moves to the deck you pick, keeping " +
                        "its schedule. \"$sourceName\" is then deleted.",
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(14.dp))
                destinations.forEach { deck ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AbhyasColors.SurfaceDim)
                            .clickable { onMerge(deck.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(deck.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${deck.total} cards",
                            color = AbhyasColors.Dim,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Text(
                text = "Cancel",
                color = AbhyasColors.Muted,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp)
            )
        }
    )
}

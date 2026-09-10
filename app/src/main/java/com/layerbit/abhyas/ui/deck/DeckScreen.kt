package com.layerbit.abhyas.ui.deck

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.SecondaryButton
import com.layerbit.abhyas.ui.components.StatRow
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.flow.SharingStarted
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

    var confirmingDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 32.dp),
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
                Text(
                    text = "Delete deck",
                    color = AbhyasColors.Again,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { confirmingDelete = true }
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = summary?.name ?: "",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
        }

        item { DeckHeader(summary = summary, onStudy = onStudy, onAddCards = onAddCards) }

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
                CardRow(card = card, onDelete = { viewModel.deleteCard(card) })
            }
        }
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
private fun DeckHeader(summary: DeckSummary?, onStudy: () -> Unit, onAddCards: () -> Unit) {
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
    }
}

@Composable
private fun CardRow(card: CardEntity, onDelete: () -> Unit) {
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

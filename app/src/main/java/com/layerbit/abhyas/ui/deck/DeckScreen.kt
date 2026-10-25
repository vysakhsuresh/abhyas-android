package com.layerbit.abhyas.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.db.DeckSummary
import com.layerbit.abhyas.data.db.MergeTarget
import com.layerbit.abhyas.data.db.ParentOption
import com.layerbit.abhyas.data.model.CardState
import com.layerbit.abhyas.data.model.CardType
import com.layerbit.abhyas.data.model.Tags
import com.layerbit.abhyas.data.ocr.ScriptOption
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.AbhyasTextField
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.DialogAction
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.FieldLabel
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScriptPickerDialog
import com.layerbit.abhyas.ui.components.SecondaryButton
import com.layerbit.abhyas.ui.components.SectionLabel
import com.layerbit.abhyas.ui.components.StatRow
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.decks.NewDeckDialog
import com.layerbit.abhyas.ui.labelRes
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

    val subdecks = repository.subdecks(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteCard(card: CardEntity) {
        viewModelScope.launch { repository.deleteCard(card) }
    }

    /**
     * Whether there is anywhere to merge into - the only thing the header link needs to know.
     *
     * This used to observe the whole collection's aggregate query and call `isNotEmpty()` on it,
     * which put a third live query over `cards` on this screen and re-ran every deck's counts
     * whenever the user edited one card.
     */
    val otherDeckCount = repository.otherDeckCount(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Loaded when the merge dialog opens, rather than kept live behind it. */
    var mergeTargets by mutableStateOf<List<MergeTarget>>(emptyList())
        private set

    /** Loaded when the "file under" dialog opens. */
    var parentOptions by mutableStateOf<List<ParentOption>>(emptyList())
        private set

    fun loadMergeTargets() {
        viewModelScope.launch { mergeTargets = repository.mergeTargets(deckId) }
    }

    fun loadParentOptions() {
        viewModelScope.launch { parentOptions = repository.parentOptions(deckId) }
    }

    fun setParent(parentId: Long?) {
        viewModelScope.launch { repository.setParent(deckId, parentId) }
    }

    fun createSubdeck(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createDeck(name, parentId = deckId) }
    }

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
     * Correct a card's wording, or its tags, without disturbing its schedule.
     *
     * Only the text fields are touched, deliberately. A typo in a question does not mean the
     * user has forgotten the fact, so rewriting it must not reset the interval, the ease or the
     * lapse count that months of reviews have established.
     */
    fun editCard(card: CardEntity, front: String, back: String, tags: String) {
        if (front.isBlank() || back.isBlank()) return
        viewModelScope.launch {
            repository.updateCard(
                card.copy(front = front.trim(), back = back.trim(), tags = Tags.normalise(tags))
            )
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
    onAddImageCards: () -> Unit,
    onOpenDeck: (Long) -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val viewModel = repositoryViewModel(key = "deck-$deckId") { DeckViewModel(it, deckId) }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val subdecks by viewModel.subdecks.collectAsStateWithLifecycle()
    val otherDeckCount by viewModel.otherDeckCount.collectAsStateWithLifecycle()

    var confirmingDelete by remember { mutableStateOf(false) }
    var changingScript by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var filing by remember { mutableStateOf(false) }
    var addingSubdeck by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardEntity?>(null) }
    var confirmingCardDelete by remember { mutableStateOf<CardEntity?>(null) }

    val isSubdeck = summary?.parentId != null

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
                TextLink(stringResource(R.string.action_back), AbhyasColors.Muted, onBack)
                Row {
                    if (otherDeckCount > 0) {
                        TextLink(stringResource(R.string.deck_file_under), AbhyasColors.Muted, {
                            viewModel.loadParentOptions()
                            filing = true
                        })
                        TextLink(stringResource(R.string.deck_merge), AbhyasColors.Muted, {
                            viewModel.loadMergeTargets()
                            merging = true
                        })
                    }
                    TextLink(stringResource(R.string.action_delete), AbhyasColors.Again, { confirmingDelete = true })
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
                modifier = Modifier
                    // "Rename deck" spelled out: tapping a title to rename it is a discoverable
                    // gesture by sight and an invisible one by ear, since nothing about a heading
                    // suggests it can be activated.
                    .semantics { heading() }
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.deck_rename)) {
                        renaming = true
                    }
                    .padding(vertical = 4.dp)
            )
        }

        item {
            DeckHeader(
                summary = summary,
                hasSubdecks = subdecks.isNotEmpty(),
                onStudy = onStudy,
                onAddCards = onAddCards,
                onAddImageCards = onAddImageCards,
                onChangeScript = { changingScript = true }
            )
        }

        // Subdecks: listed with their own counts, and a way to add one - but only on a top-level
        // deck, since the tree is one level deep.
        if (!isSubdeck) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The heading only once there is something under it; until then the link alone
                    // says the option exists without a labelled empty section.
                    if (subdecks.isNotEmpty()) {
                        SectionLabel(
                            text = stringResource(R.string.deck_subdecks),
                            color = AbhyasColors.Dim,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.2.sp
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    TextLink(
                        stringResource(R.string.deck_add_subdeck),
                        AbhyasColors.Saffron,
                        { addingSubdeck = true },
                        fontSize = 13.sp
                    )
                }
            }
            items(subdecks, key = { "sub-${it.id}" }) { sub ->
                SubdeckRow(sub) { onOpenDeck(sub.id) }
            }
        }

        if (cards.isEmpty()) {
            if (subdecks.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.deck_empty_title),
                        message = stringResource(R.string.deck_empty_message)
                    )
                }
            }
        } else {
            item {
                Spacer(Modifier.height(4.dp))
                SectionLabel(
                    text = stringResource(R.string.deck_all_cards),
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
                    onDelete = { confirmingCardDelete = card }
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

    if (addingSubdeck) {
        NewDeckDialog(
            title = stringResource(R.string.deck_add_subdeck),
            showScript = false,
            onDismiss = { addingSubdeck = false },
            onCreate = { name, _ ->
                viewModel.createSubdeck(name)
                addingSubdeck = false
            }
        )
    }

    if (filing) {
        FileUnderDialog(
            options = viewModel.parentOptions,
            isSubdeck = isSubdeck,
            hasSubdecks = subdecks.isNotEmpty(),
            onPick = {
                viewModel.setParent(it)
                filing = false
            },
            onDismiss = { filing = false }
        )
    }

    if (merging) {
        MergeDeckDialog(
            sourceName = summary?.name.orEmpty(),
            destinations = viewModel.mergeTargets,
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
            onSave = { front, back, tags ->
                viewModel.editCard(card, front, back, tags)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    // Deleting a card is as irreversible as deleting a deck and had no confirmation at all, on a
    // 17dp target next to Edit. There is no undo for it either - unlike a graded review - so the
    // dialog is the only thing between a mistap and losing a card and its whole history.
    confirmingCardDelete?.let { card ->
        ConfirmDialog(
            title = stringResource(R.string.card_delete_title),
            message = stringResource(R.string.card_delete_message, card.front),
            onConfirm = {
                confirmingCardDelete = null
                viewModel.deleteCard(card)
            },
            onDismiss = { confirmingCardDelete = null }
        )
    }

    if (confirmingDelete) {
        val total = summary?.total ?: 0
        ConfirmDialog(
            title = stringResource(R.string.deck_delete_title),
            message = buildString {
                append(pluralStringResource(R.plurals.deck_delete_message, total, total))
                if (subdecks.isNotEmpty()) append(" ").append(stringResource(R.string.deck_delete_keeps_subdecks))
            },
            onConfirm = {
                confirmingDelete = false
                viewModel.deleteDeck(onDeleted)
            },
            onDismiss = { confirmingDelete = false }
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = AbhyasColors.Muted, fontSize = 14.sp) },
        confirmButton = { DialogAction(stringResource(R.string.action_delete), onConfirm, AbhyasColors.Again) },
        dismissButton = {
            DialogAction(stringResource(R.string.action_cancel), onDismiss, AbhyasColors.Muted, emphasised = false)
        }
    )
}

@Composable
private fun DeckHeader(
    summary: DeckSummary?,
    hasSubdecks: Boolean,
    onStudy: () -> Unit,
    onAddCards: () -> Unit,
    onAddImageCards: () -> Unit,
    onChangeScript: () -> Unit
) {
    val due = summary?.due ?: 0
    val newCount = summary?.newCount ?: 0
    val waiting = due + newCount

    Card {
        StatRow(
            listOf(
                due.toString() to stringResource(R.string.stat_due),
                newCount.toString() to stringResource(R.string.stat_new),
                (summary?.total ?: 0).toString() to stringResource(R.string.stat_total)
            )
        )
        if (hasSubdecks) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.deck_includes_subdecks), color = AbhyasColors.Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (waiting > 0) {
                pluralStringResource(R.plurals.deck_study, waiting, waiting)
            } else {
                stringResource(R.string.study_nothing_due)
            },
            enabled = waiting > 0,
            onClick = onStudy
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(stringResource(R.string.deck_add_from_page), onClick = onAddCards)
        Spacer(Modifier.height(8.dp))
        SecondaryButton(stringResource(R.string.deck_add_image_cards), onClick = onAddImageCards)
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onChangeScript),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.deck_reads_pages_in), color = AbhyasColors.Dim, fontSize = 12.5.sp)
            Text(
                text = summary?.script?.let { "${it.nativeLabel}  ${stringResource(it.labelRes())}" } ?: "",
                color = AbhyasColors.Saffron,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SubdeckRow(deck: DeckSummary, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Text(deck.name, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (deck.due > 0) Pill(stringResource(R.string.count_due, deck.due), AbhyasColors.Good)
            if (deck.newCount > 0) Pill(stringResource(R.string.count_new, deck.newCount), AbhyasColors.Saffron)
            Pill(pluralStringResource(R.plurals.count_cards, deck.total, deck.total), AbhyasColors.Dim)
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
                text = if (card.type == CardType.OCCLUSION) {
                    stringResource(R.string.card_image_row, card.front, card.back)
                } else {
                    card.front
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Pill(stringResource(card.state.labelRes()), card.state.tint())
        }
        if (card.tags.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(Tags.display(card.tags), color = AbhyasColors.Easy, fontSize = 12.sp)
        }
        if (expanded) {
            if (card.type != CardType.OCCLUSION) {
                Spacer(Modifier.height(10.dp))
                Text(card.back, color = AbhyasColors.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scheduleSummary(card),
                    color = AbhyasColors.Dim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                // Both were 12.5sp Texts with nothing but a clickable, so each target was a single
                // text line - about 17dp - and they sat 18dp apart inside a Card that is itself
                // clickable. A near miss collapsed the row; a slight overshoot hit Delete. The
                // padding goes inside the clickable, which is what actually grows the target, and
                // the gap between them shrinks because each one is now much wider.
                TextLink(
                    text = stringResource(R.string.action_edit),
                    color = AbhyasColors.Saffron,
                    onClick = onEdit,
                    fontSize = 12.5.sp,
                    onClickLabel = stringResource(R.string.a11y_edit_card)
                )
                Spacer(Modifier.width(8.dp))
                TextLink(
                    text = stringResource(R.string.action_delete),
                    color = AbhyasColors.Again,
                    onClick = onDelete,
                    fontSize = 12.5.sp,
                    onClickLabel = stringResource(R.string.a11y_delete_card)
                )
            }
        }
    }
}

private fun CardState.tint() = when (this) {
    CardState.NEW -> AbhyasColors.Saffron
    CardState.LEARNING, CardState.RELEARNING -> AbhyasColors.Hard
    CardState.REVIEW -> AbhyasColors.Good
}

/** A plain-language summary of where this card sits, for the expanded row. */
@Composable
private fun scheduleSummary(card: CardEntity): String = when (card.state) {
    CardState.NEW -> stringResource(R.string.schedule_not_studied)
    CardState.LEARNING -> stringResource(R.string.schedule_learning)
    CardState.RELEARNING -> stringResource(R.string.schedule_relearning)
    CardState.REVIEW -> {
        val every = pluralStringResource(R.plurals.schedule_every_days, card.intervalDays, card.intervalDays)
        if (card.lapses == 0) every
        else every + ", " + pluralStringResource(R.plurals.schedule_forgotten, card.lapses, card.lapses)
    }
}

/**
 * Fix a card's wording or tags.
 *
 * The schedule is deliberately not shown or touched here. Someone correcting a typo has not
 * forgotten the fact, so an edit must never cost them the interval, ease and lapse history that
 * months of reviews built up - and the surest way to guarantee that is to give the edit screen
 * no way to express it.
 */
@Composable
private fun EditCardDialog(
    card: CardEntity,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var front by remember(card.id) { mutableStateOf(card.front) }
    var back by remember(card.id) { mutableStateOf(card.back) }
    var tags by remember(card.id) { mutableStateOf(Tags.display(card.tags)) }
    val valid = front.isNotBlank() && back.isNotBlank()
    val isImage = card.type == CardType.OCCLUSION

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(stringResource(R.string.card_edit_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FieldLabel(stringResource(if (isImage) R.string.occlusion_card_title else R.string.label_question))
                Spacer(Modifier.height(4.dp))
                AbhyasTextField(value = front, onValueChange = { front = it })
                Spacer(Modifier.height(12.dp))
                FieldLabel(stringResource(if (isImage) R.string.occlusion_hidden_label_plain else R.string.label_answer))
                Spacer(Modifier.height(4.dp))
                AbhyasTextField(value = back, onValueChange = { back = it })
                Spacer(Modifier.height(12.dp))
                FieldLabel(stringResource(R.string.label_tags))
                Spacer(Modifier.height(4.dp))
                AbhyasTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    placeholder = stringResource(R.string.tags_placeholder),
                    singleLine = true
                )

                if (!card.sourceText.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    FieldLabel(stringResource(R.string.from_your_notes))
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
            DialogAction(stringResource(R.string.action_save), { onSave(front, back, tags) }, enabled = valid)
        },
        dismissButton = {
            DialogAction(stringResource(R.string.action_cancel), onDismiss, AbhyasColors.Muted, emphasised = false)
        }
    )
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
        title = { Text(stringResource(R.string.deck_rename), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                FieldLabel(stringResource(R.string.label_name))
                Spacer(Modifier.height(4.dp))
                AbhyasTextField(value = name, onValueChange = { name = it }, singleLine = true)
            }
        },
        confirmButton = {
            DialogAction(stringResource(R.string.action_rename), { onRename(name) }, enabled = name.isNotBlank())
        },
        dismissButton = {
            DialogAction(stringResource(R.string.action_cancel), onDismiss, AbhyasColors.Muted, emphasised = false)
        }
    )
}

/**
 * File this deck inside another - "Chapter 4" under "Biology" - or back at the top level.
 *
 * A deck that already holds subdecks cannot itself be filed, which keeps the tree one level deep;
 * the dialog says so rather than offering a list that would be refused.
 */
@Composable
private fun FileUnderDialog(
    options: List<ParentOption>,
    isSubdeck: Boolean,
    hasSubdecks: Boolean,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(stringResource(R.string.deck_file_under_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(if (hasSubdecks) R.string.deck_file_under_blocked else R.string.deck_file_under_hint),
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(14.dp))
                if (isSubdeck) {
                    PickRow(stringResource(R.string.deck_top_level), null) { onPick(null) }
                }
                if (!hasSubdecks) {
                    options.forEach { option ->
                        PickRow(option.name, null) { onPick(option.id) }
                    }
                }
            }
        },
        confirmButton = {
            DialogAction(stringResource(R.string.action_cancel), onDismiss, AbhyasColors.Muted, emphasised = false)
        }
    )
}

@Composable
private fun PickRow(label: String, detail: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AbhyasColors.SurfaceDim)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, color = AbhyasColors.Dim, fontSize = 12.sp)
    }
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
    destinations: List<MergeTarget>,
    onMerge: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbhyasColors.Surface,
        title = { Text(stringResource(R.string.merge_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.merge_message, sourceName),
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(14.dp))
                destinations.forEach { deck ->
                    PickRow(deck.name, pluralStringResource(R.plurals.count_cards, deck.total, deck.total)) {
                        onMerge(deck.id)
                    }
                }
            }
        },
        confirmButton = {
            DialogAction(stringResource(R.string.action_cancel), onDismiss, AbhyasColors.Muted, emphasised = false)
        }
    )
}

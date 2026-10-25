package com.layerbit.abhyas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.R
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.model.CardType
import com.layerbit.abhyas.data.model.Tags
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.AbhyasTextField
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.PrimaryButton
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(repository: AbhyasRepository) : ViewModel() {

    /**
     * The query, as Compose snapshot state rather than a StateFlow.
     *
     * A text field has to read back the value it was just given *within the same frame*. Routed
     * through a StateFlow and `collectAsStateWithLifecycle`, it did not: that collection resumes on
     * AndroidUiDispatcher.Main, i.e. on the next Choreographer frame, so for the rest of the current
     * one the field still held the previous string - and a text field pushes its composed value back
     * to the IME, telling it a text that disagreed with the edit just committed. On a fast typist or
     * a predictive keyboard that is where dropped and reordered characters come from.
     */
    var term by mutableStateOf("")
        private set

    /** The tag the results are narrowed to, or blank for any. */
    var tag by mutableStateOf("")
        private set

    /**
     * Results, debounced.
     *
     * Without the delay every keystroke runs a LIKE across the whole collection, and on a long
     * query the earlier requests can land after the later ones and leave the list showing results
     * for a prefix the user has already finished typing. flatMapLatest cancels the stale one;
     * the debounce stops most of them being started at all.
     */
    val results = snapshotFlow { term to tag }
        .debounce(180)
        .flatMapLatest { (t, g) -> repository.search(t, g) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags = repository.tags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun search(value: String) {
        term = value
    }

    fun selectTag(value: String) {
        tag = if (tag == value) "" else value
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onCram: (term: String, tag: String) -> Unit) {
    val viewModel = repositoryViewModel { SearchViewModel(it) }
    val term = viewModel.term
    val tag = viewModel.tag
    val results by viewModel.results.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val filtering = term.isNotBlank() || tag.isNotBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            TextLink(
                text = stringResource(R.string.action_back),
                color = AbhyasColors.Muted,
                onClick = onBack,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(18.dp))
            ScreenTitle(stringResource(R.string.search_title))
            AbhyasTextField(
                value = term,
                onValueChange = viewModel::search,
                singleLine = true,
                placeholder = stringResource(R.string.search_placeholder)
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.take(MAX_TAG_CHIPS).forEach { t ->
                        TagChip(
                            label = "#${t.tag} · ${t.count}",
                            selected = t.tag == tag,
                            onClick = { viewModel.selectTag(t.tag) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        when {
            !filtering -> item {
                EmptyState(
                    title = stringResource(R.string.search_empty_title),
                    message = stringResource(R.string.search_empty_message)
                )
            }

            results.isEmpty() -> item {
                EmptyState(
                    title = stringResource(R.string.search_none_title),
                    message = stringResource(R.string.search_none_message, term.ifBlank { "#$tag" })
                )
            }

            else -> {
                item {
                    // Studying what a filter found, ahead of the schedule - "exam on Thursday". The
                    // real schedule is left alone, which the cram screen says on every card.
                    PrimaryButton(
                        text = pluralStringResource(R.plurals.cram_study, results.size, results.size),
                        onClick = { onCram(term, tag) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.cram_hint),
                        color = AbhyasColors.Dim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.count_cards, results.size, results.size),
                        color = AbhyasColors.Dim,
                        fontSize = 12.sp
                    )
                }
                items(results, key = { it.id }) { ResultRow(it) }
            }
        }
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) AbhyasColors.OnSaffron else AbhyasColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) AbhyasColors.Saffron else AbhyasColors.SurfaceDim)
            .border(1.dp, if (selected) AbhyasColors.Saffron else AbhyasColors.Border, RoundedCornerShape(999.dp))
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun ResultRow(card: CardEntity) {
    Card {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
            if (card.suspended) {
                Spacer(Modifier.width(10.dp))
                Pill(stringResource(R.string.set_aside), AbhyasColors.Again)
            }
        }
        if (card.type != CardType.OCCLUSION) {
            Spacer(Modifier.height(6.dp))
            Text(card.back, color = AbhyasColors.Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
        }
        if (card.tags.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(Tags.display(card.tags), color = AbhyasColors.Easy, fontSize = 12.sp)
        }
    }
}

/** Past this many, a row of tags is a list to scroll through rather than a choice to make. */
private const val MAX_TAG_CHIPS = 30

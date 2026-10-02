package com.layerbit.abhyas.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.data.db.CardEntity
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.EmptyState
import com.layerbit.abhyas.ui.components.Pill
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(repository: AbhyasRepository) : ViewModel() {

    private val _term = MutableStateFlow("")
    val term = _term.asStateFlow()

    /**
     * Results, debounced.
     *
     * Without the delay every keystroke runs a LIKE across the whole collection, and on a long
     * query the earlier requests can land after the later ones and leave the list showing results
     * for a prefix the user has already finished typing. flatMapLatest cancels the stale one;
     * the debounce stops most of them being started at all.
     */
    val results = _term
        .debounce(180)
        .flatMapLatest { repository.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun search(value: String) {
        _term.value = value
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit) {
    val viewModel = repositoryViewModel { SearchViewModel(it) }
    val term by viewModel.term.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Back",
                color = AbhyasColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Spacer(Modifier.height(18.dp))
            ScreenTitle("Find a card")
            OutlinedTextField(
                value = term,
                onValueChange = viewModel::search,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("A word from the card", color = AbhyasColors.Dim) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AbhyasColors.SurfaceDim,
                    unfocusedContainerColor = AbhyasColors.SurfaceDim,
                    focusedIndicatorColor = AbhyasColors.Saffron,
                    unfocusedIndicatorColor = AbhyasColors.Border
                )
            )
            Spacer(Modifier.height(6.dp))
        }

        when {
            term.isBlank() -> item {
                EmptyState(
                    title = "Search every deck at once",
                    message = "It looks at the question, the answer, and the sentence the card " +
                        "was made from - which is often where the word you half-remember is."
                )
            }

            results.isEmpty() -> item {
                EmptyState(
                    title = "Nothing matches",
                    message = "No card in any deck contains \"$term\"."
                )
            }

            else -> {
                item {
                    Text(
                        text = if (results.size == 1) "1 card" else "${results.size} cards",
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
private fun ResultRow(card: CardEntity) {
    Card {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = card.front,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
                modifier = Modifier.weight(1f)
            )
            if (card.suspended) {
                Spacer(Modifier.width(10.dp))
                Pill("Set aside", AbhyasColors.Again)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.back, color = AbhyasColors.Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
}

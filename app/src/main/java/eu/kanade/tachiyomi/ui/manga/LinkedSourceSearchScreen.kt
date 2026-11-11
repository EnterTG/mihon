package eu.kanade.tachiyomi.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.presentation.core.screens.LoadingScreen

/** Same search experience as migration, but results are checkable and do not become library entries. */
class LinkedSourceSearchScreen(private val mangaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel =
            assistedMetroViewModel<LinkedSourceSearchViewModel, LinkedSourceSearchViewModel.Factory> {
                create(mangaId = mangaId)
            }

        val state by viewModel.state.collectAsState()
        val selected by viewModel.selected.collectAsState()
        val focusedSource by viewModel.focusedSource.collectAsState()
        val saving by viewModel.saving.collectAsState()
        val error by viewModel.error.collectAsState()

        LaunchedEffect(viewModel) {
            viewModel.saved.collectLatest { navigator.pop() }
        }

        if (state.from == null) {
            LoadingScreen()
            return
        }

        MigrateSearchScreen(
            state = state,
            fromSourceId = state.from?.source,
            navigateUp = navigator::pop,
            onChangeSearchQuery = viewModel::updateSearchQuery,
            onSearch = { viewModel.search() },
            onChangeSearchFilter = viewModel::setSourceFilter,
            onToggleResults = viewModel::toggleFilterResults,
            getManga = { viewModel.getManga(it) },
            onClickSource = { viewModel.filterToSource(it.id) },
            onClickItem = viewModel::toggle,
            onLongClickItem = viewModel::toggle,
            selectedMangaIds = selected.values.mapTo(HashSet()) { it.id },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (focusedSource != null) {
                        TextButton(onClick = { viewModel.filterToSource(null) }, enabled = !saving) {
                            Text("Wszystkie źródła")
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wybrano: ${selected.size}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = selected.values.joinToString { it.title }
                                    .ifBlank { "Zaznacz wersje z wyników wyszukiwania" },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = viewModel::saveSelection, enabled = !saving) {
                            Text("Zapisz")
                        }
                    }
                    if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (error != null) {
                        Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }
}

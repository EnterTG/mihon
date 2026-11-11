package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.MangaSourceLinks
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * Migration-style search for source editions of a title, without adding any result to the library.
 * One edition per extension source may be selected; the original manga remains the only favorite.
 */
@AssistedInject
class LinkedSourceSearchViewModel(
    @Assisted private val mangaId: Long,
    sourcePreferences: SourcePreferences,
    extensionManager: ExtensionManager,
    networkToLocalManga: NetworkToLocalManga,
    getManga: GetManga,
    preferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val sourceLinks: MangaSourceLinks,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) : SearchViewModel(
    sourcePreferences = sourcePreferences,
    sourceManager = sourceManager,
    extensionManager = extensionManager,
    networkToLocalManga = networkToLocalManga,
    getManga = getManga,
    preferences = preferences,
) {
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): LinkedSourceSearchViewModel
    }

    /** One chosen catalog entry per source ID. Values are ordinary non-favorite manga records. */
    val selected = MutableStateFlow<Map<Long, Manga>>(emptyMap())
    val focusedSource = MutableStateFlow<Long?>(null)
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val savedChannel = Channel<Unit>(Channel.BUFFERED)
    val saved = savedChannel.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manga = mangaRepository.getMangaById(mangaId)
                selected.value = sourceLinks.linked(mangaId).associateBy { it.source }
                updateState {
                    it.copy(
                        from = manga,
                        searchQuery = manga.title,
                        sourceFilter = SourceFilter.All,
                    )
                }
                search()
            } catch (e: Exception) {
                error.value = e.message ?: "Nie można załadować powiązanych źródeł"
            }
        }
    }

    override suspend fun getEnabledSources(): List<Source> {
        val original = state.value.from?.source
        val focused = focusedSource.value
        return super.getEnabledSources()
            .filterIsInstance<HttpSource>()
            .filter { it.id != original && (focused == null || it.id == focused) }
    }

    /** Tap a source heading to search only there; use 'All sources' to restore the full results. */
    fun filterToSource(id: Long?) {
        if (focusedSource.value == id) return
        focusedSource.value = id
        search(force = true)
    }

    fun toggle(manga: Manga) {
        if (saving.value || manga.id == mangaId || manga.source == state.value.from?.source) return
        selected.update { previous ->
            if (previous[manga.source]?.id == manga.id) {
                previous - manga.source
            } else {
                previous + (manga.source to manga)
            }
        }
        error.value = null
    }

    fun saveSelection() {
        if (saving.value) return
        saving.value = true
        error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val desired = selected.value
                val original = mangaRepository.getMangaById(mangaId)
                require(original.favorite) { "Główna książka musi być w bibliotece" }
                require(desired.keys.none { it == original.source }) { "Nie można dodać oryginalnego źródła" }

                val existing = sourceLinks.linked(mangaId).associateBy { it.source }
                val additions = desired.values.filter { candidate ->
                    existing[candidate.source]?.id != candidate.id
                }

                // Validate/fetch all requested editions before modifying any links.
                // This also populates their database chapters for per-chapter fallback.
                for (manga in additions) {
                    val source = sourceManager.get(manga.source) as? HttpSource
                        ?: error("Źródło ${manga.source} nie jest zainstalowane")
                    updateMangaFromRemote(
                        source = source,
                        manga = manga,
                        fetchDetails = true,
                        fetchChapters = true,
                        manualFetch = false,
                    ).getOrThrow()
                }

                for ((sourceId, oldManga) in existing) {
                    if (desired[sourceId]?.id != oldManga.id) {
                        sourceLinks.unlink(mangaId, oldManga.id)
                    }
                }
                for (manga in additions) {
                    sourceLinks.link(mangaId, manga.id)
                }
                savedChannel.send(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = e.message ?: "Nie udało się powiązać źródeł"
            } finally {
                saving.value = false
            }
        }
    }
}

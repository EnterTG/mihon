package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.download.remote.RemoteLibrary
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter

internal class RemotePageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: HttpSource?,
    private val library: RemoteLibrary,
    private val originChapter: Chapter = chapter.chapter,
    private val manualOverride: Boolean = false,
) : PageLoader() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override var isLocal = false
    private var storedKey: String? = null

    override suspend fun getPages(): List<ReaderPage> {
        val stored = library.ensure(manga, chapter.chapter, source, originChapter, mirrorArchive = manualOverride)
        storedKey = stored.key
        return List(stored.pageCount) { index ->
            ReaderPage(index).also { it.chapter = chapter }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        try {
            page.status = Page.State.DownloadImage
            val key = checkNotNull(storedKey) { "Remote chapter has not been loaded" }
            val file = library.pageFile(key, page.index)
            page.stream = { file.inputStream() }
            page.status = Page.State.Ready
        } catch (e: Exception) {
            if (e is CancellationException) {
                page.status = Page.State.Queue
                throw e
            }
            page.status = Page.State.Error(e)
        }
    }

    override fun retryPage(page: ReaderPage) {
        scope.launch { loadPage(page) }
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
    }
}

package eu.kanade.tachiyomi.data.download.remote

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Migrates only files that can be associated unambiguously with a library chapter. */
@Inject
class LocalChapterMigration(
    private val mangaRepository: MangaRepository,
    private val chapters: GetChaptersByMangaId,
    private val sources: SourceManager,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val remote: RemoteLibrary,
) {
    private data class Candidate(val manga: Manga, val chapter: Chapter, val source: Source, val file: UniFile)

    suspend fun run(progress: suspend (Int, Int, Int) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
        check(remote.enabled) { "Network library is disabled" }
        val namespace = remote.configurationId()
        // Refresh first: never use an old offline index as proof of a server copy.
        remote.refresh()
        val candidates = mutableListOf<Candidate>()
        val knownManga = (mangaRepository.getFavorites() + mangaRepository.getReadMangaNotInLibrary()).distinctBy { it.id }
        for (manga in knownManga) {
            currentCoroutineContext().ensureActive()
            val source = sources.get(manga.source) ?: continue
            for (chapter in chapters.await(manga.id)) {
                val file = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
                    ?: continue
                candidates += Candidate(manga, chapter, source, file)
            }
        }
        val ambiguous = candidates.groupingBy { it.file.uri.toString() }.eachCount().filterValues { it > 1 }.keys
        var migrated = 0
        var skipped = 0
        progress(0, migrated, skipped)
        for ((index, item) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(remote.enabled && remote.configurationId() == namespace) { "Network library configuration changed" }
            try {
                check(item.file.uri.toString() !in ambiguous) { "Ambiguous local chapter" }
                migrateVerifiedChapter(
                    readLocal = { localPageHashes(item.file) },
                    verifyRemote = { hashes ->
                        val stored = remote.ensure(item.manga, item.chapter, item.source as? HttpSource)
                        stored.pageCount == hashes.size && remote.matchesPages(stored, hashes, namespace)
                    },
                    configurationUnchanged = { remote.enabled && remote.configurationId() == namespace },
                    deleteLocal = { item.file.delete() },
                )
                cache.removeChapter(item.chapter, item.manga)
                migrated++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                skipped++
            }
            progress(index + 1, migrated, skipped)
        }
        migrated to skipped
    }
}

/** No local deletion is attempted until both copies and the server configuration have been checked. */
internal suspend fun migrateVerifiedChapter(
    readLocal: suspend () -> List<String>,
    verifyRemote: suspend (List<String>) -> Boolean,
    configurationUnchanged: () -> Boolean,
    deleteLocal: () -> Boolean,
) {
    check(configurationUnchanged()) { "Network library configuration changed" }
    val before = readLocal()
    check(before.isNotEmpty()) { "No pages" }
    check(verifyRemote(before)) { "Server pages differ or are unavailable" }
    check(before == readLocal()) { "Local chapter changed during migration" }
    check(configurationUnchanged()) { "Network library configuration changed" }
    check(deleteLocal()) { "Could not delete local chapter" }
}

/** Return ordered page digests, refusing to delete directories or archives with unknown content. */
internal suspend fun localPageHashes(file: UniFile): List<String> {
    val pages = if (file.isDirectory) {
        val files = file.listFiles().orEmpty()
        check(files.isNotEmpty() && files.all { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } })
        files.sortedBy { it.name }.map { it.openInputStream().use { stream -> hashStream(stream) } }
    } else {
        check(file.isFile && file.name?.endsWith(".cbz", ignoreCase = true) == true)
        val entries = mutableListOf<Pair<String, String>>()
        file.openInputStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        check(
                            entry.name.substringAfterLast('.').lowercase() in
                                setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl"),
                        )
                        entries += entry.name to hashStream(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
        check(entries.map { it.first }.distinct().size == entries.size)
        entries.sortedWith { a, b -> a.first.compareToCaseInsensitiveNaturalOrder(b.first) }.map { it.second }
    }
    check(pages.isNotEmpty() && pages.size <= 2000)
    return pages
}

internal suspend fun hashStream(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        check(total <= 64L * 1024 * 1024) { "Page is too large" }
        digest.update(buffer, 0, count)
    }
    check(total > 0) { "Empty page" }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

package eu.kanade.tachiyomi.data.download.remote

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Migrates only files that can be associated unambiguously with a chapter in the database. */
@Inject
class LocalChapterMigration(
    private val mangaRepository: MangaRepository,
    private val chapters: ChapterRepository,
    private val context: Context,
    private val storage: StorageManager,
    private val sources: SourceManager,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val remote: RemoteLibrary,
) {
    private data class Candidate(val manga: Manga, val chapter: Chapter, val source: Source, val file: UniFile)

    data class Counts(val found: Int, val migrated: Int = 0, val skipped: Int = 0, val unmatched: Int = 0)

    suspend fun run(folder: Uri?, progress: suspend (Counts) -> Unit): Counts = withContext(Dispatchers.IO) {
        check(remote.enabled) { "Network library is disabled" }
        val namespace = remote.configurationId()
        val root = checkNotNull(
            if (folder == null) storage.getDownloadsDirectory() else UniFile.fromUri(context, folder),
        ) { "Download folder unavailable" }
        val files = scan(root)
        if (files.isEmpty()) return@withContext Counts(0)

        val filesByTitle = files.groupBy { it.source to it.title }
        val matches = mutableMapOf<String, MutableList<Candidate>>()
        for (manga in mangaRepository.getMangaWithChapters()) {
            currentCoroutineContext().ensureActive()
            val source = sources.getOrStub(manga.source)
            val titles = buildSet {
                for (ascii in listOf(false, true)) {
                    for (sourceAscii in listOf(false, true)) {
                        val sourceName = provider.getSourceDirName(source, sourceAscii).lowercase()
                        add(sourceName to provider.getMangaDirName(manga.title, ascii).lowercase())
                    }
                }
            }
            val mangaFiles = titles.flatMap { filesByTitle[it].orEmpty() }.groupBy { it.name }
            if (mangaFiles.isEmpty()) continue
            for (chapter in chapters.getChapterByMangaId(manga.id)) {
                val names = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                for (name in names.map { it.lowercase() }.distinct()) {
                    for (local in mangaFiles[name].orEmpty()) {
                        matches.getOrPut(local.file.uri.toString(), ::mutableListOf)
                            .add(Candidate(manga, chapter, source, local.file))
                    }
                }
            }
        }
        val candidates = matches.values.mapNotNull { it.singleOrNull() }
        var counts = Counts(found = files.size, unmatched = files.size - candidates.size)
        progress(counts)
        if (candidates.isNotEmpty()) remote.refresh()
        for (item in candidates) {
            currentCoroutineContext().ensureActive()
            check(remote.enabled && remote.configurationId() == namespace) { "Network library configuration changed" }
            try {
                migrateVerifiedChapter(
                    readLocal = { localPageHashes(item.file) },
                    verifyRemote = { hashes ->
                        val stored = remote.ensure(item.manga, item.chapter, item.source as? HttpSource)
                        stored.pageCount == hashes.size && remote.matchesPages(stored, hashes, namespace)
                    },
                    configurationUnchanged = { remote.enabled && remote.configurationId() == namespace },
                    deleteLocal = { item.file.delete() },
                )
                counts = counts.copy(migrated = counts.migrated + 1)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                counts = counts.copy(skipped = counts.skipped + 1)
                progress(counts)
                continue
            }
            if (folder == null) cache.removeChapter(item.chapter, item.manga)
            progress(counts)
        }
        if (folder != null && counts.migrated > 0) cache.invalidateCache()
        counts
    }

    private data class LocalDownload(val source: String, val title: String, val name: String, val file: UniFile)

    private suspend fun scan(root: UniFile): List<LocalDownload> = buildList {
        val children = checkNotNull(root.listFiles()) { "Cannot read download folder" }
        val nested = children.filter {
            it.isDirectory && it.name?.lowercase() in setOf("downloads", "download")
        }
        val sourceDirs = if (nested.isEmpty()) children else nested.flatMap {
            checkNotNull(it.listFiles()) { "Cannot read download folder" }.toList()
        }
        for (source in sourceDirs.filter { it.isDirectory }) {
            val sourceName = source.name?.lowercase() ?: continue
            for (title in source.listFiles().orEmpty().filter { it.isDirectory }) {
                currentCoroutineContext().ensureActive()
                val titleName = title.name?.lowercase() ?: continue
                for (file in title.listFiles().orEmpty()) {
                    val name = file.name?.lowercase() ?: continue
                    if (name.startsWith(".") || name.endsWith(Downloader.TMP_DIR_SUFFIX)) continue
                    if (file.isDirectory || (file.isFile && name.endsWith(".cbz"))) {
                        add(LocalDownload(sourceName, titleName, name, file))
                    }
                }
            }
        }
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
    currentCoroutineContext().ensureActive()
    check(deleteLocal()) { "Could not delete local chapter" }
}

/** Return ordered page digests, refusing to delete directories or archives with unknown content. */
internal suspend fun localPageHashes(file: UniFile): List<String> {
    val pages = if (file.isDirectory) {
        val files = file.listFiles().orEmpty().filterNot {
            it.isFile && (it.name == COMIC_INFO_FILE || it.name == ".nomedia")
        }
        check(files.isNotEmpty() && files.all { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } })
        files.sortedBy { it.name }.map { it.openInputStream().use { stream -> hashStream(stream) } }
    } else {
        check(file.isFile && file.name?.endsWith(".cbz", ignoreCase = true) == true)
        val entries = mutableListOf<Pair<String, String>>()
        file.openInputStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name != COMIC_INFO_FILE && entry.name != ".nomedia") {
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

package eu.kanade.tachiyomi.data.download.remote

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Migrates only files that can be associated unambiguously with a chapter in the database. */
@Inject
class LocalChapterMigration(
    private val mangaRepository: MangaRepository,
    private val chapters: ChapterRepository,
    private val history: HistoryRepository,
    private val context: Context,
    private val storage: StorageManager,
    private val sources: SourceManager,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val remote: RemoteLibrary,
) {
    private data class Candidate(val manga: Manga, val chapter: Chapter, val file: UniFile)

    data class Counts(
        val found: Int,
        val migrated: Int = 0,
        val skipped: Int = 0,
        val unmatched: Int = 0,
        val kept: Int = 0,
    )

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
        val readingOrder = mutableMapOf<Long, Pair<List<Chapter>, Long?>>()
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
            val allChapters = chapters.getChapterByMangaId(manga.id)
                .sortedWith(getChapterSort(manga, sortDescending = false))
            val lastRead = history.getHistoryByMangaId(manga.id)
                .filter { it.readAt != null }
                .maxByOrNull { checkNotNull(it.readAt) }
                ?.chapterId
            readingOrder[manga.id] = allChapters to lastRead
            for (chapter in allChapters) {
                val names = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                for (name in names.map { it.lowercase() }.distinct()) {
                    for (local in mangaFiles[name].orEmpty()) {
                        matches.getOrPut(local.file.uri.toString(), ::mutableListOf)
                            .add(Candidate(manga, chapter, local.file))
                    }
                }
            }
        }
        val candidates = matches.values.mapNotNull { it.singleOrNull() }
        val readingWindowIds = candidates.groupBy { it.manga.id }.flatMapTo(mutableSetOf()) { (mangaId, local) ->
            val (ordered, lastRead) = readingOrder.getValue(mangaId)
            localRetentionWindow(ordered, lastRead, local.mapTo(mutableSetOf()) { it.chapter.id })
        }
        val retained = candidates.filter { it.chapter.id in readingWindowIds }.distinctBy { it.chapter.id }.toSet()
        var counts = Counts(found = files.size, unmatched = files.size - candidates.size, kept = retained.size)
        progress(counts)
        val imports = candidates.filterNot { it in retained }
        if (imports.isEmpty()) return@withContext counts
        remote.checkImportSupport(namespace)

        // Unique WorkManager work uses one staging file. Reuse its path after a process restart.
        val archive = File(context.cacheDir, "local_chapter_import.zip")
        for (item in imports) {
            currentCoroutineContext().ensureActive()
            check(remote.enabled && remote.configurationId() == namespace) { "Network library configuration changed" }
            try {
                val snapshot = remote.prepareLocalImport(item.manga, item.chapter, item.file, archive)
                migrateVerifiedChapter(
                    readLocal = { readLocalChapter(item.file, context.cacheDir).signature },
                    initialSnapshot = snapshot.signature,
                    verifyRemote = { remote.importLocal(item.manga, item.chapter, archive, snapshot, namespace) },
                    configurationUnchanged = { remote.enabled && remote.configurationId() == namespace },
                    deleteLocal = { item.file.delete() },
                )
                counts = counts.copy(migrated = counts.migrated + 1)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                counts = counts.copy(skipped = counts.skipped + 1)
                progress(counts)
                continue
            } finally {
                archive.delete()
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
        val sourceDirs = if (nested.isEmpty()) {
            children.toList()
        } else {
            nested.flatMap {
                checkNotNull(it.listFiles()) { "Cannot read download folder" }.toList()
            }
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

internal fun localRetentionWindow(chapters: List<Chapter>, lastReadId: Long?, downloadedIds: Set<Long>): Set<Long> {
    if (chapters.isEmpty()) return emptySet()
    val position = chapters.indexOfFirst { it.id == lastReadId }.takeIf { it >= 0 }
        ?: chapters.indexOfFirst { !it.read }.takeIf { it >= 0 }
        ?: chapters.lastIndex
    val available = chapters.filter { it.id in downloadedIds }
    val earlierDownloads = chapters.take(position).count { it.id in downloadedIds }
    val start = (earlierDownloads - 1).coerceIn(0, (available.size - 4).coerceAtLeast(0))
    return available.drop(start).take(4).mapTo(mutableSetOf()) { it.id }
}

/** No local deletion is attempted until both copies and the server configuration have been checked. */
internal suspend fun migrateVerifiedChapter(
    readLocal: suspend () -> List<String>,
    verifyRemote: suspend (List<String>) -> Boolean,
    configurationUnchanged: () -> Boolean,
    deleteLocal: () -> Boolean,
    initialSnapshot: List<String>? = null,
) {
    check(configurationUnchanged()) { "Network library configuration changed" }
    val before = initialSnapshot ?: readLocal()
    check(before.isNotEmpty()) { "No pages" }
    check(verifyRemote(before)) { "Server copy could not be verified" }
    check(before == readLocal()) { "Local chapter changed during migration" }
    check(configurationUnchanged()) { "Network library configuration changed" }
    currentCoroutineContext().ensureActive()
    check(deleteLocal()) { "Could not delete local chapter" }
}

@Serializable
internal data class ImportedPage(val entry: String, val sha256: String)

internal data class LocalChapterSnapshot(val pages: List<ImportedPage>, val comicInfoSha256: String?) {
    val signature: List<String>
        get() = pages.map { it.sha256 } + listOfNotNull(comicInfoSha256)
}

/** Read downloaded pages in reader order and optionally write them to the import archive. */
internal suspend fun readLocalChapter(
    file: UniFile,
    cacheDir: File,
    archive: ZipOutputStream? = null,
): LocalChapterSnapshot {
    val pages = mutableListOf<Pair<String, ImportedPage>>()
    var comicInfo: String? = null
    var total = 0L

    suspend fun readEntry(name: String, input: InputStream) {
        currentCoroutineContext().ensureActive()
        if (name == ".nomedia") {
            check(input.read() == -1) { "Unexpected content in .nomedia" }
            return
        }
        val isComicInfo = name == COMIC_INFO_FILE
        check(if (isComicInfo) comicInfo == null else pages.size < 2000)
        val entry = if (isComicInfo) COMIC_INFO_FILE else "page-${pages.size}"
        archive?.putNextEntry(ZipEntry(entry))
        val (hash, size) = digestStream(input, archive, if (isComicInfo) 4L * 1024 * 1024 else MAX_LOCAL_PAGE)
        archive?.closeEntry()
        if (isComicInfo) {
            comicInfo = hash
        } else {
            total += size
            check(total <= 2L * 1024 * 1024 * 1024) { "Chapter exceeds size limit" }
            pages += name to ImportedPage(entry, hash)
        }
    }

    if (file.isDirectory) {
        for (entry in checkNotNull(file.listFiles()).sortedBy { it.name }) {
            val name = checkNotNull(entry.name)
            check(entry.isFile)
            check(name == COMIC_INFO_FILE || name == ".nomedia" || ImageUtil.isImage(name) { entry.openInputStream() })
            entry.openInputStream().use { readEntry(name, it) }
        }
    } else {
        check(file.isFile && file.name?.endsWith(".cbz", ignoreCase = true) == true)
        // Mihon writes stored ZIP entries with data descriptors. Their sizes are in the central directory.
        val copy = File.createTempFile("local_chapter_", ".cbz", cacheDir)
        try {
            file.openInputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            ZipFile(copy).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    check(
                        name == COMIC_INFO_FILE || name == ".nomedia" ||
                            name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS,
                    )
                    CheckedInputStream(zip.getInputStream(entry), CRC32()).use { input ->
                        readEntry(name, input)
                        check(input.checksum.value == entry.crc) { "Invalid CBZ entry checksum" }
                    }
                }
            }
        } finally {
            copy.delete()
        }
        pages.sortWith { a, b -> a.first.compareToCaseInsensitiveNaturalOrder(b.first) }
    }
    check(pages.isNotEmpty() && pages.map { it.first }.distinct().size == pages.size)
    return LocalChapterSnapshot(pages.map { it.second }, comicInfo)
}

internal suspend fun hashStream(input: InputStream): String = digestStream(input, null, MAX_LOCAL_PAGE).first

private suspend fun digestStream(input: InputStream, output: OutputStream?, limit: Long): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        check(total <= limit) { "File is too large" }
        digest.update(buffer, 0, count)
        output?.write(buffer, 0, count)
    }
    check(total > 0) { "Empty file" }
    return digest.digest().joinToString("") { "%02x".format(it) } to total
}

private const val MAX_LOCAL_PAGE = 64L * 1024 * 1024
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")

package eu.kanade.tachiyomi.data.download.remote

import android.content.Context
import android.util.AtomicFile
import androidx.core.util.writeBytes
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.core.metro.AppCoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.encodeUtf8
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Image downloads go directly from the origin to the NAS; only this reader window reaches Android. */
@Inject
@SingleIn(AppScope::class)
class RemoteLibrary(
    private val context: Context,
    private val preferences: DownloadPreferences,
    private val json: Json,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    // A dedicated client avoids the shared HTTP disk cache, source interceptors and credential logging.
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(30, TimeUnit.MINUTES)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()
    private val pageClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()
    private val indexReady = CompletableDeferred<Unit>()
    private val catalogLock = Mutex()
    private val events = Channel<Unit>(Channel.CONFLATED)
    private val historyWake = Channel<Unit>(Channel.CONFLATED)
    val changes = events.receiveAsFlow()
    private val root = File(context.cacheDir, "remote_library")
    private val buffer = RemoteChapterBuffer(root)

    @Volatile
    private var catalog = Catalog()

    val enabled: Boolean
        get() = preferences.remoteLibraryEnabled.get()

    private val origin: String
        get() = preferences.remoteLibraryUrl.get().trim().trimEnd('/')

    private val namespace: String
        get() = (origin + "\n" + preferences.remoteLibraryToken.get().trim()).encodeUtf8().sha256().hex()

    private val indexFile: AtomicFile
        get() = AtomicFile(File(context.noBackupFilesDir, "remote_library_index.json"))

    init {
        scope.launch {
            combine(
                preferences.remoteLibraryEnabled.changes(),
                preferences.remoteLibraryUrl.changes(),
                preferences.remoteLibraryToken.changes(),
            ) { _, _, _ -> Unit }.collect {
                events.trySend(Unit)
                historyWake.trySend(Unit)
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    flushHistory()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // The durable outbox survives outages, old servers and process restarts.
                }
                withTimeoutOrNull(60_000L) { historyWake.receive() }
            }
        }
        scope.launch(Dispatchers.IO) {
            catalogLock.withLock {
                catalog = try {
                    json.decodeFromString<Catalog>(indexFile.openRead().bufferedReader().use { it.readText() })
                } catch (_: IOException) {
                    Catalog()
                } catch (_: IllegalArgumentException) {
                    Catalog()
                }
            }
            indexReady.complete(Unit)
            events.trySend(Unit)
            if (enabled) {
                try {
                    refresh()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Keep the last successful index and the reader's offline buffer.
                }
            }
        }
    }

    private fun entries(): List<RemoteChapter> =
        catalog.takeIf { enabled && it.namespace == namespace }?.chapters.orEmpty()

    /** A session is queued before networking; an acknowledgement lost in transit cannot double its duration. */
    suspend fun recordHistory(
        manga: Manga,
        chapter: Chapter,
        lastRead: Long,
        readDuration: Long,
    ) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val event = RemoteHistoryEvent(
            eventId = UUID.randomUUID().toString(),
            sourceId = manga.source.toString(),
            mangaUrl = manga.url,
            mangaTitle = manga.title,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
            lastRead = lastRead,
            readDuration = readDuration.coerceAtLeast(0),
            lastPageRead = chapter.lastPageRead,
            read = chapter.read,
        )
        val directory = File(context.noBackupFilesDir, "remote_history/$namespace")
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create history outbox" }
        AtomicFile(File(directory, "${event.eventId}.json"))
            .writeBytes(json.encodeToString(event).toByteArray())
        historyWake.trySend(Unit)
    }

    private suspend fun flushHistory() {
        if (!enabled) return
        val serverOrigin = origin
        val serverToken = preferences.remoteLibraryToken.get().trim()
        val expectedNamespace = (serverOrigin + "\n" + serverToken).encodeUtf8().sha256().hex()
        val directory = File(context.noBackupFilesDir, "remote_history/$expectedNamespace")
        val files = directory.listFiles()?.filter { it.extension == "json" }.orEmpty()
        for (file in files) {
            if (!enabled || namespace != expectedNamespace) return
            // Build the target before suspension. Never send an old server's outbox to a new server/token.
            val target = request("history", serverOrigin, serverToken)
            val event = json.decodeFromString<RemoteHistoryEvent>(
                AtomicFile(file).openRead().bufferedReader().use { it.readText() },
            )
            val body = json.encodeToString(event).toRequestBody("application/json".toMediaType())
            val ack = pageClient.newCall(target.post(body).build()).awaitSuccess().use {
                json.decodeFromString<RemoteHistoryAck>(it.body.string())
            }
            check(ack.eventId == event.eventId) { "Invalid history acknowledgement" }
            AtomicFile(file).delete()
        }
    }

    fun key(manga: Manga, chapter: Chapter): String =
        "${manga.source}\n${manga.url}\n${chapter.url}".encodeUtf8().sha256().hex()

    /** Manual reader versions have their own server-side archives, even if the primary source was selected. */
    private fun variantMangaUrl(manga: Manga): String = "${manga.url}#yomira-reader-variant:${manga.source}"

    fun archiveKey(manga: Manga, chapter: Chapter, source: HttpSource?, originChapter: Chapter = chapter): String =
        "${source?.id ?: manga.source}\n${variantMangaUrl(manga)}\n${originChapter.url}"
            .encodeUtf8().sha256().hex()

    fun contains(sourceId: Long, chapterUrl: String): Boolean =
        entries().any { it.sourceId == sourceId.toString() && it.chapterUrl == chapterUrl }

    fun downloadedIds(chapters: List<Chapter>, manga: Manga): Set<Long> {
        val keys = entries().mapTo(HashSet()) { it.key }
        return chapters.filter { key(manga, it) in keys }.mapTo(HashSet()) { it.id }
    }

    fun count(manga: Manga): Int = entries().count {
        it.sourceId == manga.source.toString() && it.mangaUrl == manga.url
    }

    fun count(): Int = entries().size

    private fun request(
        path: String,
        serverOrigin: String = origin,
        serverToken: String = preferences.remoteLibraryToken.get().trim(),
    ): Request.Builder {
        val url = serverOrigin.toHttpUrl()
        require(
            url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
                url.query == null && url.fragment == null,
        ) {
            "Enter a server origin such as https://mihon.example.com (without a path or credentials)"
        }
        require(serverToken.length >= 32) {
            "Server token must contain at least 32 characters"
        }
        return Request.Builder()
            .url(url.newBuilder().encodedPath("/v1/$path").build())
            .header("Authorization", "Bearer $serverToken")
            .header("Cache-Control", "no-store")
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        catalogLock.withLock {
            // Serialize the network snapshot with local commits; a slow refresh must not erase a newer download.
            val expectedNamespace = namespace
            val chapters = pageClient.newCall(request("chapters").build()).awaitSuccess().use {
                json.decodeFromString<List<RemoteChapter>>(it.body.string())
            }
            require(chapters.all { it.key.matches(Regex("[a-f0-9]{64}")) && it.pageCount in 1..2000 }) {
                "Invalid server catalog"
            }
            if (namespace != expectedNamespace) return@withLock
            saveCatalog(Catalog(expectedNamespace, chapters))
        }
    }

    private fun saveCatalog(value: Catalog) {
        indexFile.writeBytes(json.encodeToString(value).toByteArray())
        catalog = value
        events.trySend(Unit)
    }

    /** Idempotent: already archived chapters do not need working source URLs or an installed extension. */
    suspend fun ensure(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource?,
        originChapter: Chapter = chapter,
        mirrorArchive: Boolean = false,
    ): RemoteChapter = withContext(Dispatchers.IO) {
        indexReady.await()
        val key = if (mirrorArchive) archiveKey(manga, chapter, source, originChapter) else key(manga, chapter)
        entries().firstOrNull { it.key == key }?.let { return@withContext it }
        // The catalog can be stale, e.g. a second device has downloaded this chapter.
        refresh()
        entries().firstOrNull { it.key == key }?.let { return@withContext it }
        checkNotNull(source) { "Install the source extension to download this chapter to the server" }
        val pages = source.getPageList(originChapter.toSChapter())
        require(pages.size in 1..2000) { "Remote chapters must contain 1 to 2000 pages" }
        val manifest = pages.map { page ->
            if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
            val image = source.getRemoteImageRequest(page)
            require(image.method == "GET" && image.body == null) { "Remote library supports GET image requests only" }
            val headers = image.headers.toMap().toMutableMap()
            val cookies = source.client.cookieJar.loadForRequest(image.url)
            if (cookies.isNotEmpty() && image.header("Cookie") == null) {
                headers["Cookie"] = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            }
            RemoteImage(image.url.toString(), headers)
        }
        val expectedNamespace = namespace
        val metadata = RemoteChapter(
            key = key,
            sourceId = (if (mirrorArchive) source?.id ?: manga.source else manga.source).toString(),
            mangaUrl = if (mirrorArchive) variantMangaUrl(manga) else manga.url,
            mangaTitle = manga.title,
            chapterUrl = if (mirrorArchive) originChapter.url else chapter.url,
            chapterName = if (mirrorArchive) originChapter.name else chapter.name,
            pageCount = pages.size,
        )
        val body = json.encodeToString(RemoteManifest(metadata, manifest))
            .toRequestBody("application/json".toMediaType())
        val stored = client.newCall(request("chapters/$key").post(body).build()).awaitSuccess().use {
            json.decodeFromString<RemoteChapter>(it.body.string())
        }
        require(stored.key == key && stored.pageCount == pages.size) { "Invalid server download response" }
        catalogLock.withLock {
            check(namespace == expectedNamespace) { "Remote server configuration changed" }
            val existing = catalog.takeIf { it.namespace == expectedNamespace }?.chapters.orEmpty()
            saveCatalog(Catalog(expectedNamespace, existing.filterNot { it.key == key } + stored))
        }
        stored
    }

    /** A migration must never verify against a different server after settings change. */
    internal fun configurationId(): String = namespace

    internal suspend fun checkImportSupport(expectedNamespace: String) {
        val target = importRequest("imports", expectedNamespace).build()
        pageClient.newCall(target).await().use { response ->
            if (response.code == 404 || response.code == 501) throw LocalImportUnsupported()
            check(response.isSuccessful) { "Cannot connect to the import service" }
        }
    }

    private fun importRequest(path: String, expectedNamespace: String): Request.Builder {
        val serverOrigin = origin
        val serverToken = preferences.remoteLibraryToken.get().trim()
        check(enabled && (serverOrigin + "\n" + serverToken).encodeUtf8().sha256().hex() == expectedNamespace) {
            "Network library configuration changed"
        }
        return request(path, serverOrigin, serverToken)
    }

    internal suspend fun prepareLocalImport(
        manga: Manga,
        chapter: Chapter,
        local: UniFile,
        file: File,
    ): LocalChapterSnapshot {
        return ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            val snapshot = readLocalChapter(local, zip)
            val metadata = RemoteChapter(
                key = key(manga, chapter),
                sourceId = manga.source.toString(),
                mangaUrl = manga.url,
                mangaTitle = manga.title,
                chapterUrl = chapter.url,
                chapterName = chapter.name,
                pageCount = snapshot.pages.size,
            )
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = LocalImportManifest(metadata, snapshot.pages, snapshot.comicInfoSha256)
            zip.write(json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            snapshot
        }
    }

    internal suspend fun importLocal(
        manga: Manga,
        chapter: Chapter,
        file: File,
        snapshot: LocalChapterSnapshot,
        expectedNamespace: String,
    ): Boolean {
        val key = key(manga, chapter)
        val request = importRequest("chapters/$key/import", expectedNamespace)
            .put(file.asRequestBody("application/zip".toMediaType()))
            .build()
        val receipt = client.newCall(request).awaitSuccess().use {
            json.decodeFromString<LocalImportReceipt>(it.body.string())
        }
        val hashes = snapshot.pages.map { it.sha256 }
        val variant = (hashes.joinToString("") + snapshot.comicInfoSha256.orEmpty()).encodeUtf8().sha256().hex()
        val variantUrl = "${manga.url}#yomira-local-import:$variant"
        val variantKey = "${manga.source}\n$variantUrl\n${chapter.url}".encodeUtf8().sha256().hex()
        val stored = receipt.chapter
        val validKey = (stored.key == key && stored.mangaUrl == manga.url) ||
            (stored.key == variantKey && stored.mangaUrl == variantUrl)
        if (!validKey || stored.sourceId != manga.source.toString() || stored.chapterUrl != chapter.url ||
            stored.pageCount != hashes.size || receipt.pageHashes != hashes ||
            receipt.comicInfoSha256 != snapshot.comicInfoSha256
        ) {
            return false
        }
        catalogLock.withLock {
            check(enabled && namespace == expectedNamespace) { "Network library configuration changed" }
            val existing = catalog.takeIf { it.namespace == expectedNamespace }?.chapters.orEmpty()
            saveCatalog(Catalog(expectedNamespace, existing.filterNot { it.key == stored.key } + stored))
        }
        return true
    }

    suspend fun delete(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        val keys = chapters.map { key(manga, it) }.toSet()
        val expectedNamespace = namespace
        keys.forEach { key ->
            client.newCall(request("chapters/$key").delete().build()).awaitSuccess().close()
        }
        catalogLock.withLock {
            if (namespace == expectedNamespace) {
                saveCatalog(Catalog(expectedNamespace, entries().filterNot { it.key in keys }))
            }
        }
        buffer.remove(keys.map { "$expectedNamespace/$it" }.toSet())
    }

    suspend fun deleteManga(manga: Manga) {
        val expectedNamespace = namespace
        val keys = entries()
            .filter {
                (it.sourceId == manga.source.toString() && it.mangaUrl == manga.url) ||
                    it.mangaUrl == variantMangaUrl(manga)
            }
            .map { it.key }.toSet()
        withContext(Dispatchers.IO) {
            keys.forEach { client.newCall(request("chapters/$it").delete().build()).awaitSuccess().close() }
            catalogLock.withLock {
                if (namespace == expectedNamespace) {
                    saveCatalog(Catalog(expectedNamespace, entries().filterNot { it.key in keys }))
                }
            }
            buffer.remove(keys.map { "$expectedNamespace/$it" }.toSet())
        }
    }

    /** Called only when the active chapter changes, never by a speculative page loader. */
    suspend fun setWindow(
        manga: Manga,
        chapters: List<Chapter>,
        chapterKeyOverrides: Map<Long, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        buffer.setWindow(chapters.map { "$namespace/${chapterKeyOverrides[it.id] ?: key(manga, it)}" }.toSet())
    }

    /** Files are written atomically and only inside the latest window, including after cancellation. */
    suspend fun pageFile(key: String, index: Int): File = withContext(Dispatchers.IO) {
        buffer.page("$namespace/$key", index) { pending ->
            pageClient.newCall(request("chapters/$key/pages/$index").build()).awaitSuccess().use { response ->
                response.body.byteStream().use { input ->
                    pending.outputStream().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            check(total <= 64L * 1024 * 1024) { "Remote image exceeds 64 MiB" }
                            output.write(bytes, 0, count)
                        }
                        check(total > 0) { "Remote image is empty" }
                    }
                }
            }
        }
    }
}

@Serializable
private data class Catalog(val namespace: String = "", val chapters: List<RemoteChapter> = emptyList())

@Serializable
private data class RemoteImage(val url: String, val headers: Map<String, String>)

@Serializable
private data class RemoteManifest(val metadata: RemoteChapter, val pages: List<RemoteImage>)

@Serializable
data class RemoteChapter(
    val key: String,
    val sourceId: String,
    val mangaUrl: String,
    val mangaTitle: String,
    val chapterUrl: String,
    val chapterName: String,
    val pageCount: Int,
)

@Serializable
private data class RemoteHistoryAck(val eventId: String)

@Serializable
private data class RemoteHistoryEvent(
    val eventId: String,
    val sourceId: String,
    val mangaUrl: String,
    val mangaTitle: String,
    val chapterUrl: String,
    val chapterName: String,
    val lastRead: Long,
    val readDuration: Long,
    val lastPageRead: Int,
    val read: Boolean,
)

internal class LocalImportUnsupported : IOException()

@Serializable
private data class LocalImportManifest(
    val metadata: RemoteChapter,
    val pages: List<ImportedPage>,
    val comicInfoSha256: String?,
)

@Serializable
private data class LocalImportReceipt(
    val chapter: RemoteChapter,
    val pageHashes: List<String>,
    val comicInfoSha256: String?,
)

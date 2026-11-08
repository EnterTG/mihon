package eu.kanade.tachiyomi.data.download.remote

import android.content.Context
import android.util.AtomicFile
import androidx.core.util.writeBytes
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.core.metro.AppCoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.encodeUtf8
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            ) { _, _, _ -> Unit }.collect { events.trySend(Unit) }
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

    fun key(manga: Manga, chapter: Chapter): String =
        "${manga.source}\n${manga.url}\n${chapter.url}".encodeUtf8().sha256().hex()

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

    private fun request(path: String): Request.Builder {
        val url = origin.toHttpUrl()
        require(
            url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
                url.query == null && url.fragment == null,
        ) {
            "Enter a server origin such as https://mihon.example.com (without a path or credentials)"
        }
        require(preferences.remoteLibraryToken.get().trim().length >= 32) {
            "Server token must contain at least 32 characters"
        }
        return Request.Builder()
            .url(url.newBuilder().encodedPath("/v1/$path").build())
            .header("Authorization", "Bearer ${preferences.remoteLibraryToken.get().trim()}")
            .header("Cache-Control", "no-store")
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val expectedNamespace = namespace
        val chapters = client.newCall(request("chapters").build()).awaitSuccess().use {
            json.decodeFromString<List<RemoteChapter>>(it.body.string())
        }
        require(chapters.all { it.key.matches(Regex("[a-f0-9]{64}")) && it.pageCount in 1..2000 }) {
            "Invalid server catalog"
        }
        catalogLock.withLock {
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
    ): RemoteChapter = withContext(Dispatchers.IO) {
        indexReady.await()
        val key = key(manga, chapter)
        entries().firstOrNull { it.key == key }?.let { return@withContext it }
        // The catalog can be stale, e.g. a second device has downloaded this chapter.
        refresh()
        entries().firstOrNull { it.key == key }?.let { return@withContext it }
        checkNotNull(source) { "Install the source extension to download this chapter to the server" }
        val pages = source.getPageList(chapter.toSChapter())
        require(pages.isNotEmpty()) { "The source returned an empty chapter" }
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
            sourceId = manga.source.toString(),
            mangaUrl = manga.url,
            mangaTitle = manga.title,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
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
            .filter { it.sourceId == manga.source.toString() && it.mangaUrl == manga.url }
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
    suspend fun setWindow(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        buffer.setWindow(chapters.map { "$namespace/${key(manga, it)}" }.toSet())
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

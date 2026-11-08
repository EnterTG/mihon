package eu.kanade.tachiyomi.data.download.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Serializes eviction and writes so an obsolete prefetch cannot recreate an evicted chapter. */
internal class RemoteChapterBuffer(private val root: File) {
    private val lock = Mutex()
    private var window = emptySet<String>()

    suspend fun setWindow(chapters: Set<String>) = lock.withLock {
        require(chapters.size <= 4)
        require(chapters.all { it.matches(Regex("[a-f0-9]{64}/[a-f0-9]{64}")) })
        window = chapters
        root.listFiles().orEmpty().forEach { server ->
            server.listFiles().orEmpty().forEach { chapter ->
                if ("${server.name}/${chapter.name}" !in window) chapter.deleteRecursively()
            }
            if (server.listFiles().isNullOrEmpty()) server.delete()
        }
    }

    suspend fun remove(chapters: Set<String>) = lock.withLock {
        chapters.intersect(window).forEach { File(root, it).deleteRecursively() }
    }

    suspend fun page(chapter: String, index: Int, write: suspend (File) -> Unit): File = lock.withLock {
        check(chapter in window) { "Chapter is outside the reading buffer" }
        require(index in 0 until 2000)
        val dir = File(root, chapter)
        val target = File(dir, index.toString())
        if (target.isFile) return@withLock target
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create the reading buffer" }
        val pending = File(dir, "$index.tmp")
        try {
            write(pending)
            check(pending.renameTo(target)) { "Cannot commit the buffered image" }
            target
        } finally {
            pending.delete()
        }
    }
}

internal fun <T> remoteReadingWindow(chapters: List<T>, position: Int): List<T> {
    require(position in chapters.indices)
    return chapters.subList((position - 1).coerceAtLeast(0), (position + 3).coerceAtMost(chapters.size))
}

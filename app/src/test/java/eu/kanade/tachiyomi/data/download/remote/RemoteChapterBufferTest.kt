package eu.kanade.tachiyomi.data.download.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RemoteChapterBufferTest {
    @TempDir
    lateinit var root: File

    private fun key(index: Int) = "${"a".repeat(64)}/${index.toString(16).padStart(64, '0')}"

    @Test
    fun `window follows reading order at boundaries and in the middle`() {
        val chapters = (0..9).toList()
        assertEquals(listOf(0, 1, 2), remoteReadingWindow(chapters, 0))
        assertEquals(listOf(3, 4, 5, 6), remoteReadingWindow(chapters, 4))
        assertEquals(listOf(8, 9), remoteReadingWindow(chapters, 9))
        assertEquals(listOf(0), remoteReadingWindow(listOf(0), 0))
    }

    @Test
    fun `moving window evicts old images and rejects stale prefetch`() = runBlocking<Unit> {
        val buffer = RemoteChapterBuffer(root)
        buffer.setWindow((0..3).map(::key).toSet())
        repeat(4) { buffer.page(key(it), 0) { file -> file.writeText("image") } }
        buffer.setWindow((1..4).map(::key).toSet())
        assertFalse(File(root, key(0)).exists())
        assertThrows<IllegalStateException> {
            runBlocking { buffer.page(key(0), 1) { it.writeText("stale") } }
        }
        buffer.page(key(4), 0) { it.writeText("next") }
        assertEquals(4, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `failed or cancelled writes never publish a partial image`() = runBlocking<Unit> {
        val buffer = RemoteChapterBuffer(root)
        buffer.setWindow(setOf(key(0)))
        assertThrows<CancellationException> {
            runBlocking {
                buffer.page(key(0), 0) {
                    it.writeText("partial")
                    throw CancellationException()
                }
            }
        }
        assertEquals(0, root.walkTopDown().count { it.isFile })
        val file = buffer.page(key(0), 0) { it.writeText("complete") }
        assertEquals("complete", file.readText())
        buffer.page(key(0), 0) { error("Cached image must be reused offline") }
    }

    @Test
    fun `buffer rejects oversized windows and invalid paths`() = runBlocking<Unit> {
        val buffer = RemoteChapterBuffer(root)
        assertThrows<IllegalArgumentException> { runBlocking { buffer.setWindow((0..4).map(::key).toSet()) } }
        assertThrows<IllegalArgumentException> { runBlocking { buffer.setWindow(setOf("../outside")) } }
    }
}

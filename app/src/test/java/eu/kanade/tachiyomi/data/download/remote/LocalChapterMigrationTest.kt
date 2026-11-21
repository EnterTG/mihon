package eu.kanade.tachiyomi.data.download.remote

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalChapterMigrationTest {
    @Test
    fun `only deletes after a matching server and unchanged local copy`() = runBlocking {
        var deleted = false
        migrateVerifiedChapter(
            readLocal = { listOf("page1", "page2") },
            verifyRemote = { it == listOf("page1", "page2") },
            configurationUnchanged = { true },
            deleteLocal = {
                deleted = true
                true
            },
        )
        assertTrue(deleted)
    }

    @Test
    fun `never deletes if remote differs or verification fails`() {
        for (remoteFailure in listOf(false, true)) {
            var deleted = false
            assertThrows<IllegalStateException> {
                runBlocking {
                    migrateVerifiedChapter(
                        readLocal = { listOf("local") },
                        verifyRemote = { if (remoteFailure) error("offline") else false },
                        configurationUnchanged = { true },
                        deleteLocal = {
                            deleted = true
                            true
                        },
                    )
                }
            }
            assertFalse(deleted)
        }
    }

    @Test
    fun `never deletes if local pages or server settings change during verification`() {
        for (changeConfiguration in listOf(false, true)) {
            var reads = 0
            var deleted = false
            assertThrows<IllegalStateException> {
                runBlocking {
                    migrateVerifiedChapter(
                        readLocal = { listOf(if (++reads == 1 || changeConfiguration) "original" else "changed") },
                        verifyRemote = { true },
                        configurationUnchanged = { !changeConfiguration || reads == 0 },
                        deleteLocal = {
                            deleted = true
                            true
                        },
                    )
                }
            }
            assertFalse(deleted)
        }
    }

    @Test
    fun `CBZ metadata does not stop page verification`() = runBlocking {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in listOf("10.jpg" to "last", "ComicInfo.xml" to "<ComicInfo/>", "2.jpg" to "first")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val file = mockk<UniFile>()
        every { file.isDirectory } returns false
        every { file.isFile } returns true
        every { file.name } returns "chapter.cbz"
        every { file.openInputStream() } answers { bytes.toByteArray().inputStream() }

        val expected = listOf("first", "last").map { hashStream(it.byteInputStream()) }
        val snapshot = readLocalChapter(file)
        assertEquals(expected, snapshot.pages.map { it.sha256 })
        assertEquals(hashStream("<ComicInfo/>".byteInputStream()), snapshot.comicInfoSha256)
    }

    @Test
    fun `local retention keeps the reading window for each title`() {
        val chapters = (1L..10L).map { Chapter.create().copy(id = it, read = it < 5) }
        assertEquals(setOf(4L, 5L, 6L, 7L), localRetentionWindow(chapters, null))
        assertEquals(setOf(6L, 7L, 8L, 9L), localRetentionWindow(chapters, 7L))
        assertEquals(setOf(9L, 10L), localRetentionWindow(chapters, 10L))
    }

    @Test
    fun `hashing checks the entire non-empty page`() = runBlocking {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hashStream("abc".byteInputStream()),
        )
        assertThrows<IllegalStateException> { runBlocking { hashStream(ByteArray(0).inputStream()) } }
    }
}

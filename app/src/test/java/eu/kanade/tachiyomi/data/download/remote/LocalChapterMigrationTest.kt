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
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.util.Base64

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
    fun `Mihon stored CBZ keeps page order and metadata`(@TempDir cache: File) = runBlocking {
        // ZIP_STORED entries written to a non-seekable stream, as in Mihon downloads.
        val bytes = Base64.getMimeDecoder().decode(
            """
            UEsDBBQACAAAAHOrdV0AAAAAAAAAAAAAAAAGAAAAMTAuanBnbGFzdFBLBwigqdtKBAAAAAQAAABQSwMEFAAIAAAAc6t1XQAA
            AAAAAAAAAAAAAA0AAABDb21pY0luZm8ueG1sPENvbWljSW5mby8+UEsHCG6FiF0MAAAADAAAAFBLAwQUAAgAAABzq3VdAAAA
            AAAAAAAAAAAABQAAADIuanBnZmlyc3RQSwcIV+5xkgUAAAAFAAAAUEsBAhQAFAAIAAAAc6t1XaCp20oEAAAABAAAAAYAAAAA
            AAAAAAAAAIABAAAAADEwLmpwZ1BLAQIUABQACAAAAHOrdV1uhYhdDAAAAAwAAAANAAAAAAAAAAAAAACAATgAAABDb21pY0lu
            Zm8ueG1sUEsBAhQAFAAIAAAAc6t1XVfucZIFAAAABQAAAAUAAAAAAAAAAAAAAIABfwAAADIuanBnUEsFBgAAAAADAAMAogAA
            ALcAAAAAAA==
            """.trimIndent(),
        )
        val file = mockk<UniFile>()
        every { file.isDirectory } returns false
        every { file.isFile } returns true
        every { file.name } returns "chapter.cbz"
        every { file.openInputStream() } answers { bytes.inputStream() }

        val expected = listOf("first", "last").map { hashStream(it.byteInputStream()) }
        val snapshot = readLocalChapter(file, cache)
        assertEquals(expected, snapshot.pages.map { it.sha256 })
        assertEquals(hashStream("<ComicInfo/>".byteInputStream()), snapshot.comicInfoSha256)
    }

    @Test
    fun `local retention keeps the reading window for each title`() {
        val chapters = (1L..10L).map { Chapter.create().copy(id = it, read = it < 5) }
        val all = (1L..10L).toSet()
        assertEquals(setOf(4L, 5L, 6L, 7L), localRetentionWindow(chapters, null, all))
        assertEquals(setOf(6L, 7L, 8L, 9L), localRetentionWindow(chapters, 7L, all))
        assertEquals(setOf(7L, 8L, 9L, 10L), localRetentionWindow(chapters, 10L, all))
        assertEquals(setOf(6L, 7L, 8L, 9L), localRetentionWindow(chapters, 1L, (6L..10L).toSet()))
        assertEquals(setOf(9L, 10L), localRetentionWindow(chapters, null, setOf(9L, 10L)))
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

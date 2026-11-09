package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.seconds

class DownloadQueueTest {

    private fun download(id: Long) = Download(
        source = mockk(),
        manga = Manga.create(),
        chapter = Chapter.create().copy(id = id),
    ).apply { status = Download.State.QUEUE }

    @Test
    fun `a failed chapter retries while another chapter stays queued`() = runTest {
        val failed = download(1)
        val next = download(2)
        val queue = MutableStateFlow(listOf(failed, next))
        val retries = mutableListOf<Download>()
        backgroundScope.launch {
            queue.retryFailedDownloads {
                retries.add(it)
                it.status = Download.State.QUEUE
            }
        }
        runCurrent()
        failed.status = Download.State.ERROR
        runCurrent()

        advanceTimeBy(29.seconds)
        runCurrent()
        retries shouldBe emptyList()
        next.status shouldBe Download.State.QUEUE
        advanceTimeBy(1.seconds)
        runCurrent()
        retries shouldBe listOf(failed)
        failed.status shouldBe Download.State.QUEUE
        queue.withDownloadStates().first().map { it.second } shouldBe
            listOf(Download.State.QUEUE, Download.State.QUEUE)
    }

    @Test
    fun `permanent failures keep retrying with bounded backoff`() = runTest {
        val failed = download(1).apply { status = Download.State.ERROR }
        val queue = MutableStateFlow(listOf(failed))
        var retries = 0
        backgroundScope.launch {
            queue.retryFailedDownloads {
                retries++
                it.status = Download.State.QUEUE
            }
        }
        runCurrent()
        listOf(30, 60, 120, 240, 300, 300, 300).forEachIndexed { attempt, seconds ->
            advanceTimeBy((seconds - 1).seconds)
            runCurrent()
            retries shouldBe attempt
            advanceTimeBy(1.seconds)
            runCurrent()
            retries shouldBe attempt + 1
            failed.status = Download.State.ERROR
            runCurrent()
        }
    }

    @Test
    fun `immediate repeated failure does not lose the retry`() = runTest {
        val failed = download(1).apply { status = Download.State.ERROR }
        val queue = MutableStateFlow(listOf(failed))
        var retries = 0
        backgroundScope.launch {
            queue.retryFailedDownloads {
                retries++
                // StateFlow may conflate these transitions, e.g. a source fails before its first suspension.
                it.status = Download.State.QUEUE
                it.status = Download.State.ERROR
            }
        }
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        retries shouldBe 1
        advanceTimeBy(60.seconds)
        runCurrent()
        retries shouldBe 2
    }

    @Test
    fun `new failures do not postpone another chapter retry`() = runTest {
        val first = download(1).apply { status = Download.State.ERROR }
        val second = download(2)
        val queue = MutableStateFlow(listOf(first, second))
        val retries = mutableListOf<Download>()
        backgroundScope.launch {
            queue.retryFailedDownloads {
                retries.add(it)
                it.status = Download.State.QUEUE
            }
        }
        runCurrent()
        advanceTimeBy(20.seconds)
        second.status = Download.State.ERROR
        runCurrent()
        advanceTimeBy(10.seconds)
        runCurrent()
        retries shouldBe listOf(first)
        advanceTimeBy(20.seconds)
        runCurrent()
        retries shouldBe listOf(first, second)
    }

    @Test
    fun `removing a chapter and pausing cancel pending retries`() = runTest {
        val removed = download(1).apply { status = Download.State.ERROR }
        val paused = download(2).apply { status = Download.State.ERROR }
        val queue = MutableStateFlow(listOf(removed, paused))
        val retries = mutableListOf<Download>()
        val scheduler = backgroundScope.launch { queue.retryFailedDownloads { retries.add(it) } }
        runCurrent()
        advanceTimeBy(10.seconds)
        queue.value = listOf(paused)
        runCurrent()
        advanceTimeBy(10.seconds)
        scheduler.cancel()
        runCurrent()
        advanceTimeBy(300.seconds)
        runCurrent()
        retries shouldBe emptyList()
    }

    @Test
    fun `completion removes retry history and queue replacement cancels old work`() = runTest {
        val old = download(1).apply { status = Download.State.ERROR }
        val replacement = download(2).apply { status = Download.State.ERROR }
        val queue = MutableStateFlow(listOf(old))
        val retries = mutableListOf<Download>()
        backgroundScope.launch {
            queue.retryFailedDownloads {
                retries.add(it)
                it.status = Download.State.QUEUE
            }
        }
        runCurrent()
        advanceTimeBy(10.seconds)
        old.status = Download.State.DOWNLOADED
        queue.value = listOf(replacement)
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        retries shouldBe listOf(replacement)
    }
}

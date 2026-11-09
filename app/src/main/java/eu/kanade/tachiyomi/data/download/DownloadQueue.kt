package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Observe status changes too: changing a chapter's status does not change the queue's list. */
internal fun Flow<List<Download>>.withDownloadStates(): Flow<List<Pair<Download, Download.State>>> =
    flatMapLatest { downloads ->
        if (downloads.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(downloads.map(Download::statusFlow)) { states ->
                downloads.mapIndexed { index, download -> download to states[index] }
            }
        }
    }

/** Each failed chapter waits independently, leaving source slots free for the rest of the queue. */
internal suspend fun StateFlow<List<Download>>.retryFailedDownloads(retry: suspend (Download) -> Unit): Unit =
    coroutineScope {
        val jobs = mutableMapOf<Download, Job>()
        collect { downloads ->
            val queued = downloads.toSet()
            jobs.keys.filter { it !in queued }.forEach { jobs.remove(it)?.cancel() }
            downloads.filter { it !in jobs }.forEach { download ->
                jobs[download] = launch {
                    var attempt = 0
                    while (true) {
                        download.statusFlow.first { it == Download.State.ERROR }
                        // 30s, 1m, 2m, 4m, then 5m between attempts, without giving up.
                        delay((30L shl attempt).coerceAtMost(300).seconds)
                        attempt = (attempt + 1).coerceAtMost(4)
                        retry(download)
                    }
                }
            }
        }
    }

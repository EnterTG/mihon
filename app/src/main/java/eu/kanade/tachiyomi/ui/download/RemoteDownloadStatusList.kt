package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.data.download.model.Download

/**
 * In NAS mode this is a status monitor, not a local download manager.
 * Automatic retries and alternate-source fallback continue in Downloader.
 */
@Composable
internal fun RemoteDownloadStatusList(
    sections: List<DownloadQueueSection>,
    contentPadding: PaddingValues,
) {
    LazyColumn(contentPadding = contentPadding) {
        sections.forEach { section ->
            item(key = "source-${section.id}") {
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            items(section.downloads, key = { "chapter-${it.chapter.id}" }) { download ->
                val status by download.statusFlow.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(download.manga.title, style = MaterialTheme.typography.bodyLarge)
                        Text(download.chapter.name, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (status == Download.State.DOWNLOADING) {
                        CircularProgressIndicator()
                    }
                    Text(
                        text = when (status) {
                            Download.State.NOT_DOWNLOADED, Download.State.QUEUE -> "W kolejce"
                            Download.State.DOWNLOADING -> "Pobieranie"
                            Download.State.DOWNLOADED -> "Pobrano"
                            Download.State.ERROR -> "Ponowienie"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

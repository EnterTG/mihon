package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderSourceSelectDialog(
    dialog: ReaderViewModel.Dialog.SourceSelect,
    onChoose: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Źródło tego rozdziału") },
        text = {
            Column {
                Text("Wybierz wersję rozdziału. Postęp czytania pozostanie przy tej samej książce.")
                dialog.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(option.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.selected, onClick = { onChoose(option.id) })
                        Column {
                            Text(option.sourceName)
                            Text(option.chapterName)
                        }
                    }
                }
                if (dialog.options.size == 1) {
                    Text("Brak dopasowanego rozdziału w innych połączonych źródłach.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zamknij") }
        },
    )
}

@Composable
internal fun ReaderSourceErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nie można zmienić źródła") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zamknij") }
        },
    )
}

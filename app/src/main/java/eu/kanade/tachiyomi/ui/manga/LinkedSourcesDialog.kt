package eu.kanade.tachiyomi.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment

@Composable
internal fun LinkedSourcesDialog(
    candidates: List<MangaViewModel.LinkOption>,
    error: String?,
    onToggle: (MangaViewModel.LinkOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Powiązane źródła") },
        text = {
            Column {
                Text("Połącz wydania tej samej książki z różnych rozszerzeń. Najpierw dodaj je do biblioteki.")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Szukaj w bibliotece") },
                    singleLine = true,
                )
                if (error != null) Text(error)
                val filtered = candidates.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.sourceName.contains(query, ignoreCase = true)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(option) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = option.linked, onCheckedChange = { onToggle(option) })
                            Column {
                                Text(option.title)
                                Text(option.sourceName)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Gotowe") }
        },
    )
}

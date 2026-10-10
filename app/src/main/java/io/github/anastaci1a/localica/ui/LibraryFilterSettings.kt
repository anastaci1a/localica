package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.MediaKind

@Composable
internal fun LibraryFilterSettings(value: LibraryFilters, onChange: (LibraryFilters) -> Unit) {
    SettingsHeading("Visible filters")
    Text("Choose which filters appear in Library and their order. Global search keeps all item types available.", style = MaterialTheme.typography.bodyMedium)
    value.visible.forEachIndexed { index, filter ->
        Surface(shape = Corners.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(filter.label, style = MaterialTheme.typography.titleMedium)
                FlowRow {
                    TextButton(enabled = index > 0, onClick = {
                        onChange(value.copy(visible = value.visible.toMutableList().apply { add(index - 1, removeAt(index)) }))
                    }) { Text("Move up") }
                    TextButton(enabled = index < value.visible.lastIndex, onClick = {
                        onChange(value.copy(visible = value.visible.toMutableList().apply { add(index + 1, removeAt(index)) }))
                    }) { Text("Move down") }
                    TextButton(enabled = value.visible.size > 1, onClick = { onChange(value.copy(visible = value.visible - filter)) }) { Text("Remove") }
                }
            }
        }
    }
    var adding by remember { mutableStateOf(false) }
    SettingsLink("Add library filter", enabled = value.visible.size < LibraryFilter.entries.size) { adding = true }
    if (adding) ChoiceDialog("Add library filter", LibraryFilter.entries.filterNot { it in value.visible }, LibraryFilter.All,
        { it.label }, { adding = false }) { onChange(value.copy(visible = value.visible + it)) }
    SettingsHeading("Collections in All")
    Text("Independent of the visible filters. All contains collections only.", style = MaterialTheme.typography.bodyMedium)
    MediaKind.entries.filterNot { it == MediaKind.Song || it == MediaKind.Queue }.forEach { kind ->
        BooleanPreference(kind.label, kind in value.allCollections) { included ->
            onChange(value.copy(allCollections = if (included) value.allCollections + kind else value.allCollections - kind))
        }
    }
    TextButton(onClick = { onChange(LibraryFilters()) }) { Text("Reset library filters") }
}

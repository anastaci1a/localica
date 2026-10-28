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
    Text("Choose the resting order of Library filters. Selecting a filter brings it to the front; tapping it again restores the combined Library view. Global search keeps all item types available.", style = MaterialTheme.typography.bodyMedium)
    value.visible.forEachIndexed { index, filter ->
        Surface(shape = Corners.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(filter.label, style = MaterialTheme.typography.titleMedium)
                FlowRow {
                    ActionTextButton(enabled = index > 0, onClick = {
                        onChange(value.copy(visible = io.github.anastaci1a.localica.library.OccurrenceMoves.byOffset(value.visible, index, -1)))
                    }) { Text("Move up") }
                    ActionTextButton(enabled = index < value.visible.lastIndex, onClick = {
                        onChange(value.copy(visible = io.github.anastaci1a.localica.library.OccurrenceMoves.byOffset(value.visible, index, 1)))
                    }) { Text("Move down") }
                    ActionTextButton(onClick = { onChange(value.copy(visible = value.visible - filter)) }) { Text("Remove") }
                }
            }
        }
    }
    var adding by remember { mutableStateOf(false) }
    val addingOrigin = rememberGlassAnchor()
    SettingsLink("Add library filter", anchor = addingOrigin, enabled = value.visible.size < LibraryFilter.entries.size) { adding = true }
    if (adding) CompositionLocalProvider(LocalGlassOrigin provides addingOrigin) {
        ChoiceDialog("Add library filter", LibraryFilter.entries.filterNot { it in value.visible }, LibraryFilter.entries.first { it !in value.visible },
        { it.label }, { adding = false }) { onChange(value.copy(visible = value.visible + it)) }
    }
    SettingsHeading("Unfiltered Library collections")
    Text("Choose which collections appear when no filter is selected. This is independent of the visible filter buttons.", style = MaterialTheme.typography.bodyMedium)
    MediaKind.entries.filterNot { it == MediaKind.Song || it == MediaKind.Queue }.forEach { kind ->
        BooleanPreference(kind.label, kind in value.allCollections) { included ->
            onChange(value.copy(allCollections = if (included) value.allCollections + kind else value.allCollections - kind))
        }
    }
    ActionTextButton(onClick = { onChange(LibraryFilters()) }) { Text("Reset library filters") }
}


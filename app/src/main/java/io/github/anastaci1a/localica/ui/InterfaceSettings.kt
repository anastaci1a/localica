package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun InterfaceSettingsControls(value: InterfaceSettings, onChange: (InterfaceSettings) -> Unit) {
    HorizontalDivider()
    Text("Navigation and player", style = MaterialTheme.typography.titleMedium)
    BooleanPreference("Retrace tab history", value.tabHistory) { onChange(value.copy(tabHistory = it)) }
    BooleanPreference("Confirm before exiting", value.confirmExit) { onChange(value.copy(confirmExit = it)) }
    BooleanPreference("Expand player when a song starts", value.expandOnPlay) { onChange(value.copy(expandOnPlay = it)) }
    BooleanPreference("Show album artist first", value.albumArtistFirst) { onChange(value.copy(albumArtistFirst = it)) }
    BooleanPreference("Reduce motion", value.reducedMotion) { onChange(value.copy(reducedMotion = it)) }
    BooleanPreference("Faster animations", value.fasterMotion) { onChange(value.copy(fasterMotion = it)) }
    ArtworkLayoutPreference(value.artworkLayout) { onChange(value.copy(artworkLayout = it)) }
    ArtworkActionPreference("Artwork tap", value.artworkTap) { onChange(value.copy(artworkTap = it)) }
    ArtworkActionPreference("Artwork hold", value.artworkHold) { onChange(value.copy(artworkHold = it)) }
    Text("Main tabs", style = MaterialTheme.typography.titleMedium)
    value.tabs.forEachIndexed { index, tab ->
        Column {
            Text(tab.label)
            Row {
                TextButton(enabled = index > 0, onClick = {
                    val tabs = value.tabs.toMutableList().apply { add(index - 1, removeAt(index)) }
                    onChange(value.copy(tabs = tabs))
                }) { Text("Move up") }
                TextButton(enabled = index < value.tabs.lastIndex, onClick = {
                    val tabs = value.tabs.toMutableList().apply { add(index + 1, removeAt(index)) }
                    onChange(value.copy(tabs = tabs))
                }) { Text("Move down") }
                TextButton(enabled = value.tabs.size > 1, onClick = { onChange(value.copy(tabs = value.tabs - tab)) }) { Text("Remove") }
            }
        }
    }
    var adding by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { adding = true }, enabled = value.tabs.size < MainTab.entries.size) { Text("Add main tab") }
        DropdownMenu(adding, { adding = false }) {
            MainTab.entries.filterNot { it in value.tabs }.forEach { tab ->
                ActionMenuItem(tab.label, onClick = { onChange(value.copy(tabs = value.tabs + tab)); adding = false })
            }
        }
    }
    TextButton(onClick = { onChange(value.copy(tabs = InterfaceSettings().tabs)) }) { Text("Reset main tabs") }
}

@Composable
internal fun ArtworkActionPreference(label: String, value: ArtworkAction, onChange: (ArtworkAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: ${value.label}") }
        DropdownMenu(open, { open = false }) { ArtworkAction.entries.forEach { action ->
            ActionMenuItem(action.label, onClick = { onChange(action); open = false })
        } }
    }
}

@Composable
internal fun BooleanPreference(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, null)
    }
}

@Composable
internal fun ArtworkLayoutPreference(value: ArtworkLayout, onChange: (ArtworkLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Player artwork: " + value.label) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Player artwork") }, text = {
        Column { ArtworkLayout.entries.forEach { layout ->
            Row(Modifier.fillMaxWidth().toggleable(value == layout, role = Role.RadioButton,
                onValueChange = { onChange(layout); open = false }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == layout, null); Text(layout.label, Modifier.weight(1f))
            }
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

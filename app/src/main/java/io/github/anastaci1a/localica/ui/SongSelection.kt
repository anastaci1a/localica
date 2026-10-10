package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongItem(row: SongRow, playing: Boolean, enabled: Boolean, selectionMode: Boolean, checked: Boolean,
    onSelect: () -> Unit, onPlay: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag("song-" + row.id).combinedClickable(enabled = enabled, role = Role.Button,
        onClick = { if (selectionMode) onSelect() else onPlay() }, onLongClick = onSelect,
        onLongClickLabel = "Select song").padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onSelect() }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Select ${row.track.title}" })
            Column(Modifier.weight(1f)) {
                Text(row.track.title, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium)
                Text(row.track.artist ?: row.track.fileName, style = MaterialTheme.typography.bodySmall)
            }
            Text(formatTime(row.track.durationMs), style = MaterialTheme.typography.labelSmall)
        }
        extra()
    }
    HorizontalDivider()
}

@Composable
internal fun SelectionActions(rows: List<SongRow>, selected: Set<String>, onSelection: (Set<String>) -> Unit,
    queues: QueueController?, playlists: PlaylistController?, playlistState: PlaylistState,
    onRemove: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    val chosen = rows.filter { it.id in selected }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${chosen.size} selected")
        TextButton(onClick = { onSelection(rows.map { it.id }.toSet()) }) { Text("Select all") }
        TextButton(onClick = { onSelection(emptySet()) }) { Text("Clear") }
        Box {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { menu = true }) { Text("Actions") }
            DropdownMenu(menu, { menu = false }) {
                SongTap.entries.forEach { action ->
                    DropdownMenuItem(text = { Text(action.label()) }, onClick = {
                        queues?.act(chosen.map { it.track }, action); menu = false; onSelection(emptySet())
                    })
                }
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { adding = chosen.map { it.track }; menu = false })
                if (onRemove != null) DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = {
                    onRemove(); menu = false; onSelection(emptySet())
                })
            }
        }
    }
    adding?.let { tracks -> AddToPlaylistDialog(tracks, playlistState, playlists) { adding = null; onSelection(emptySet()) } }
}

@Composable
private fun AddToPlaylistDialog(tracks: List<Track>, state: PlaylistState, controller: PlaylistController?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add ${tracks.size} songs to playlist") }, text = {
        Column {
            Box {
                TextButton(onClick = { expanded = true }) { Text(state.playlists.firstOrNull { it.id == chosen }?.name ?: "New playlist") }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text("New playlist") }, onClick = { chosen = null; expanded = false })
                    state.playlists.forEach { list -> DropdownMenuItem(text = { Text(list.name) },
                        onClick = { chosen = list.id; expanded = false }) }
                }
            }
            if (chosen == null) OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !state.loading && (chosen != null || name.isNotBlank()), onClick = {
        chosen?.let { controller?.append(it, tracks) } ?: controller?.create(name, tracks)
        onDismiss()
    }) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

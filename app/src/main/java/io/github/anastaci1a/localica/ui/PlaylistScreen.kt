package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.QueueController

@Composable
fun PlaylistScreen(state: PlaylistUiState, folders: List<FolderSelection>, controller: PlaylistController?,
    queues: QueueController?, onOpen: (String?) -> Unit, onQuery: (LibraryQuery) -> Unit,
    onPlay: (String) -> Unit, currentTrackId: String?, enabled: Boolean) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var selection by remember(state.selected?.id, state.query) { mutableStateOf(emptySet<String>()) }
    val selected = state.selected
    LazyColumn(Modifier.fillMaxSize().testTag("playlist-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(selected?.name ?: "Playlists", style = MaterialTheme.typography.headlineMedium)
                if (state.saved.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.saved.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    if (selected != null) TextButton(onClick = { onOpen(null) }) { Text("All playlists") }
                    TextButton(onClick = { naming = true }) { Text(if (selected == null) "New playlist" else "Rename") }
                    if (selected != null) TextButton(onClick = { deleting = true }) { Text("Delete") }
                }
                if (selected != null) {
                    QueryControls(state.query, folders, SongListView.Playlists, onQuery)
                    SelectionActions(state.rows, selection, { selection = it }, queues, controller, state.saved,
                        onRemove = { controller?.removeEntries(selected.id, state.rows.filter { it.id in selection }.map { it.id }.toSet()) })
                    if (selected.entries.isEmpty()) Text("Select songs in Library, then choose Actions → Add to playlist.")
                    else if (state.rows.isEmpty()) Text("No matching songs")
                } else if (state.saved.playlists.isEmpty() && !state.saved.loading) Text("Create a playlist or add selected songs from Library.")
            }
        }
        if (selected == null) items(state.saved.playlists, key = { it.id }) { playlist ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(playlist.id) }.padding(20.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                Text("${playlist.entries.size} songs")
            }
        } else itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
            SongItem(row, currentTrackId == row.track.id, enabled, selection.isNotEmpty(), row.id in selection,
                onSelect = { selection = if (row.id in selection) selection - row.id else selection + row.id },
                onPlay = { onPlay(row.id) }) {
                Row {
                    // Reordering is meaningful only when the complete saved order is displayed.
                    val reorder = state.query == LibraryViews.defaults(SongListView.Playlists)
                    TextButton(enabled = reorder && index > 0, onClick = { controller?.move(selected.id, row.id, -1) }) { Text("Up") }
                    TextButton(enabled = reorder && index < state.rows.lastIndex, onClick = { controller?.move(selected.id, row.id, 1) }) { Text("Down") }
                    TextButton(onClick = { controller?.removeEntries(selected.id, setOf(row.id)) }) { Text("Remove") }
                }
            }
        }
    }
    if (naming) {
        var name by remember(selected?.id) { mutableStateOf(selected?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text(if (selected == null) "New playlist" else "Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                if (selected == null) controller?.create(name) else controller?.rename(selected.id, name)
                naming = false
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
    }
    if (deleting && selected != null) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("Delete playlist?") }, text = { Text("Delete “${selected.name}”? Audio files and queues are kept.") },
        confirmButton = { TextButton(onClick = { controller?.delete(selected.id); deleting = false; onOpen(null) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

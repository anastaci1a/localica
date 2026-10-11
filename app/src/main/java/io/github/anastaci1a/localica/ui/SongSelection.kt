package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

@Composable
internal fun SelectionActions(rows: List<SongRow>, selected: Set<String>, onSelection: (Set<String>) -> Unit,
    queues: QueueController?, playlists: PlaylistController?, playlistState: PlaylistState,
    onRemove: (((Boolean) -> Unit) -> Unit)? = null, removeLabel: String = "Remove from playlist",
    onAll: (() -> Unit)? = null, onRange: (() -> Unit)? = null, onCollapse: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val editTags = LocalTagEdit.current
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    val chosen = rows.filter { it.id in selected }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${chosen.size} selected", Modifier.weight(1f))
            TextButton(onClick = { onAll?.invoke() ?: onSelection(rows.map { it.id }.toSet()) }) { Text("All") }
            if (onRange != null) TextButton(onClick = onRange) { Text("Range") }
            if (onCollapse != null) ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_down, "Collapse selection", onClick = onCollapse)
            ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_close, "Close selection") { onSelection(emptySet()) }
        }
        FlowRow(Modifier.fillMaxWidth()) {
            if (editTags != null) TextButton(enabled = chosen.isNotEmpty(), onClick = {
                editTags(chosen.map { it.track }.distinctBy { it.id }) { if (it) onSelection(emptySet()) }
            }) { Text("Edit tags") }
            SongTap.entries.forEach { action -> TextButton(enabled = chosen.isNotEmpty() && queues != null, onClick = {
                queues?.act(chosen.map { it.track }, action, chosen.map { it.sourcePlaylistId }) { if (it) onSelection(emptySet()) }
            }) { Text(when (action) { SongTap.NewQueue -> "New queue"; SongTap.PlayNow -> "Play now"; SongTap.PlayNext -> "Play next"; SongTap.Append -> "Append" }) } }
            TextButton(enabled = chosen.isNotEmpty(), onClick = { adding = chosen.map { it.track } }) { Text("Add to playlist") }
            if (onRemove != null) TextButton(enabled = chosen.isNotEmpty(), onClick = {
                onRemove { if (it) onSelection(emptySet()) }
            }) { Text(removeLabel) }
        }
    }
    adding?.let { tracks -> AddToPlaylistDialog(tracks, playlistState, playlists,
        onDismiss = { adding = null }, onAdded = { adding = null; onSelection(emptySet()) }) }
}

@Composable
internal fun AddToPlaylistDialog(tracks: List<Track>, state: PlaylistState, controller: PlaylistController?,
    onDismiss: () -> Unit, onAdded: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Add ${tracks.size} songs to playlist") }, text = {
        Column {
            val pickerAnchor = rememberGlassAnchor()
            val pickerLabel = state.playlists.firstOrNull { it.id == chosen }?.name ?: "New playlist"
            SideEffect { pickerAnchor.face = { Text(pickerLabel, Modifier.padding(horizontal = 12.dp), maxLines = 1) } }
            Box {
                LiquidGlassSurface(Modifier.glassAnchor(pickerAnchor)) {
                    TextButton(onClick = { expanded = true }) { Text(pickerLabel) }
                }
                GlassMenu(expanded, pickerAnchor, { expanded = false }) {
                    DropdownMenuItem(text = { Text("New playlist") }, onClick = { chosen = null; expanded = false })
                    state.playlists.forEach { list ->
                        val contains = tracks.size == 1 && list.entries.any { it.track.id == tracks.single().id }
                        DropdownMenuItem(text = { Text(list.name,
                            fontWeight = if (contains) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            modifier = Modifier.semantics { if (contains) contentDescription = list.name + ", already contains this song" }) },
                        onClick = { chosen = list.id; expanded = false }) }
                }
            }
            if (chosen == null) OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !state.loading && (chosen != null || name.isNotBlank()), onClick = {
        val complete: (Boolean) -> Unit = { if (it) onAdded() }
        chosen?.let { controller?.append(it, tracks, complete) } ?: controller?.create(name, tracks, complete)
    }) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

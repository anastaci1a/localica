package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
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
            LiquidGlassSurface(pressFeedback = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onCollapse != null) {
                        ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_down, "Collapse selection", glass = false, onClick = onCollapse)
                        GlassPillDivider()
                    }
                    ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_close, "Close selection", glass = false) { onSelection(emptySet()) }
                }
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddToPlaylistDialog(tracks: List<Track>, state: PlaylistState, controller: PlaylistController?,
    onDismiss: () -> Unit, onAdded: () -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(emptyList<String>()) }
    var saving by remember { mutableStateOf(false) }
    val duplicate = name.isNotBlank() && !PlaylistEdits.nameAvailable(state.playlists, name)
    val valid = controller != null && !saving && !state.loading && !duplicate && (name.isNotBlank() || chosen.isNotEmpty())
    fun add() {
        if (!valid) return
        saving = true
        controller?.addToDestinations(chosen.toSet(), name, tracks) { success -> saving = false; if (success) onAdded() }
    }
    GlassAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Add to playlists") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassSearchField(name, { name = it }, "New playlist name", enabled = !saving,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }))
            if (duplicate) Text("A playlist with this name already exists.", color = MaterialTheme.colorScheme.error)
            SelectionViewport(Modifier.fillMaxWidth().heightIn(max = 320.dp)) { overscroll ->
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), overscrollEffect = overscroll) {
                items(state.playlists.size, key = { state.playlists[it].id }) { index ->
                    val playlist = state.playlists[index]
                    val selected = playlist.id in chosen
                    Row(Modifier.fillMaxWidth().selectionLens(selected, false)
                        .then(Modifier.combinedClickable(enabled = !saving, onClick = {
                            chosen = if (selected) chosen - playlist.id else chosen + playlist.id
                        })).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaArtwork(listOfNotNull(playlist.coverUri) + playlist.entries.map { it.track.uri }, Modifier.size(44.dp))
                        Text(playlist.name, Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        InterfaceCheckbox(selected, null, enabled = !saving)
                    }
                }
            }
            }
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = ::add) { Text("Add") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}

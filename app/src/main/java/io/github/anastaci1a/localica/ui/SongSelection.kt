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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.lazy.LazyListState
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*

/** Passive in-host drawing witness; it neither invalidates drawing nor captures pixels. */
internal data class PlaylistSelectionDrawSource(val scene: SelectionScene, val state: LazyListState,
    val viewport: LayoutCoordinates, val content: LayoutCoordinates,
    val rows: Map<String, LayoutCoordinates>, val chosen: List<String>, val view: android.view.View,
    val hostDraws: () -> Int)
internal val LocalPlaylistSelectionDrawObserver = staticCompositionLocalOf<((PlaylistSelectionDrawSource) -> Unit)?> { null }
private class PlaylistSelectionProbe {
    var scene: SelectionScene? = null
    var viewport: LayoutCoordinates? = null
    var content: LayoutCoordinates? = null
    val rows = mutableMapOf<String, LayoutCoordinates>()
    var hostDraws = 0
}

@Composable
internal fun SelectionActions(rows: List<SongRow>, selected: Set<String>, onSelection: (Set<String>) -> Unit,
    queues: QueueController?, playlists: PlaylistController?, playlistState: PlaylistState,
    onRemove: (((Boolean) -> Unit) -> Unit)? = null, removeLabel: String = "Remove from playlist",
    onAll: (() -> Unit)? = null, onRange: (() -> Unit)? = null, onCollapse: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val editTags = LocalTagEdit.current
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    val chosen = rows.filter { it.id in selected }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${chosen.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_queue, "All") {
                onAll?.invoke() ?: onSelection(rows.map { it.id }.toSet())
            }
            if (onRange != null) GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_queue, "Range", onClick = onRange)
            if (editTags != null) GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_edit, "Edit tags", enabled = chosen.isNotEmpty()) {
                editTags(chosen.map { it.track }.distinctBy { it.id }) { if (it) onSelection(emptySet()) }
            }
        }
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = .12f))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SongTap.entries.forEach { action ->
                val label = when (action) { SongTap.NewQueue -> "New queue"; SongTap.PlayNow -> "Play now"; SongTap.PlayNext -> "Play next"; SongTap.Append -> "Append" }
                GlassActionButton(if (action == SongTap.PlayNow) io.github.anastaci1a.localica.R.drawable.ic_play else io.github.anastaci1a.localica.R.drawable.ic_queue,
                    label, enabled = chosen.isNotEmpty() && queues != null) {
                    queues?.act(chosen.map { it.track }, action, chosen.map { it.sourcePlaylistId }) { if (it) onSelection(emptySet()) }
                }
            }
            GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_playlist_add, "Add to playlist", enabled = chosen.isNotEmpty()) { adding = chosen.map { it.track } }
            if (onRemove != null) GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_close, removeLabel, enabled = chosen.isNotEmpty()) {
                onRemove { if (it) onSelection(emptySet()) }
            }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val browsePreferences = remember(context) { io.github.anastaci1a.localica.browse.BrowsePreferences(context) }
    val playlistRoute = remember { io.github.anastaci1a.localica.navigation.BrowseRoute(io.github.anastaci1a.localica.navigation.MainTab.Playlists) }
    var playlistStyle by remember { mutableStateOf(browsePreferences.read(playlistRoute)) }
    DisposableEffect(browsePreferences) { val stop = browsePreferences.observe(playlistRoute) { playlistStyle = it }; onDispose(stop) }
    val sortedPlaylists by produceState(state.playlists, state.playlists, playlistStyle) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val byId = state.playlists.associateBy { it.id }
            io.github.anastaci1a.localica.browse.BrowseQueries.evaluate(io.github.anastaci1a.localica.library.LibrarySnapshot(),
                state.playlists, playlistRoute, playlistStyle.query()).items.mapNotNull { byId[it.ref.id] }
        }
    }
    val duplicate = name.isNotBlank() && !PlaylistEdits.nameAvailable(state.playlists, name)
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val probe = remember { PlaylistSelectionProbe() }
    val observer = LocalPlaylistSelectionDrawObserver.current
    val observe by rememberUpdatedState(observer)
    val valid = controller != null && !saving && !state.loading && !duplicate && (name.isNotBlank() || chosen.isNotEmpty())
    fun add() {
        if (!valid) return
        saving = true
        controller?.addToDestinations(chosen.toSet(), name, tracks) { success -> saving = false; if (success) onAdded() }
    }
    GlassAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Add to playlists") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassSearchField(name, { name = it }, "New playlist name", enabled = !saving, showSearchIcon = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }))
            if (duplicate) Text("A playlist with this name already exists.", color = MaterialTheme.colorScheme.error)
            val selectionView = androidx.compose.ui.platform.LocalView.current
            SelectionViewport(Modifier.fillMaxWidth().heightIn(max = 320.dp).scrollEdgeFade(scroll)
                .testTag("playlist-selection-viewport")
                .then(if (observer == null) Modifier else Modifier.onPlaced { probe.viewport = it }),
                observePlacement = { scroll.layoutInfo; if (observer != null) probe.hostDraws++; Unit },
                visibleItemKeys = { scroll.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.key } },
                contentDrawing = if (observer == null) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val scene = probe.scene
                    val viewport = probe.viewport
                    val content = probe.content
                    if (scene != null && viewport?.isAttached == true && content?.isAttached == true)
                        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                            observe?.invoke(PlaylistSelectionDrawSource(scene, scroll, viewport, content, probe.rows, chosen, selectionView) { probe.hostDraws })
                        }
                }) { overscroll ->
            val scene = LocalSelectionScene.current
            SideEffect { probe.scene = scene }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()
                .then(if (observer == null) Modifier else Modifier.onPlaced { probe.content = it }),
                state = scroll, overscrollEffect = overscroll) {
                items(sortedPlaylists.size, key = { sortedPlaylists[it].id }) { index ->
                    val playlist = sortedPlaylists[index]
                    val selected = playlist.id in chosen
                    if (observer != null) DisposableEffect(playlist.id) { onDispose { probe.rows.remove(playlist.id) } }
                    Row(Modifier.fillMaxWidth().semantics { this.selected = selected }.selectionLens(selected, false, itemKey = playlist.id)
                        .testTag("playlist-selection-${playlist.id}")
                        .then(if (observer == null) Modifier else Modifier.onPlaced { probe.rows[playlist.id] = it })
                        .then(Modifier.combinedClickable(enabled = !saving, onClick = {
                            chosen = if (selected) chosen - playlist.id else chosen + playlist.id
                        })).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaArtwork(io.github.anastaci1a.localica.browse.collectionCoverUris(playlist.coverUri, playlist.entries.firstOrNull()?.track?.uri), Modifier.size(44.dp))
                        Text(playlist.name, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            }
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { ActionTextButton(enabled = valid, onClick = ::add) { Text("Add") } },
        dismissButton = { ActionTextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}

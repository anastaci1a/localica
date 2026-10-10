package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BrowserScreen(library: LibraryUiState, playlists: PlaylistState, route: BrowseRoute,
    onRoute: (BrowseRoute) -> Unit, onOpen: (BrowserItem) -> Unit, onSettings: () -> Unit,
    onPlay: (List<SongRow>, Int, SongListView) -> Unit, queues: QueueController?, playlistController: PlaylistController?,
    enabled: Boolean, playerOpen: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { BrowsePreferences(context) }
    var style by remember(route.key, route.filter) { mutableStateOf(preferences.read(route)) }
    var sorting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val result by produceState(BrowseResult(), library.snapshot, playlists.playlists, route, style) {
        value = withContext(Dispatchers.Default) { BrowseQueries.evaluate(library.snapshot, playlists.playlists, route, style.query()) }
    }
    var selection by remember(route.key, route.text, route.filter) { mutableStateOf(emptySet<String>()) }
    var contextMenu by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    BackHandler(selection.isNotEmpty() && !playerOpen) { selection = emptySet(); contextMenu = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (route.collection?.kind == MediaKind.Playlist) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
            }
            ActionIcon(R.drawable.ic_sort, "Sort and presentation") { sorting = true }
        }
        if (route.search) OutlinedTextField(route.text, { onRoute(route.copy(text = it)) }, singleLine = true,
            label = { Text(if (route.globalSearch) "Search library" else "Search ${route.title ?: route.tab.label}") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if (route.collection == null && (route.tab == MainTab.Library || route.globalSearch)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(route.filter == null, { onRoute(route.copy(filter = null)) }, { Text("All") })
                MediaKind.entries.forEach { kind -> FilterChip(route.filter == kind,
                    { onRoute(route.copy(filter = kind)) }, { Text(kind.label) }) }
            }
        }
        if (selection.isNotEmpty()) SelectionActions(result.rows, selection, { selection = it }, queues, playlistController, playlists,
            onRemove = if (route.collection?.kind == MediaKind.Playlist) ({
                playlistController?.removeEntries(route.collection.id, selection)
            }) else null)
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(columns = when (style.presentation) {
                Presentation.List -> GridCells.Fixed(1)
                Presentation.Compact -> GridCells.Adaptive(100.dp)
                Presentation.Standard -> GridCells.Adaptive(150.dp)
                Presentation.Large -> GridCells.Adaptive(220.dp)
            }, modifier = Modifier.fillMaxSize().testTag("browser-list"), contentPadding = PaddingValues(bottom = 80.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LibraryNotice(library)
                        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (library.snapshot.folders.isEmpty()) TextButton(onClick = onSettings) { Text("Add music folders") }
                        if (result.items.isEmpty() && !library.loading) Text(if (route.search) "No matching items" else "Nothing here yet")
                    }
                }
                items(result.items, key = { it.key }) { item ->
                    Box {
                        Column(Modifier.fillMaxWidth().testTag("media-${item.key}").combinedClickable(
                            enabled = item.row == null || enabled,
                            onClick = {
                                if (item.row == null) onOpen(item)
                                else if (selection.isNotEmpty()) selection = if (item.key in selection) selection - item.key else selection + item.key
                                else onPlay(result.rows, result.rows.indexOfFirst { it.id == item.row.id }, result.scope)
                            }, onLongClickLabel = if (item.row != null) "Select song and show actions" else null,
                            onLongClick = if (item.row != null) ({ selection = selection + item.key; contextMenu = item.key }) else null
                        ).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (style.presentation != Presentation.List) {
                                AlbumArtwork(item.songs.firstOrNull()?.track?.uri, Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.row != null && selection.isNotEmpty()) Checkbox(item.key in selection,
                                    { selection = if (item.key in selection) selection - item.key else selection + item.key })
                                if (style.presentation == Presentation.List) AlbumArtwork(item.songs.firstOrNull()?.track?.uri, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(start = if (style.presentation == Presentation.List) 12.dp else 0.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                }
                            }
                        }
                        DropdownMenu(contextMenu == item.key, { contextMenu = null }) {
                            SongTap.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label()) }, onClick = {
                                queues?.act(result.rows.filter { it.id in selection }.map { it.track }, action)
                                contextMenu = null; selection = emptySet()
                            }) }
                            DropdownMenuItem(text = { Text("Continue selecting") }, onClick = { contextMenu = null })
                            if (route.collection?.kind == MediaKind.Playlist) {
                                val index = result.rows.indexOfFirst { it.id == item.key }
                                val manual = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank()
                                DropdownMenuItem(text = { Text("Move up") }, enabled = manual && index > 0, onClick = {
                                    playlistController?.move(route.collection.id, item.key, -1); contextMenu = null; selection = emptySet()
                                })
                                DropdownMenuItem(text = { Text("Move down") }, enabled = manual && index < result.rows.lastIndex, onClick = {
                                    playlistController?.move(route.collection.id, item.key, 1); contextMenu = null; selection = emptySet()
                                })
                            }
                        }
                    }
                }
            }
            if (route.tab == MainTab.Library && route.collection == null && !route.search) FloatingActionButton(
                onClick = { naming = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_plus), "Create playlist", Modifier.size(24.dp))
            }
        }
    }
    if (sorting) BrowseSortDialog(route, style, preferences.overridden(route), { sorting = false }, { value, override ->
        preferences.write(route, value, override); style = value; sorting = false; selection = emptySet()
    }, { preferences.reset(route); style = preferences.read(route); sorting = false })
    if (renaming) {
        var name by remember { mutableStateOf(playlists.playlists.firstOrNull { it.id == route.collection?.id }?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename playlist") }, text = {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            route.collection?.let { playlistController?.rename(it.id, name); onRoute(route.copy(title = name.trim())) }; renaming = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete playlist?") },
        text = { Text("Audio files and queues are kept.") }, confirmButton = { TextButton(onClick = {
            route.collection?.let { playlistController?.delete(it.id) }; deleting = false
            onRoute(route.copy(collection = null, title = null))
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text("New playlist") }, text = {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { playlistController?.create(name); naming = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
    }
}

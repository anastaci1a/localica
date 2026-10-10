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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.home.PinEdits
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BrowserScreen(library: LibraryUiState, playlists: PlaylistState, route: BrowseRoute,
    onRoute: (BrowseRoute) -> Unit, onOpen: (BrowserItem) -> Unit, onSettings: () -> Unit,
    onPlay: (List<SongRow>, Int, SongListView) -> Unit, queues: QueueController?, playlistController: PlaylistController?,
    enabled: Boolean, playerOpen: Boolean, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {},
    sorting: Boolean = false, onSortDismiss: () -> Unit = {}, queueState: QueueUiState = QueueUiState(),
    playingTrackId: String? = null, playingEntryId: String? = null,
    header: @Composable () -> Unit = {}, presentationOnly: Boolean = false, selectionVisible: Boolean = true,
    onSearchEdit: (BrowseRoute) -> Unit = onRoute, onSearchCommit: () -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val editTags = LocalTagEdit.current
    val preferences = remember(context) { BrowsePreferences(context) }
    var style by remember(route.key, route.filter) { mutableStateOf(preferences.read(route).let { it.copy(sort = route.querySort ?: it.sort, ascending = route.queryAscending ?: it.ascending) }) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(false) }
    LaunchedEffect(route.key) {
        if (route.search && !focusedOnce) { focus.requestFocus(); keyboard?.show(); focusedOnce = true }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val loadedResult by produceState<BrowseResult?>(null, library.snapshot, playlists.playlists, route, style, queueState.library) {
        value = withContext(Dispatchers.Default) { BrowseQueries.evaluate(library.snapshot, playlists.playlists, route, style.query(), queueState.library.queues) }
    }
    val result = loadedResult ?: BrowseResult()
    val gridState = rememberLazyGridState()
    val scrollScope = rememberCoroutineScope()
    var consumedScrollRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopRequest, loadedResult != null) {
        if (loadedResult != null && consumedScrollRequest != scrollToTopRequest) {
            consumedScrollRequest = scrollToTopRequest
            gridState.easeToTop(reducedMotion)
        }
    }
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    val selection = session.entries.filter { it.source == route.collection }.map { it.row.id }.toSet()
    SideEffect { if (selectionVisible) { session.visible = result.rows; session.source = route.collection } }
    var contextMenu by remember { mutableStateOf<String?>(null) }
    var addingSong by remember { mutableStateOf<Track?>(null) }
    var info by remember { mutableStateOf<Track?>(null) }
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (route.collection?.kind == MediaKind.Playlist) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
        }
        header()
        OutlinedTextField(route.text, { onSearchEdit(route.copy(text = it, querySort = style.sort, queryAscending = style.ascending)) }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearchCommit(); keyboard?.hide() }),
            trailingIcon = { ActionIcon(R.drawable.ic_search, "Submit search") { onSearchCommit(); keyboard?.hide() } },
            label = { Text(if (route.globalSearch) "Search library" else "Search ${route.title ?: route.tab.label}") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus))
        if (route.collection == null && (route.tab == MainTab.Library || route.globalSearch)) {
            Row(Modifier.nestedScroll(HorizontalGestureOwner).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(route.filter == null, { onRoute(route.copy(filter = null)) }, { Text("All") })
                BrowseQueries.libraryKinds.forEach { kind -> FilterChip(route.filter == kind,
                    { onRoute(route.copy(filter = kind)) }, { Text(kind.label) }) }
            }
        }
        Box(Modifier.weight(1f)) {
            if (loadedResult == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LazyVerticalGrid(state = gridState, columns = when (style.presentation) {
                Presentation.List -> GridCells.Fixed(1)
                Presentation.Compact -> GridCells.Adaptive(100.dp)
                Presentation.Standard -> GridCells.Adaptive(150.dp)
                Presentation.Large -> GridCells.Adaptive(220.dp)
            }, modifier = Modifier.fillMaxSize().testTag("browser-list"), contentPadding = PaddingValues(bottom = 80.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LibraryNotice(library)
                        playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (library.snapshot.folders.isEmpty() && route.collection?.kind != MediaKind.Queue) TextButton(onClick = onSettings) { Text("Add music folders") }
                        if (result.items.isEmpty() && !library.loading) Text(if (route.search) "No matching items" else "Nothing here yet")
                    }
                }
                items(result.items, key = { it.key }) { item ->
                    val current = item.row != null && if (playingEntryId != null) item.row.id == playingEntryId else item.row.track.id == playingTrackId
                    Box {
                        Column(Modifier.fillMaxWidth().testTag("media-${item.key}").combinedClickable(
                            enabled = item.row == null || enabled,
                            onClick = {
                                if (item.row == null) { onSearchCommit(); onOpen(item) }
                                else if (session.active) session.toggle(route.collection, item.row)
                                else { onSearchCommit(); onPlay(result.rows, result.rows.indexOfFirst { it.id == item.row.id }, result.scope) }
                            }, onLongClickLabel = if (item.row != null) "Select song and show actions" else "Collection actions",
                            onLongClick = {
                                if (item.row != null) session.select(route.collection, listOf(item.row))
                                else contextMenu = item.key
                            }
                        ).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (style.presentation != Presentation.List) {
                                MediaArtwork(item.coverUris, Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.row != null && session.active) Checkbox(item.key in selection,
                                    { session.toggle(route.collection, item.row) })
                                if (style.presentation == Presentation.List) MediaArtwork(item.coverUris, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(start = if (style.presentation == Presentation.List) 12.dp else 0.dp)) {
                                    Text((if (current) "▶ " else "") + item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    item.information(style)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                }
                                if (item.row != null && style.sort == TrackSort.Manual && style.ascending && route.text.isBlank() &&
                                    route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) ReorderHandle("Reorder " + item.title) { offset ->
                                    if (route.collection?.kind == MediaKind.Queue) queues?.moveEntry(item.key, offset, route.collection?.id)
                                    else route.collection?.let { playlistController?.move(it.id, item.key, offset) {} }
                                }
                                ActionIcon(R.drawable.ic_more, "Actions for " + item.title) { contextMenu = item.key }
                            }
                        }
                        DropdownMenu(contextMenu == item.key, { contextMenu = null }) {
                            if (item.row != null) {
                                ActionMenuItem("Information", onClick = { contextMenu = null; info = item.row.track })
                                if (editTags != null) ActionMenuItem("Edit tags", onClick = { contextMenu = null; editTags(listOf(item.row.track)) {} })
                                ActionMenuItem("Add to playlist", onClick = { contextMenu = null; addingSong = item.row.track })
                                SongTap.entries.forEach { action -> ActionMenuItem(action.label(), onClick = {
                                    contextMenu = null; queues?.act(listOf(item.row.track), action)
                                }) }
                            }
                            if (item.row == null) ActionMenuItem("Select all songs", onClick = {
                                val contents = route.copy(collection = item.ref, globalSearch = false, search = false, text = "")
                                val songs = BrowseQueries.evaluate(library.snapshot, playlists.playlists, contents,
                                    preferences.read(contents).query(), queueState.library.queues).rows
                                session.select(item.ref, songs); contextMenu = null
                            })
                            if (item.ref.kind in PinEdits.supported) DropdownMenuItem(text = { Text(if (item.ref in pins) "Unpin" else "Pin to Home") },
                                onClick = { onPin(item.ref); contextMenu = null })
                            if (route.collection?.kind == MediaKind.Playlist) {
                                val index = result.rows.indexOfFirst { it.id == item.key }
                                val manual = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank()
                                ActionMenuItem("Move up", enabled = manual && index > 0, onClick = {
                                    playlistController?.move(route.collection.id, item.key, -1) { Unit }; contextMenu = null
                                })
                                ActionMenuItem("Move down", enabled = manual && index < result.rows.lastIndex, onClick = {
                                    playlistController?.move(route.collection.id, item.key, 1) { if (it) session.clear() }; contextMenu = null
                                })
                            }
                            if (route.collection?.kind == MediaKind.Queue) {
                                val index = result.rows.indexOfFirst { it.id == item.key }
                                val manual = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank()
                                ActionMenuItem("Move up", enabled = manual && index > 0,
                                    onClick = { queues?.moveEntry(item.key, -1, route.collection?.id) { if (it) session.clear() }; contextMenu = null })
                                ActionMenuItem("Move down", enabled = manual && index < result.rows.lastIndex,
                                    onClick = { queues?.moveEntry(item.key, 1, route.collection?.id) { if (it) session.clear() }; contextMenu = null })
                            }
                        }
                    }
                }
            }
            val playingIndex = result.items.indexOfFirst { it.row?.id == playingEntryId }.takeIf { playingEntryId != null && it >= 0 }?.plus(1)
            val showCurrent by remember(playingIndex) { derivedStateOf {
                playingIndex != null && gridState.layoutInfo.visibleItemsInfo.none { it.index == playingIndex }
            } }
            androidx.compose.animation.AnimatedVisibility(showCurrent, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300))) {
                FilledTonalButton(onClick = { playingIndex?.let { scrollScope.launch { gridState.easeToItem(it, reducedMotion) } } }) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_play), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("Current song")
                }
            }
            if (loadedResult != null) BrowseScrollbar(gridState, result.items, style.sort, Modifier.align(Alignment.CenterEnd))
            if (route.tab == MainTab.Library && route.collection == null && !route.search) FloatingActionButton(
                onClick = { naming = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_plus), "Create playlist", Modifier.size(24.dp))
            }
        }
        if (LocalSessionSelection.current == null) SessionSelectionPanel(session, queues, playlistController, playlists)
    }

    addingSong?.let { song -> AddToPlaylistDialog(listOf(song), playlists, playlistController, { addingSong = null }, { addingSong = null }) }
    info?.let { song -> SongInformation(song, { info = null }, { editTags?.invoke(listOf(song)) {} }) { action ->
        val tab = when (action) { PlayerAction.Album -> MainTab.Albums; PlayerAction.Artist -> MainTab.Artists
            PlayerAction.AlbumArtist -> MainTab.AlbumArtists; else -> MainTab.Folders }
        BrowseQueries.evaluate(library.snapshot, playlists.playlists, BrowseRoute(tab)).items
            .firstOrNull { item -> item.songs.any { it.track.id == song.id } }?.let(onOpen)
    } }
    if (sorting) BrowseSortDialog(route, style, preferences.overridden(route), onSortDismiss, { value, override ->
        preferences.write(route, value, override); style = value
        if (route.search) onRoute(route.copy(querySort = value.sort, queryAscending = value.ascending))
        onSortDismiss()
    }, { preferences.reset(route); style = preferences.read(route); onSortDismiss() }, presentationOnly)
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

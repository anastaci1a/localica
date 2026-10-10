package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.home.PinEdits
import kotlinx.coroutines.flow.first
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
    onSearchEdit: (BrowseRoute) -> Unit = onRoute, onSearchCommit: () -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false,
    libraryFilters: LibraryFilters = LibraryFilters(), onFilterSettings: () -> Unit = {}) {
    val obscuredInset = LocalBrowseObscuredInset.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val editTags = LocalTagEdit.current
    val preferences = remember(context) { BrowsePreferences(context) }
    var style by remember(route.key, route.filter) { mutableStateOf(preferences.read(route).let { it.copy(sort = route.querySort ?: it.sort, ascending = route.queryAscending ?: it.ascending) }) }
    val focus = remember(route.key) { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(false) }
    LaunchedEffect(route.key, playerOpen) {
        if (route.search && !focusedOnce && !playerOpen) {
            withFrameNanos { } // Wait until the newly keyed field is attached.
            focus.requestFocus(); keyboard?.show(); focusedOnce = true
        }
    }
    val filters = if (route.globalSearch) LibraryFilter.entries else libraryFilters.visible
    LaunchedEffect(libraryFilters.visible, route.key, route.filter, selectionVisible) {
        if (selectionVisible && route.tab == MainTab.Library && route.collection == null && !route.globalSearch && filters.none { it.kind == route.filter }) {
            onRoute(route.copy(filter = filters.first().kind))
        }
    }
    var filterMenu by remember { mutableStateOf<LibraryFilter?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val loadedResult by produceState<BrowseResult?>(null, library.snapshot, playlists.playlists, route, style, queueState.library, libraryFilters) {
        value = withContext(Dispatchers.Default) {
            BrowseQueries.evaluate(library.snapshot, playlists.playlists, route, style.query(), queueState.library.queues, libraryFilters.allCollections).let {
                if (route.collection?.kind == MediaKind.Album) it.groupAlbumSongs(style.songGrouping) else it
            }
        }
    }
    val result = loadedResult ?: BrowseResult()
    val gridState = rememberLazyGridState()
    val scrollScope = rememberCoroutineScope()
    var consumedScrollRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopRequest) {
        if (consumedScrollRequest != scrollToTopRequest) {
            // Keep one job through query publication and the first layout. A readiness key can
            // restart this effect after it consumed the request but before scrolling could begin.
            snapshotFlow { loadedResult != null && gridState.layoutInfo.totalItemsCount > 0 }.first { it }
            consumedScrollRequest = scrollToTopRequest
            gridState.easeToTop(reducedMotion)
        }
    }
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    val selectedTracks = session.entries.map { it.row.track.id }.toSet()
    SideEffect { if (selectionVisible) { session.visible = result.rows; session.source = route.collection } }
    val reorder = remember(route.key) { SongDragState(gridState, scrollScope) }
    val dragHost = LocalSongDragHost.current
    SideEffect {
        if (reorder.drag != null) { dragHost?.state = reorder; dragHost?.reducedMotion = reducedMotion }
        else if (dragHost?.state === reorder) dragHost.state = null
    }
    DisposableEffect(reorder, dragHost) { onDispose { if (dragHost?.state === reorder) dragHost.state = null } }
    val editableOrder = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank() &&
        route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)
    SideEffect { reorder.reconcile(result.items); reorder.obscuredBottom = with(density) { obscuredInset.toPx() } }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(reorder.drag) { if (reorder.drag != null) reorder.autoScroll(edge) }
    val moveSongs: (Set<String>, String?, (Boolean) -> Unit) -> Unit = { ids, before, done ->
        when (route.collection?.kind) {
            MediaKind.Queue -> if (queues != null) queues.moveBefore(route.collection.id, ids, before, done) else done(false)
            MediaKind.Playlist -> if (playlistController != null) playlistController.moveBefore(route.collection.id, ids, before, done) else done(false)
            else -> done(false)
        }
    }
    var contextMenu by remember { mutableStateOf<String?>(null) }
    var addingSong by remember { mutableStateOf<Track?>(null) }
    var info by remember { mutableStateOf<Track?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (route.collection?.kind == MediaKind.Playlist) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
        }
        header()
        if (route.search || style.quickSearch && (route.collection != null || route.tab != MainTab.Library)) OutlinedTextField(route.text, { onSearchEdit(route.copy(text = it, querySort = style.sort, queryAscending = style.ascending)) }, singleLine = true, enabled = !playerOpen,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearchCommit(); keyboard?.hide() }),
            trailingIcon = { ActionIcon(R.drawable.ic_search, "Submit search") { onSearchCommit(); keyboard?.hide() } },
            label = { Text(if (route.globalSearch) "Search library" else "Search ${route.title ?: route.tab.label}") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus))
        if (route.collection == null && (route.tab == MainTab.Library || route.globalSearch)) {
            Row(Modifier.nestedScroll(HorizontalGestureOwner).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { filter -> Box {
                    val selected = route.filter == filter.kind
                    Surface(shape = Corners.card,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(filter.label, Modifier.heightIn(min = 48.dp).combinedClickable(
                            role = androidx.compose.ui.semantics.Role.Tab,
                            onClick = { onRoute(route.copy(filter = filter.kind)) },
                            onLongClickLabel = "Configure library filters", onLongClick = { filterMenu = filter })
                            .semantics { this.selected = selected }.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge)
                    }
                    DropdownMenu(filterMenu == filter, { filterMenu = null }) {
                        ActionMenuItem("Edit library filters and order", onClick = { filterMenu = null; onFilterSettings() })
                    }
                } }
            }
        }
        Box(Modifier.weight(1f).onGloballyPositioned { reorder.viewportOrigin = it.positionInRoot() }.onSizeChanged { reorder.viewportHeight = it.height; reorder.viewportWidth = it.width }
            .songDrag(reorder, editableOrder && enabled, route.collection, session, reducedMotion, style.presentation == Presentation.List, moveSongs)) {
            if (loadedResult == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(galleryColumns(style.presentation,
                with(androidx.compose.ui.platform.LocalDensity.current) { reorder.viewportWidth.toDp().value })), modifier = Modifier.fillMaxSize().testTag("browser-list"), contentPadding = PaddingValues(bottom = maxOf(80.dp, obscuredInset + 16.dp))) {
                item(key = "browser-notice", contentType = "notice", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LibraryNotice(library)
                        playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (library.snapshot.folders.isEmpty() && route.collection?.kind != MediaKind.Queue) TextButton(onClick = onSettings) { Text("Add music folders") }
                        if (result.items.isEmpty() && !library.loading) Text(if (route.search) "No matching items" else "Nothing here yet")
                    }
                }
                items(reorder.rendered(result.items), key = { it?.key ?: DropGapKey },
                    contentType = { if (it == null) "gap" else "${style.presentation}:${it.ref.kind}" }) { entry ->
                    if (entry == null) {
                        val height = with(androidx.compose.ui.platform.LocalDensity.current) { (reorder.drag?.cardSize?.height ?: 0f).toDp() }
                        Box(Modifier.fillMaxWidth().height(height).testTag("drop-gap"))
                        return@items
                    }
                    val item = entry
                    val current = item.row != null && if (playingEntryId != null) item.row.id == playingEntryId else item.row.track.id == playingTrackId
                    val selectedCount = if (item.row != null) { if (session.contains(route.collection, item.row.id)) 1 else 0 }
                        else item.songs.count { it.track.id in selectedTracks }
                    val partiallySelected = item.row == null && selectedCount > 0 && selectedCount < item.songs.size
                    Column(Modifier.then(if (editableOrder) Modifier.animateItem(placementSpec = androidx.compose.animation.core.tween(if (reducedMotion) 0 else 350), fadeInSpec = null, fadeOutSpec = null) else Modifier)
                        .graphicsLayer { alpha = if (reorder.drag?.let { it.dropping && item.key in it.keys } == true) 0f else 1f }) {
                        val itemMenu: @Composable (Boolean) -> Unit = { showButton ->
                            Box {
                                if (showButton) ActionIcon(R.drawable.ic_more, "Actions for " + item.title) { contextMenu = item.key }
                                DropdownMenu(contextMenu == item.key, { contextMenu = null }) {
                                    if (item.row != null) {
                                        ActionMenuItem("Information", onClick = { contextMenu = null; info = item.row.track })
                                        if (editTags != null) ActionMenuItem("Edit tags", onClick = { contextMenu = null; editTags(listOf(item.row.track)) {} })
                                        ActionMenuItem("Add to playlist", onClick = { contextMenu = null; addingSong = item.row.track })
                                        SongTap.entries.forEach { action -> ActionMenuItem(action.label(), onClick = {
                                            contextMenu = null; queues?.act(listOf(item.row.track), action, listOf(item.row.sourcePlaylistId))
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
                        item.groupHeading?.let { Text(it, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                        Column(Modifier.fillMaxWidth().padding(4.dp).selectionOutline(selectedCount > 0, partiallySelected, MaterialTheme.colorScheme.primary).testTag("media-${item.key}").combinedClickable(
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
                        ).padding(horizontal = if (style.presentation == Presentation.Compact) 4.dp else 12.dp, vertical = 8.dp)) {
                            if (style.presentation != Presentation.List) {
                                Box {
                                    MediaArtwork(item.coverUris, Modifier.fillMaxWidth().aspectRatio(1f))
                                    itemMenu(false)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (style.presentation == Presentation.List) MediaArtwork(item.coverUris, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(start = if (style.presentation == Presentation.List) 12.dp else 0.dp)) {
                                    Text((if (current) "▶ " else "") + item.title, style = if (style.presentation == Presentation.Compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    item.information(style)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                }
                                if (style.presentation == Presentation.List) itemMenu(true)
                            }
                        }

                    }
                }
            }
            if (dragHost == null) SongDragOverlay(reorder, reducedMotion)
            val playingIndex = result.items.indexOfFirst { it.row?.id == playingEntryId }.takeIf { playingEntryId != null && it >= 0 }?.plus(1)
            val showCurrent by remember(playingIndex) { derivedStateOf {
                playingIndex != null && gridState.layoutInfo.visibleItemsInfo.none { it.index == playingIndex }
            } }
            androidx.compose.animation.AnimatedVisibility(showCurrent && !session.active && reorder.drag == null, Modifier.align(Alignment.BottomCenter).padding(bottom = obscuredInset + 16.dp),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)) { it }) {
                FilledTonalButton(onClick = { playingIndex?.let { scrollScope.launch { gridState.easeToItem(it, reducedMotion) } } }) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_play), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("Current song")
                }
            }
            if (loadedResult != null) BrowseScrollbar(gridState, result.items, style.sort, Modifier.align(Alignment.CenterEnd).padding(bottom = obscuredInset))
            if (route.tab in setOf(MainTab.Library, MainTab.Playlists) && route.collection == null && !route.search)
                PlaylistCreationButton(library.snapshot, playlists, playlistController, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = obscuredInset + 16.dp))
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
}

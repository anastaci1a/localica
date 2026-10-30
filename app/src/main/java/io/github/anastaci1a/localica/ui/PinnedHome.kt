package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PinnedHome(library: LibraryUiState, playlists: PlaylistState, pins: List<MediaRef>,
    onSettings: () -> Unit, onBrowse: () -> Unit, onOpen: (BrowserItem, List<SongRow>) -> Unit,
    onPin: (MediaRef) -> Unit, onMove: (MediaRef, Int) -> Unit, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false,
    queryDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default, selectionVisible: Boolean = true,
    listBackEnabled: Boolean = false,
    playlistController: io.github.anastaci1a.localica.playlist.PlaylistController? = null, queues: io.github.anastaci1a.localica.queue.QueueController? = null,
    playerOpen: Boolean = false) {
    val sorts = rememberBrowseSortSnapshot()
    val cache = LocalBrowsePresentationCache.current
    val cached = remember(library.snapshot, playlists.playlists, pins, cache, sorts) { cache?.home(library.snapshot, playlists.playlists, pins, sorts) }
    val presented by produceState(sorts to cached.orEmpty(), library.snapshot, playlists.playlists, pins, sorts) {
        if (cached != null) { value = sorts to cached; return@produceState }
        val result = withContext(queryDispatcher) {
            val catalog = BrowseQueries.evaluate(library.snapshot, playlists.playlists,
                BrowseRoute(MainTab.Home, globalSearch = true), sorts = sorts).items.associateBy { it.ref }
            pins.mapNotNull { catalog[it] }
        }
        cache?.putHome(library.snapshot, playlists.playlists, pins, result, sorts)
        value = sorts to result
    }
    val items = presented.second
    val currentOrder = presented.first.sameCollections(sorts)
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    val songs = items.mapNotNull { it.row }
    BindSelectionScope(session, null, songs, selectionVisible)
    var info by remember { mutableStateOf<SongInformationRequest?>(null) }
    var menu by remember { mutableStateOf<MediaRef?>(null) }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val artworkTransit = remember { ArtworkTransit() }
    val carousel = androidx.compose.foundation.lazy.rememberLazyListState()
    val surroundingBackdrop = LocalFrostedBackdrop.current
    val listBackdrop = rememberFrostedBackdrop(items to surroundingBackdrop?.fallback,
        enabled = songs.isNotEmpty(), dark = surroundingBackdrop?.dark == true, retainRaster = false)
    val refreshBackdrop = LocalBackdropRefresh.current
    LaunchedEffect(items, scroll.isScrollInProgress, carousel.isScrollInProgress) {
        if (!scroll.isScrollInProgress && !carousel.isScrollInProgress) {
            kotlinx.coroutines.delay(150)
            refreshBackdrop()
            listBackdrop.refresh()
        }
    }
    val returnOwner = remember(library.snapshot, playlists.playlists, pins) { Any() }
    val returnToList = ListReturnActions(scrollToTopRequest, returnOwner, selectionVisible && !playerOpen && !session.active, listBackEnabled && !session.active,
        listReturnAction(scroll.canScrollBackward, scroll.isScrollInProgress, false), ready = {}, execute = { action ->
            if (action == ListReturnAction.ScrollTop) artworkTransit.during(!reducedMotion &&
                scroll.firstVisibleItemIndex > scroll.layoutInfo.visibleItemsInfo.size * 2) {
                scroll.easeToItem(0, reducedMotion, 500)
            }
        })
    CompositionLocalProvider(LocalArtworkTransit provides artworkTransit) {
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().controlScrollParent(scroll, ControlScrollAxis.Vertical)
        .pointerInput(scroll) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                returnToList.cancel()
            }
        }
        .recordFrostedBackdrop(listBackdrop, surroundingBackdrop).testTag("home-list"), state = scroll, contentPadding = PaddingValues(top = 16.dp, bottom = LocalBrowseObscuredInset.current + 16.dp)) {
        item { Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Your music", style = MaterialTheme.typography.headlineMedium)
            Text("${library.snapshot.tracks.size} songs in your library")
            ActionTextButton(onClick = onBrowse) { Text("Browse all music") }
            if (library.snapshot.folders.isEmpty()) ActionTextButton(onClick = onSettings) { Text("Add music folders") }
            Text("Pinned", style = MaterialTheme.typography.titleLarge)
            if (pins.isEmpty()) Text("Hold a song, album, or playlist and choose Pin to Home.")
        } }
        item { SelectionViewport { overscroll -> LazyRow(state = carousel, overscrollEffect = overscroll,
            modifier = Modifier.nestedScroll(HorizontalGestureOwner)
                .controlScrollParent(carousel, ControlScrollAxis.Horizontal).testTag("home-pins"), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item -> Box {
                val anchor = rememberGlassAnchor()
                val selectedCount = session.selectedCount(item)
                Column(Modifier.width(160.dp).clip(Corners.card).testTag("pin-${item.key}")
                    .selectionLens(selectedCount > 0, item.row == null && selectedCount > 0 && selectedCount < item.songs.size)
                    .combinedClickable(enabled = currentOrder, onClick = {
                        if (item.row != null && session.active) session.toggle(null, item.row)
                        else onOpen(item, songs)
                    },
                    onLongClickLabel = "Pinned item actions", onLongClick = { menu = item.ref })) {
                    MediaArtwork(item.coverUris, Modifier.fillMaxWidth().aspectRatio(1f).contextMenuAnchor(anchor, item.ref.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)), shape = artworkShape(item.ref.kind))
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(item.ref.kind.label, style = MaterialTheme.typography.bodySmall)
                }
                CollectionActions(currentOrder && menu == item.ref, anchor, item, playlists, playlistController, queues, pins, onPin, { menu = null },
                    onInformationPresented = { track, presentation -> info = SongInformationRequest(track, presentation) }, extra = {
                    ActionMenuItem("Move earlier", enabled = pins.indexOf(item.ref) > 0,
                        onClick = { onMove(item.ref, -1); menu = null })
                    ActionMenuItem("Move later", enabled = pins.indexOf(item.ref) < pins.lastIndex,
                        onClick = { onMove(item.ref, 1); menu = null })
                })
            } }
        } } }
    }
    CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
        FloatingReturnToPositionButton(up = true, label = "Return to top",
            visible = selectionVisible && !playerOpen && currentOrder && songs.isNotEmpty() && !session.active && scroll.canScrollBackward && !returnToList.pending,
            reducedMotion = reducedMotion,
            returning = returnToList.pending,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBrowseObscuredInset.current)) {
            returnToList(ListReturnAction.ScrollTop)
        }
    }
    }
    }
    info?.let { request -> TagEditorPresentationScope(request.presentation) {
    val track = request.track
    SongInformation(track, { info = null }, folders = library.snapshot.folders) { action ->
        collectionItemForTrack(action, track, library.snapshot, playlists.playlists, sorts)
            ?.let { info = null; onOpen(it, it.songs) }
    } } }

}

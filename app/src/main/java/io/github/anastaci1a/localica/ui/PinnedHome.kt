package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    playlistController: io.github.anastaci1a.localica.playlist.PlaylistController? = null, queues: io.github.anastaci1a.localica.queue.QueueController? = null) {
    val cache = LocalBrowsePresentationCache.current
    val cached = remember(library.snapshot, playlists.playlists, pins, cache) { cache?.home(library.snapshot, playlists.playlists, pins) }
    val items by produceState(cached.orEmpty(), library.snapshot, playlists.playlists, pins) {
        if (cached != null) { value = cached; return@produceState }
        val result = withContext(queryDispatcher) {
            val catalog = BrowseQueries.evaluate(library.snapshot, playlists.playlists,
                BrowseRoute(MainTab.Home, globalSearch = true)).items.associateBy { it.ref }
            pins.mapNotNull { catalog[it] }
        }
        cache?.putHome(library.snapshot, playlists.playlists, pins, result)
        value = result
    }
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    val songs = items.mapNotNull { it.row }
    BindSelectionScope(session, null, songs, selectionVisible)
    var info by remember { mutableStateOf<io.github.anastaci1a.localica.library.Track?>(null) }
    val editTags = LocalTagEdit.current
    var menu by remember { mutableStateOf<MediaRef?>(null) }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val artworkTransit = remember { ArtworkTransit() }
    val carousel = androidx.compose.foundation.lazy.rememberLazyListState()
    val refreshBackdrop = LocalBackdropRefresh.current
    LaunchedEffect(items, scroll.isScrollInProgress, carousel.isScrollInProgress) {
        if (!scroll.isScrollInProgress && !carousel.isScrollInProgress) {
            kotlinx.coroutines.delay(150)
            refreshBackdrop()
        }
    }
    val returnOwner = remember(library.snapshot, playlists.playlists, pins) { Any() }
    ListReturnActions(scrollToTopRequest, returnOwner, selectionVisible, listBackEnabled && !session.active,
        listReturnAction(scroll.canScrollBackward, scroll.isScrollInProgress, false), ready = {}, execute = { action ->
            if (action == ListReturnAction.ScrollTop) artworkTransit.during(!reducedMotion &&
                scroll.firstVisibleItemIndex > scroll.layoutInfo.visibleItemsInfo.size * 2) {
                scroll.easeToItem(0, reducedMotion, 500)
            }
        })
    CompositionLocalProvider(LocalArtworkTransit provides artworkTransit) {
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(top = 16.dp, bottom = LocalBrowseObscuredInset.current + 16.dp)) {
        item { Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Your music", style = MaterialTheme.typography.headlineMedium)
            Text("${library.snapshot.tracks.size} songs in your library")
            ActionTextButton(onClick = onBrowse) { Text("Browse all music") }
            if (library.snapshot.folders.isEmpty()) ActionTextButton(onClick = onSettings) { Text("Add music folders") }
            Text("Pinned", style = MaterialTheme.typography.titleLarge)
            if (pins.isEmpty()) Text("Hold a song, album, or playlist and choose Pin to Home.")
        } }
        item { SelectionViewport { overscroll -> LazyRow(state = carousel, overscrollEffect = overscroll,
            modifier = Modifier.nestedScroll(HorizontalGestureOwner).testTag("home-pins"), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item -> Box {
                val anchor = rememberGlassAnchor()
                val selectedCount = session.selectedCount(item)
                Column(Modifier.width(160.dp).clip(Corners.card).testTag("pin-${item.key}")
                    .selectionLens(selectedCount > 0, item.row == null && selectedCount > 0 && selectedCount < item.songs.size)
                    .combinedClickable(onClick = {
                        if (item.row != null && session.active) session.toggle(null, item.row)
                        else onOpen(item, songs)
                    },
                    onLongClickLabel = "Pinned item actions", onLongClick = { menu = item.ref })) {
                    MediaArtwork(item.coverUris, Modifier.fillMaxWidth().aspectRatio(1f).contextMenuAnchor(anchor, item.ref.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)), shape = artworkShape(item.ref.kind))
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(item.ref.kind.label, style = MaterialTheme.typography.bodySmall)
                }
                CollectionActions(menu == item.ref, anchor, item, playlists, playlistController, queues, pins, onPin, { menu = null }, onInformation = { info = it }, extra = {
                    ActionMenuItem("Move earlier", enabled = pins.indexOf(item.ref) > 0,
                        onClick = { onMove(item.ref, -1); menu = null })
                    ActionMenuItem("Move later", enabled = pins.indexOf(item.ref) < pins.lastIndex,
                        onClick = { onMove(item.ref, 1); menu = null })
                })
            } }
        } } }
    }
    }
    info?.let { track -> SongInformation(track, { info = null }, { editTags?.invoke(listOf(track)) {} }) { action ->
        val tab = when (action) { PlayerAction.Album -> MainTab.Albums; PlayerAction.Artist -> MainTab.Artists
            PlayerAction.AlbumArtist -> MainTab.AlbumArtists; else -> MainTab.Folders }
        BrowseQueries.evaluate(library.snapshot, playlists.playlists, BrowseRoute(tab)).items
            .firstOrNull { item -> item.songs.any { it.track.id == track.id } }?.let { info = null; onOpen(it, it.songs) }
    } }

}

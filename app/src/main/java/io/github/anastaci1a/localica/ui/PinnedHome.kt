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
    onPin: (MediaRef) -> Unit, onMove: (MediaRef, Int) -> Unit, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false) {
    val items by produceState(emptyList<BrowserItem>(), library.snapshot, playlists.playlists, pins) {
        value = withContext(Dispatchers.Default) {
            val catalog = BrowseQueries.evaluate(library.snapshot, playlists.playlists,
                BrowseRoute(MainTab.Home, globalSearch = true)).items.associateBy { it.ref }
            pins.mapNotNull { catalog[it] }
        }
    }
    var menu by remember { mutableStateOf<MediaRef?>(null) }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val carousel = androidx.compose.foundation.lazy.rememberLazyListState()
    val refreshBackdrop = LocalBackdropRefresh.current
    LaunchedEffect(items, scroll.isScrollInProgress, carousel.isScrollInProgress) {
        if (!scroll.isScrollInProgress && !carousel.isScrollInProgress) {
            kotlinx.coroutines.delay(150)
            refreshBackdrop()
        }
    }
    var consumedScrollRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopRequest) {
        if (consumedScrollRequest != scrollToTopRequest) {
            consumedScrollRequest = scrollToTopRequest
            scroll.easeToItem(0, reducedMotion, 500)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(top = 16.dp, bottom = LocalBrowseObscuredInset.current + 16.dp)) {
        item { Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Your music", style = MaterialTheme.typography.headlineMedium)
            Text("${library.snapshot.tracks.size} songs in your library")
            TextButton(onClick = onBrowse) { Text("Browse all music") }
            if (library.snapshot.folders.isEmpty()) TextButton(onClick = onSettings) { Text("Add music folders") }
            Text("Pinned", style = MaterialTheme.typography.titleLarge)
            if (items.isEmpty()) Text("Hold a song, album, or playlist and choose Pin to Home.")
        } }
        item { LazyRow(state = carousel, modifier = Modifier.nestedScroll(HorizontalGestureOwner), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item -> Box {
                Column(Modifier.width(160.dp).clip(Corners.card).combinedClickable(onClick = { onOpen(item, items.mapNotNull { it.row }) },
                    onLongClickLabel = "Pinned item actions", onLongClick = { menu = item.ref })) {
                    MediaArtwork(item.coverUris, Modifier.fillMaxWidth().aspectRatio(1f), shape = artworkShape(item.ref.kind))
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(item.ref.kind.label, style = MaterialTheme.typography.bodySmall)
                }
                GlassMenu(menu == item.ref, rememberGlassAnchor(), { menu = null }) {
                    ActionMenuItem("Move earlier", enabled = pins.indexOf(item.ref) > 0,
                        onClick = { onMove(item.ref, -1); menu = null })
                    ActionMenuItem("Move later", enabled = pins.indexOf(item.ref) < pins.lastIndex,
                        onClick = { onMove(item.ref, 1); menu = null })
                    ActionMenuItem("Unpin", onClick = { onPin(item.ref); menu = null })
                }
            } }
        } }
    }
}

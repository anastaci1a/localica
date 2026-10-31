package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import kotlinx.coroutines.*

/** Identity freshness avoids comparing entire library or playlist contents on the UI thread. */
internal class CollectionLookupSource(val snapshot: LibrarySnapshot, val playlists: List<ManualPlaylist>,
    val sorts: BrowseSortSnapshot) {
    override fun equals(other: Any?) = other is CollectionLookupSource && snapshot === other.snapshot &&
        playlists === other.playlists && sorts == other.sorts
    override fun hashCode() = 31 * (31 * System.identityHashCode(snapshot) + System.identityHashCode(playlists)) + sorts.hashCode()
}

internal class CollectionLookupHandle(val cancel: () -> Unit,
    val open: (PlayerAction, Track, () -> Boolean) -> Job)

@Composable
internal fun rememberCollectionLookup(snapshot: LibrarySnapshot, playlists: List<ManualPlaylist>,
    sorts: BrowseSortSnapshot, dispatcher: CoroutineDispatcher = Dispatchers.Default,
    onOpen: (BrowserItem) -> Unit): CollectionLookupHandle {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val requests = remember { LatestUiRequest() }
    val source = CollectionLookupSource(snapshot, playlists, sorts)
    val latestSource = rememberUpdatedState(source)
    val latestOpen = rememberUpdatedState(onOpen)
    DisposableEffect(requests, source) { onDispose { requests.cancel() } }
    return CollectionLookupHandle(requests::cancel) { action, track, stillCurrent ->
        val requestedSource = latestSource.value
        requests.submit(scope, dispatcher, { latestSource.value == requestedSource && stillCurrent() }, {
            val context = currentCoroutineContext()
            collectionItemForTrack(action, track, requestedSource.snapshot, requestedSource.playlists,
                requestedSource.sorts) { context.ensureActive() }
        }, { latestOpen.value(it) })
    }
}

@Composable
internal fun rememberOwnedCollectionLookup(owner: Any, snapshot: LibrarySnapshot,
    playlists: List<ManualPlaylist>, sorts: BrowseSortSnapshot,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    onOpen: (BrowserItem) -> Unit): CollectionLookupHandle = key(owner) {
    rememberCollectionLookup(snapshot, playlists, sorts, dispatcher, onOpen)
}

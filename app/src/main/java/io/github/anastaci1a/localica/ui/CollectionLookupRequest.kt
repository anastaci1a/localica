package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import kotlinx.coroutines.*

/** Owned by one UI lifetime; submission, cancellation and publication stay on its scope thread. */
internal class CollectionLookupRequests {
    private var current: Any? = null
    private var job: Job? = null

    fun cancel() {
        current = null
        job?.cancel()
        job = null
    }

    fun <T : Any> submit(scope: CoroutineScope, dispatcher: CoroutineDispatcher,
        stillCurrent: () -> Boolean, query: suspend () -> T?, publish: (T) -> Unit): Job {
        cancel()
        val request = Any()
        current = request
        val submitted = scope.launch {
            val result = withContext(dispatcher) { query() }
            ensureActive()
            if (result != null && current === request && stillCurrent()) publish(result)
        }
        job = submitted
        return submitted
    }
}

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
    val requests = remember { CollectionLookupRequests() }
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

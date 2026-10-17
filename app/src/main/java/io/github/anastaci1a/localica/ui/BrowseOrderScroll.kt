package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef

/** The committed immutable entries identity, independent of cursor-only collection copies. */
internal class BrowseOrderGeneration(val collection: MediaRef, val entries: Any, val order: SavedOrder) {
    fun matches(source: BrowseSourceRevision): Boolean = when (collection.kind) {
        MediaKind.Queue -> source.queues.firstOrNull { it.id == collection.id }?.let {
            it.entries === entries && it.order == order
        } == true
        MediaKind.Playlist -> source.playlists.firstOrNull { it.id == collection.id }?.let {
            it.entries === entries && it.order == order
        } == true
        else -> false
    }
}

/** Identity tokens belong to one action in one route/popup lifetime. */
internal class BrowseOrderScrollToken internal constructor(val owner: Any, val route: BrowseRoute)

internal class BrowseOrderScrollRequest internal constructor(
    val token: BrowseOrderScrollToken,
    val committed: BrowseOrderGeneration,
    val targetOccurrenceId: String?,
    val followCurrent: Boolean,
) {
    val owner get() = token.owner
    val route get() = token.route

    /** Call only after presentation is no longer pending. Null means wait; zero means top.
     * All song indices account for the full-span header. */
    fun targetIndex(presentation: PresentedBrowse?, currentOwner: Any, currentRoute: BrowseRoute,
        targetOccurrenceId: String? = this.targetOccurrenceId): Int? {
        if (presentation == null || owner !== currentOwner || route != currentRoute || presentation.route != route ||
            !committed.matches(presentation.source)) return null
        val index = targetOccurrenceId?.let { id -> presentation.result.items.indexOfFirst { it.row?.id == id } } ?: -1
        return if (index < 0) 0 else index + 1
    }
}

/** Main-thread coordinator. Call cancel on touch, route hiding, or popup ownership loss.
 * Observe revision and request together to cancel both source waits and running scroll motion.
 * Actual change comes from the serialized command receipt, never a potentially stale UI snapshot. */
internal class BrowseOrderScrollCoordinator {
    var revision by mutableLongStateOf(0L)
        private set
    var request by mutableStateOf<BrowseOrderScrollRequest?>(null)
        private set
    private var activeToken: BrowseOrderScrollToken? = null

    fun begin(owner: Any, route: BrowseRoute): BrowseOrderScrollToken {
        cancel()
        return BrowseOrderScrollToken(owner, route).also { activeToken = it }
    }

    fun complete(token: BrowseOrderScrollToken, currentOwner: Any, currentRoute: BrowseRoute,
        changed: Boolean, committed: BrowseOrderGeneration?, targetOccurrenceId: String?, followCurrent: Boolean = false): Boolean {
        if (activeToken !== token) return false
        activeToken = null
        if (token.owner !== currentOwner || token.route != currentRoute || !changed || committed == null ||
            token.route.collection != committed.collection || token.route.globalSearch ||
            (committed.collection.kind != MediaKind.Queue && committed.collection.kind != MediaKind.Playlist)) return false
        request = BrowseOrderScrollRequest(token, committed, targetOccurrenceId, followCurrent)
        return true
    }

    fun isCurrent(value: BrowseOrderScrollRequest, currentOwner: Any, currentRoute: BrowseRoute): Boolean =
        request === value && value.owner === currentOwner && value.route == currentRoute

    fun finish(value: BrowseOrderScrollRequest) {
        if (request === value) cancel()
    }

    /** Dismissal rejects pending receipts without interrupting an already accepted follow. */
    fun cancelPending() {
        activeToken = null
    }

    fun cancel() {
        cancelPending()
        request = null
        revision++
    }
}

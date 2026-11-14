package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.navigation.MediaKind
import java.util.UUID

/** Collections own their occurrences; a song row inherits the list containing it. */
internal data class SongSelectionTarget(val source: MediaRef?, val rows: List<SongRow>)

internal fun BrowserItem.selectionTarget(containingSource: MediaRef? = null) =
    if (row == null) SongSelectionTarget(ref, songs) else SongSelectionTarget(containingSource, listOf(row))

internal data class SelectedSong(val id: String = UUID.randomUUID().toString(), val source: MediaRef?, val row: SongRow)

/** Library rows use the same ID in every projection; mutable lists own persisted entry IDs.
 * Keep cross-recognition here: library selections do not select playlist or queue entries.
 */
private fun selectionOccurrence(source: MediaRef?, rowId: String): Pair<MediaRef?, String> =
    source?.takeIf { it.kind == MediaKind.Playlist || it.kind == MediaKind.Queue } to rowId

/** Activity session only: configuration changes retain it; fresh sessions never restore it from disk. */
internal class SessionSelection : ViewModel() {
    private var identity by mutableStateOf<Any?>(null)
    val active: Boolean get() = identity != null
    var collapsed by mutableStateOf(false)
    private var presentationHold by mutableStateOf<Any?>(null)
    val panelVisible: Boolean get() = active && presentationHold == null

    /** Membership starts at recognition; its contact or acquired drag owns the panel hold. */
    fun beginPresentationHold(owner: Any = Any()): Any? {
        if (active || presentationHold != null) return null
        return owner.also { presentationHold = it }
    }
    fun endPresentationHold(token: Any?) {
        if (token != null && presentationHold === token) presentationHold = null
    }
    fun adoptPresentationHold(owner: Any): Pair<() -> Unit, () -> Unit> {
        val selected = identity
        val transaction = Any()
        if (presentationHold == owner) presentationHold = transaction
        return { if (identity === selected) clear() } to { endPresentationHold(transaction) }
    }
    val entries = mutableStateListOf<SelectedSong>()
    private val occurrences by derivedStateOf { entries.mapTo(hashSetOf()) { selectionOccurrence(it.source, it.row.id) } }
    var visible: List<SongRow> = emptyList()
    var source: MediaRef? = null
    fun contains(source: MediaRef?, id: String) = selectionOccurrence(source, id) in occurrences
    fun selectedCount(item: BrowserItem, containingSource: MediaRef? = null): Int =
        item.row?.let { if (contains(containingSource, it.id)) 1 else 0 }
            ?: item.songs.count { contains(item.ref, it.id) }
    fun select(source: MediaRef?, rows: List<SongRow>) {
        if (!active) { collapsed = false; identity = Any() }
        val known = occurrences.toMutableSet()
        entries.addAll(rows.mapNotNull { row ->
            if (known.add(selectionOccurrence(source, row.id))) SelectedSong(source = source, row = row) else null
        })
    }
    fun toggle(source: MediaRef?, row: SongRow) {
        val occurrence = selectionOccurrence(source, row.id)
        if (occurrence in occurrences) entries.removeAll { selectionOccurrence(it.source, it.row.id) == occurrence }
        else select(source, listOf(row))
    }
    fun range() {
        val indices = visible.indices.filter { contains(source, visible[it].id) }
        if (indices.isNotEmpty()) select(source, visible.subList(indices.first(), indices.last() + 1))
    }
    fun clear() { entries.clear(); identity = null; collapsed = false; presentationHold = null }
}
internal val LocalSessionSelection = staticCompositionLocalOf<SessionSelection?> { null }

/** Only the settled, uncovered list owns All/Range; offscreen pages retain membership. */
@Composable
internal fun BindSelectionScope(session: SessionSelection, source: MediaRef?, rows: List<SongRow>, active: Boolean) {
    SideEffect { if (active) { session.visible = rows; session.source = source } }
}

@Composable
internal fun SessionSelectionPanel(session: SessionSelection, queues: io.github.anastaci1a.localica.queue.QueueController?,
    playlists: io.github.anastaci1a.localica.playlist.PlaylistController?,
    playlistState: io.github.anastaci1a.localica.playlist.PlaylistState, dragging: Boolean = false) {
    var retained by remember { mutableStateOf(session.entries.toList()) }
    if (session.active) SideEffect { retained = session.entries.toList() }
    val entries = if (session.active) session.entries.toList() else retained
    val rows = entries.map { it.row.copy(id = it.id) }
    val source = entries.map { it.source }.distinct().singleOrNull()
    val remove: (((Boolean) -> Unit) -> Unit)? = when {
        source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Playlist -> ({ complete ->
            playlists?.removeEntries(source.id, session.entries.map { it.row.id }.toSet(), complete) })
        source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Queue && queues != null -> ({ complete ->
            queues.removeEntries(session.entries.map { it.row.id }.toSet(), source.id, complete) })
        else -> null
    }
    // Collapsed and dragging share the minimized pill (#534); dragging cannot expand it.
    SelectionActions(rows, rows.map { it.id }.toSet(), { session.clear() }, queues, playlists, playlistState, remove,
        if (source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Queue) "Remove from queue" else "Remove from playlist",
        onAll = { session.select(session.source, session.visible) }, onRange = session::range,
        onCollapse = { session.collapsed = true }, minimized = session.collapsed || dragging,
        onExpand = { session.collapsed = false }.takeUnless { dragging })
}

@Composable
internal fun SelectionPanelVisibility(visible: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    FloatingSelectionPanel(rememberFloatingSelectionPanel(visible, reducedMotion), modifier, content)
}

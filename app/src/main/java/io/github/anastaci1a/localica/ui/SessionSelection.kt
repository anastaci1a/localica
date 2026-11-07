package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.navigation.MediaRef
import java.util.UUID

/** Collections own their occurrences; a song row inherits the list containing it. */
internal data class SongSelectionTarget(val source: MediaRef?, val rows: List<SongRow>)

internal fun BrowserItem.selectionTarget(containingSource: MediaRef? = null) =
    if (row == null) SongSelectionTarget(ref, songs) else SongSelectionTarget(containingSource, listOf(row))

internal data class SelectedSong(val id: String = UUID.randomUUID().toString(), val source: MediaRef?, val row: SongRow,
    val sourceRowIds: Set<String> = setOf(row.id))

/** Activity session only: configuration changes retain it; fresh sessions never restore it from disk. */
internal class SessionSelection : ViewModel() {
    var active by mutableStateOf(false)
        private set
    var collapsed by mutableStateOf(false)
    private var presentationHold by mutableStateOf<Any?>(null)
    val panelVisible: Boolean get() = active && presentationHold == null

    /** Membership starts at recognition; a new panel waits for its initiating pointer. */
    fun beginPresentationHold(): Any? {
        if (active || presentationHold != null) return null
        return Any().also { presentationHold = it }
    }
    fun endPresentationHold(token: Any?) {
        if (token != null && presentationHold === token) presentationHold = null
    }
    val entries = mutableStateListOf<SelectedSong>()
    val trackIds by derivedStateOf { entries.mapTo(hashSetOf()) { it.row.track.id } }
    var visible: List<SongRow> = emptyList()
    var source: MediaRef? = null
    fun contains(row: SongRow) = row.track.id in trackIds
    @Suppress("UNUSED_PARAMETER") // Source provenance affects edits, never membership.
    fun selectedCount(item: BrowserItem, containingSource: MediaRef? = null): Int =
        item.row?.let { if (contains(it)) 1 else 0 }
            ?: item.songs.count { it.track.id in trackIds }
    fun select(source: MediaRef?, rows: List<SongRow>) {
        if (!active) collapsed = false
        active = true
        // Keep one action input per song, with the first source's occurrence IDs
        // available for mutable-list removal and reordering.
        val scope = if (this.source == source) visible else emptyList()
        val occurrences = (rows + scope).groupBy { it.track.id }
        val indices = entries.withIndex().associate { it.value.row.track.id to it.index }
        for (row in rows.distinctBy { it.track.id }) {
            val ids = occurrences.getValue(row.track.id).mapTo(linkedSetOf()) { it.id }
            val index = indices[row.track.id]
            if (index == null) entries.add(SelectedSong(source = source, row = row, sourceRowIds = ids))
            else if (entries[index].source == source) {
                val previous = entries[index]
                entries[index] = previous.copy(sourceRowIds = previous.sourceRowIds + ids)
            }
        }
    }
    fun toggle(source: MediaRef?, row: SongRow) {
        if (contains(row)) entries.removeAll { it.row.track.id == row.track.id }
        else select(source, listOf(row))
    }
    fun range() {
        val indices = visible.indices.filter { contains(visible[it]) }
        if (indices.isNotEmpty()) select(source, visible.subList(indices.first(), indices.last() + 1))
    }
    fun clear() { entries.clear(); active = false; collapsed = false }
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
            playlists?.removeEntries(source.id, session.entries.flatMap { it.sourceRowIds }.toSet(), complete) })
        source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Queue && queues != null -> ({ complete ->
            queues.removeEntries(session.entries.flatMap { it.sourceRowIds }.toSet(), source.id, complete) })
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

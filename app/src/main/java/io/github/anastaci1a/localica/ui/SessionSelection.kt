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

internal data class SelectedSong(val id: String = UUID.randomUUID().toString(), val source: MediaRef?, val row: SongRow)

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
    private val occurrences by derivedStateOf { entries.mapTo(hashSetOf()) { it.source to it.row.id } }
    val trackIds by derivedStateOf { entries.mapTo(hashSetOf()) { it.row.track.id } }
    var visible: List<SongRow> = emptyList()
    var source: MediaRef? = null
    fun contains(source: MediaRef?, id: String) = source to id in occurrences
    fun selectedCount(item: BrowserItem, containingSource: MediaRef? = null): Int =
        item.row?.let { if (contains(containingSource, it.id)) 1 else 0 }
            ?: item.songs.count { it.track.id in trackIds }
    fun select(source: MediaRef?, rows: List<SongRow>) {
        if (!active) collapsed = false
        active = true
        val known = occurrences.toMutableSet()
        entries.addAll(rows.mapNotNull { row ->
            if (known.add(source to row.id)) SelectedSong(source = source, row = row) else null
        })
    }
    fun toggle(source: MediaRef?, row: SongRow) {
        if (contains(source, row.id)) entries.removeAll { it.source == source && it.row.id == row.id }
        else select(source, listOf(row))
    }
    fun range() {
        val indices = visible.indices.filter { contains(source, visible[it].id) }
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
    playlistState: io.github.anastaci1a.localica.playlist.PlaylistState) {
    var retained by remember { mutableStateOf(session.entries.toList()) }
    if (session.active) SideEffect { retained = session.entries.toList() }
    val entries = if (session.active) session.entries.toList() else retained
    if (session.collapsed) GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_queue,
        "Selection: " + session.entries.size, onClick = { session.collapsed = false }) else {
        val rows = entries.map { it.row.copy(id = it.id) }
        val source = entries.map { it.source }.distinct().singleOrNull()
        val remove: (((Boolean) -> Unit) -> Unit)? = when {
            source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Playlist -> ({ complete ->
                playlists?.removeEntries(source.id, session.entries.map { it.row.id }.toSet(), complete) })
            source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Queue && queues != null -> ({ complete ->
                queues.removeEntries(session.entries.map { it.row.id }.toSet(), source.id, complete) })
            else -> null
        }
        SelectionActions(rows, rows.map { it.id }.toSet(), { session.clear() }, queues, playlists, playlistState, remove,
            if (source?.kind == io.github.anastaci1a.localica.navigation.MediaKind.Queue) "Remove from queue" else "Remove from playlist",
            onAll = { session.select(session.source, session.visible) }, onRange = session::range,
            onCollapse = { session.collapsed = true })
    }
}

@Composable
internal fun SelectionPanelVisibility(visible: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val duration = if (reducedMotion) 0 else 220
    androidx.compose.animation.AnimatedVisibility(visible, modifier,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(duration)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(duration)) { it / 2 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(duration)) +
            androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(duration)) { it / 2 }) { content() }
}

package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.browse.BrowseCellGeometry
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.MediaRef
import kotlinx.coroutines.*

@Immutable
internal data class SongDragPresentation(val style: BrowseStyle, val cellGeometry: BrowseCellGeometry,
    val colors: androidx.compose.material3.ColorScheme?, val ink: androidx.compose.ui.graphics.Color)

/** Song-specific appearance and selection remain adapters around the shared drag transaction. */
@Stable
internal class SongDragState(grid: LazyGridState, scope: CoroutineScope) :
    KeyedDragState<BrowserItem, SongDragPresentation>(grid, scope, { it.key }) {
    var style = BrowseStyle()
    var cellGeometry = BrowseCellGeometry(1)
    var colors: androidx.compose.material3.ColorScheme? = null
    var ink = androidx.compose.ui.graphics.Color.Unspecified
    override fun capturePresentation() = SongDragPresentation(style, cellGeometry, colors, ink)
}

/** Song selection adapts the shared whole-row long-press gesture. */
@Composable
internal fun Modifier.songDrag(state: SongDragState, enabled: Boolean, source: MediaRef?, selection: SessionSelection,
    reducedMotion: Boolean, trailingActions: Boolean, commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    return sharedKeyedDrag(state, enabled, reducedMotion, ownerKey = source to trailingActions,
        itemAt = { point ->
            state.hit(point)?.takeIf { item ->
                item.row != null && (!trailingActions || state.bounds()[item.key]?.let { point.x <= it.right - 48 * density } == true)
            }
        }, onHold = { item -> selection.select(source, listOf(requireNotNull(item.row))) },
        keysFor = {
            selection.entries.takeIf { entries -> entries.all { it.source == source && state.items.any { item -> item.key == it.row.id } } }
                ?.map { it.row.id }?.toSet()
        }, onCommitted = selection::clear, commit = commit)
}

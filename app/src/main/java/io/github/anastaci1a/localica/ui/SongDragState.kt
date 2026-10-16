package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
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

/** Own the held pointer at Initial pass so item clicks/scrolling cannot take over mid-drag. */
internal fun Modifier.songDrag(state: SongDragState, enabled: Boolean, source: MediaRef?, selection: SessionSelection,
    reducedMotion: Boolean, trailingActions: Boolean, commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit): Modifier = pointerInput(state, source, reducedMotion, trailingActions, enabled) {
    // Keep this ancestor node installed: removing it cancels every descendant's
    // hit path, including a pinch that temporarily makes the browse query pending.
    // Keying enabled cancels only our own active drag when availability changes.
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled) return@awaitEachGesture
        val item = state.hit(down.position) ?: return@awaitEachGesture
        val row = item.row ?: return@awaitEachGesture
        // The trailing action button keeps its own press/hold interaction.
        val bounds = state.bounds()[item.key] ?: return@awaitEachGesture
        if (trailingActions && down.position.x > bounds.right - 48 * density) return@awaitEachGesture
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed || change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (held != null) return@awaitEachGesture
        selection.select(source, listOf(row))
        var moving = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) break
                if (!change.pressed) {
                    // Compose delivers pointer cancellation as an already-consumed release.
                    val canceled = change.isConsumed
                    change.consume()
                    if (moving) {
                        if (!canceled) state.move(change.position)
                        state.finish(canceled || state.drag?.overCancel == true, reducedMotion, selection::clear, commit)
                    }
                    break
                }
                change.consume()
                if (!moving && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    val selected = selection.entries.takeIf { entries -> entries.all { it.source == source && state.items.any { item -> item.key == it.row.id } } }
                        ?.map { it.row.id }?.toSet()
                    if (selected != null) moving = state.begin(item, selected, down.position)
                }
                if (moving) state.move(change.position)
            }
        } finally { if (moving && state.drag?.dropping == false) state.finish(true, reducedMotion, selection::clear, commit) }
    }
}

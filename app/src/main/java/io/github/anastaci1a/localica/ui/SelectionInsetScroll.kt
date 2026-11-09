package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One reveal owns one cancellable scroll. A fresh touch retires it even before dragging. */
internal class SelectionInsetScroll {
    internal var job: Job? = null
    fun interrupt() { job?.cancel(); job = null }
}

/** Follow measured padding, so the lazy viewport has admitted each pixel before scrolling it.
 * Dismissal removes that same padding progressively; lazy layout clamps only the occupied tail.
 * Selection affects eligibility, never distance: the only destination is the full panel inset.
 */
@Composable
internal fun SelectionInsetScroll(grid: LazyGridState, panel: FloatingSelectionPanelState?,
    ownership: BrowseScrollOwnership, baseInset: () -> Float, enabled: () -> Boolean, selected: (Any) -> Boolean): SelectionInsetScroll {
    val controller = remember(grid) { SelectionInsetScroll() }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSelected by rememberUpdatedState(selected)
    val currentBaseInset by rememberUpdatedState(baseInset)
    val density = LocalDensity.current
    LaunchedEffect(grid, panel, density) {
        if (panel == null) return@LaunchedEffect
        coroutineScope {
            var restingLayout = grid.layoutInfo
            var restingAtBottom = !grid.canScrollForward && grid.canScrollBackward
            snapshotFlow { Triple(panel.visible, grid.layoutInfo, grid.canScrollForward to grid.canScrollBackward) }
                .map { (visible, layout, directions) ->
                    if (!visible) {
                        restingLayout = layout
                        restingAtBottom = !directions.first && directions.second
                    }
                    visible
                }.distinctUntilChanged().collectLatest { visible ->
                controller.interrupt()
                if (!visible || !currentEnabled()) return@collectLatest
                val initial = restingLayout
                val atBottom = restingAtBottom
                val padding = initial.afterContentPadding
                val chrome = currentBaseInset()
                val selectedBottom = initial.visibleItemsInfo.filter { currentSelected(it.key) }
                    .maxOfOrNull { it.offset.y + it.size.height }
                controller.job = launch {
                    snapshotFlow { panel.height }.first { it.value > 0f }
                    val inset = with(density) { (panel.height + FloatingSurfaceGap).toPx() }
                    val covered = selectedBottom != null && selectedBottom > initial.viewportSize.height -
                        (chrome + inset)
                    if (!atBottom && !covered) return@launch
                    ownership.during {
                        grid.scroll {
                            var consumed = 0f
                            snapshotFlow { Triple(grid.layoutInfo.afterContentPadding, panel.progress, panel.visible) }
                                .first { (measured, progress, shown) ->
                                    val destination = (inset * progress.coerceIn(0f, 1f)).roundToInt().toFloat()
                                    val admitted = (measured - padding).coerceAtLeast(0).toFloat()
                                    consumed += scrollBy(minOf(destination, admitted) - consumed)
                                    !shown || progress >= 1f && admitted >= inset.roundToInt()
                                }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(controller) {
        snapshotFlow { currentEnabled() }.collect { if (!it) controller.interrupt() }
    }
    DisposableEffect(controller) { onDispose { controller.interrupt() } }
    return controller
}

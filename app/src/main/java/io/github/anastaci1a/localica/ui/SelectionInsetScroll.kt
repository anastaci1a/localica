package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One reveal owns one cancellable scroll. A fresh touch retires it even before dragging. */
internal class SelectionInsetScroll {
    internal var job: Job? = null
    fun interrupt() { job?.cancel(); job = null }
}

/** Follow measured padding, so lazy layout admits each pixel before scrolling it. Removing
 * that padding progressively clamps only the occupied tail. Membership determines admission,
 * never distance. Reveal generations survive density/font-scale changes without replay.
 */
@Composable
internal fun SelectionInsetScroll(grid: LazyGridState, panel: FloatingSelectionPanelState?,
    ownership: BrowseScrollOwnership, baseInset: () -> Float, enabled: () -> Boolean, selected: (Any) -> Boolean): SelectionInsetScroll {
    val controller = remember(grid) { SelectionInsetScroll() }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSelected by rememberUpdatedState(selected)
    val currentBaseInset by rememberUpdatedState(baseInset)
    val density by rememberUpdatedState(LocalDensity.current)
    LaunchedEffect(grid, panel) {
        if (panel == null) return@LaunchedEffect
        coroutineScope {
            var restingLayout = grid.layoutInfo
            var restingAtBottom = !grid.canScrollForward && grid.canScrollBackward
            var restingClearance = with(density) { panel.clearance.toPx().roundToInt() }
            snapshotFlow {
                Triple(panel.revealGeneration, panel.visible,
                    Triple(grid.layoutInfo, !grid.canScrollForward && grid.canScrollBackward,
                        with(density) { panel.clearance.toPx().roundToInt() }))
            }.map { (generation, visible, measured) ->
                if (!visible) {
                    restingLayout = measured.first
                    restingAtBottom = measured.second
                    restingClearance = measured.third
                }
                generation
            }.distinctUntilChanged().drop(1).collectLatest { generation ->
                controller.interrupt()
                // The initial generation is skipped even for a previously used shared panel.
                // A replacement host never resumes its retired owner's reveal.
                if (generation == 0L || !panel.visible || !currentEnabled()) return@collectLatest
                val initial = restingLayout
                val atBottom = restingAtBottom
                val padding = initial.afterContentPadding
                val initialClearance = restingClearance
                val chrome = currentBaseInset()
                val selectedBottom = initial.visibleItemsInfo.filter { currentSelected(it.key) }
                    .maxOfOrNull { it.offset.y + it.size.height }
                controller.job = launch {
                    snapshotFlow { panel.height }.first { it.value > 0f }
                    fun covered(): Boolean = selectedBottom != null && selectedBottom > initial.viewportSize.height -
                        chrome - with(density) { (panel.height + FloatingSurfaceGap).toPx() }
                    if (!atBottom) {
                        // A growing measured pill may become eligible during the same reveal.
                        snapshotFlow { covered() || panel.progress >= 1f }.first { it }
                        if (!covered()) return@launch
                    }
                    ownership.during {
                        grid.scroll {
                            var consumed = 0f
                            snapshotFlow {
                                Triple(grid.layoutInfo.afterContentPadding,
                                    with(density) { panel.clearance.toPx().roundToInt() }, panel.progress)
                            }.first { (measured, clearance, progress) ->
                                val destination = (clearance - initialClearance).coerceAtLeast(0).toFloat()
                                val admitted = (measured - padding).coerceAtLeast(0).toFloat()
                                consumed += scrollBy(minOf(destination, admitted) - consumed)
                                progress >= 1f && admitted >= destination
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(controller, panel) {
        snapshotFlow { panel?.visible == true && currentEnabled() }.collect { if (!it) controller.interrupt() }
    }
    DisposableEffect(controller) { onDispose { controller.interrupt() } }
    return controller
}

package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.ui.components.SelectionInsetSource
import io.github.anastaci1a.localica.ui.components.rememberSelectionInsetScroll
import kotlin.math.roundToInt

internal typealias SelectionInsetScroll = io.github.anastaci1a.localica.ui.components.SelectionInsetScroll

/** Legacy browse hosts adapt their panel to the shared component's read-only geometry. */
@Composable
internal fun SelectionInsetScroll(grid: LazyGridState, panel: FloatingSelectionPanelState?,
    ownership: BrowseScrollOwnership, baseInset: () -> Float, enabled: () -> Boolean, selected: (Any) -> Boolean): SelectionInsetScroll {
    val density by rememberUpdatedState(LocalDensity.current)
    val source = remember(panel) { panel?.let { owner -> object : SelectionInsetSource {
        override val generation get() = owner.revealGeneration
        override val visible get() = owner.visible
        override val clearance get() = with(density) { owner.clearance.toPx().roundToInt() }
        override val fullInset get() = with(density) { (owner.height + FloatingSurfaceGap).toPx() }
        override val measured get() = owner.height.value > 0f
        override val progress get() = owner.progress
    } } }
    return rememberSelectionInsetScroll(grid, source, { block -> ownership.during(block) }, baseInset, enabled, selected)
}

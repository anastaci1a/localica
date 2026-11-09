package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectionInsetScrollTest {
    @get:Rule val compose = localicaComposeRule()
    private var visible by mutableStateOf(false)
    private var selection = -1
    private lateinit var grid: LazyGridState
    private lateinit var panel: FloatingSelectionPanelState
    private lateinit var motion: SelectionInsetScroll
    private var pixels = 1f
    private var columnCount = 1

    private fun fixture(columns: Int = 1, reduced: Boolean = false) {
        columnCount = columns
        compose.setContent {
            pixels = LocalDensity.current.density
            grid = rememberLazyGridState()
            panel = rememberFloatingSelectionPanel(visible, reduced)
            motion = SelectionInsetScroll(grid, panel, remember { BrowseScrollOwnership() },
                baseInset = { 40f * pixels }, enabled = { true }, selected = { it == selection })
            Box(Modifier.size(320.dp, 480.dp)) {
                LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("inset-list"), grid,
                    contentPadding = PaddingValues(bottom = 40.dp + panel.clearance)) {
                    items(80, key = { it }) { Box(Modifier.height(60.dp).fillMaxWidth().testTag("inset-$it")) }
                }
                FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
                    Box(Modifier.height(80.dp).fillMaxWidth().testTag("inset-panel"))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun position(): Float = (grid.firstVisibleItemIndex / columnCount).toFloat() * 60f * pixels + grid.firstVisibleItemScrollOffset
    private fun bottom() {
        compose.onNodeWithTag("inset-list").performScrollToIndex(79)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(grid.canScrollForward) }
    }
    private fun verify(atBottom: Boolean, covered: Boolean, columns: Int = 1, reduced: Boolean = false) {
        fixture(columns, reduced)
        if (atBottom) bottom() else {
            compose.onNodeWithTag("inset-list").performScrollToIndex(20)
            compose.runOnIdle {
                selection = if (covered) grid.layoutInfo.visibleItemsInfo.last { it.offset.y < grid.layoutInfo.viewportSize.height - 40f * pixels }.key as Int
                    else grid.firstVisibleItemIndex
            }
        }
        var before = 0f
        compose.runOnIdle { before = position() }
        compose.mainClock.autoAdvance = false
        val frames = mutableListOf<Float>()
        try {
            compose.runOnIdle { visible = true }
            repeat(60) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnIdle { frames += position() / columns - before }
            }
            val expected = if (atBottom || covered) 88f * pixels else 0f
            assertEquals("Exactly the added panel inset", expected, frames.last(), 1.1f)
            frames.zipWithNext().forEach { (a, b) -> assertTrue("Reveal is monotonic: $frames", b >= a - 1f) }
            if (!reduced && expected > 0f) assertTrue("Reveal has intermediate frames", frames.any { it > 1f && it < expected - 1f })
            compose.runOnIdle { visible = false }
            val exits = mutableListOf<Float>()
            repeat(60) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnIdle { exits += position() / columns - before }
            }
            exits.zipWithNext().forEach { (a, b) -> assertTrue("Dismiss is monotonic: $exits", b <= a + 1f) }
            assertEquals("Only the occupied extra tail settles back", if (atBottom) 0f else expected, exits.last(), 1.1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun atBottomMovesExactlyTheInsetAndDismissesWithoutJump() = verify(true, false)
    @Test fun coveredSelectionMovesExactlyTheInset() = verify(false, true)
    @Test fun neitherConditionKeepsPosition() = verify(false, false)
    @Test fun galleryUsesSameInset() = verify(true, false, columns = 3)
    @Test fun reducedMotionUsesExactDestination() = verify(true, false, reduced = true)
    @Test fun touchRetiresMotionWithoutRestart() {
        fixture(); bottom()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible = true }
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            var stopped = 0f
            compose.runOnIdle { motion.interrupt(); stopped = position() }
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(stopped, position(), 1.1f) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}

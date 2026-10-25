package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowseCellGeometry
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import io.github.anastaci1a.localica.ui.BrowsePinchMotion
import io.github.anastaci1a.localica.ui.pinchItemPlacement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PinchItemPlacementLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun committedLayerCanOutliveItsTransactionWithoutRecomposition() = retirement(committed = true)
    @Test fun rollbackLayerCanOutliveItsTransactionAndAdmitANewCapture() = retirement(committed = false)

    private fun retirement(committed: Boolean) {
        val style = BrowseStyle(listSize = ListSize.Normal)
        val motion = BrowsePinchMotion().apply { source = style; target = style.copy(listSize = ListSize.Large) }
        runBlocking { motion.progress.snapTo(0f) }
        var compositions = 0
        var shift = 0f
        compose.setContent {
            val grid = rememberLazyGridState()
            val density = LocalDensity.current
            LaunchedEffect(density) {
                motion.pixelDensity = density.density
                shift = with(density) { 24.dp.toPx() }
                motion.bounds = mapOf("song" to Rect(0f, shift, with(density) { 180.dp.toPx() }, shift * 3))
            }
            Box(Modifier.size(180.dp, 180.dp).testTag("viewport")) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "song") {
                        SideEffect { compositions++ }
                        // Deliberately keep the real production modifier mounted after retirement:
                        // native layer invalidation may run before BrowserScreen replaces it.
                        Box(Modifier.height(48.dp).pinchItemPlacement(motion, grid, "song", 0,
                            style, BrowseCellGeometry(1)).testTag("song"))
                    }
                }
            }
        }
        fun top(): Float = compose.onNodeWithTag("song").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot.top
        compose.waitForIdle()
        val initialCompositions = compositions
        assertEquals(shift, top(), .5f)
        compose.runOnIdle { runBlocking { motion.progress.snapTo(if (committed) 1f else .5f) } }
        compose.waitForIdle()
        assertEquals(if (committed) 0f else shift / 2, top(), .5f)
        if (!committed) compose.runOnIdle { runBlocking { motion.progress.snapTo(0f) } }
        compose.runOnIdle { motion.clearTransaction() }
        compose.waitForIdle()
        assertEquals("Retired layers return to ordinary live placement", 0f, top(), .5f)
        compose.runOnIdle {
            motion.capturing = true
            motion.source = style; motion.target = style.copy(listSize = ListSize.Large)
            motion.bounds = mapOf("song" to Rect(0f, shift * 2, shift * 5, shift * 4))
            runBlocking { motion.progress.snapTo(0f) }
        }
        compose.waitForIdle()
        assertEquals("The next readback must not reuse the retired transform", 0f, top(), .5f)
        compose.runOnIdle { motion.capturing = false }
        compose.waitForIdle()
        assertEquals(shift * 2, top(), .5f)
        compose.runOnIdle { motion.dispose() }
        compose.waitForIdle()
        assertEquals(0f, top(), .5f)
        assertEquals("Only native layer updates should be needed", initialCompositions, compositions)
    }
}

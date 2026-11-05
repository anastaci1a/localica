package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DragShadowContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun cancelShadowFallsOnTheMovingDragStack() {
        lateinit var state: KeyedDragState<String, Unit>
        val entries = listOf("first", "second", "third")
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            state = remember { object : KeyedDragState<String, Unit>(grid, scope, { it }) {
                override fun capturePresentation() = Unit
            }.also { it.items = entries } }
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(300.dp, 440.dp).background(Color.White).testTag("drag-shadow-scene")
                    .onSizeChanged { state.viewportWidth = it.width; state.viewportHeight = it.height }) {
                    LazyVerticalGrid(columns = GridCells.Fixed(1), state = grid) {
                        items(entries, key = { it }) { Box(Modifier.fillMaxWidth().height(120.dp)) }
                    }
                    KeyedDragOverlay(state, true, { "Dragging $it" }, cancelBottomMargin = 80.dp) { _, _ ->
                        // Opaque card foreground proves the X shadow is above the actual stack.
                        Box(Modifier.fillMaxSize().background(Color.White))
                    }
                }
            }
        } }
        compose.runOnIdle {
            assertTrue(state.begin("first", entries.toSet(), state.bounds().getValue("first").center))
        }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("drag-shadow-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val target = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(target.center.x-8*d, target.bottom+3*d, target.center.x+8*d, target.bottom+7*d))
        for (dx in listOf(-12f, -8f, -4f, 0f, 4f, 8f, 12f, 0f)) {
            compose.runOnIdle { state.move(target.center + Offset(dx*d, 0f)) }
            val image = scene.captureToImage()
            val luminance = probe.sample(image)
            assertTrue("X shadow must fall on the opaque dragged card at dx=$dx: $luminance", luminance < .97f)
            ShadowFrameProbe.assertSpreadEdge(image,
                Rect(target.center.x-8*d, target.bottom+30*d, target.center.x+8*d, target.bottom+32*d),
                Rect(target.center.x-8*d, target.bottom+32*d, target.center.x+8*d, target.bottom+34*d), .02f, "drag X")
        }
        probe.assertContinuous(.025f, "drag X over moving cards")
    }
}

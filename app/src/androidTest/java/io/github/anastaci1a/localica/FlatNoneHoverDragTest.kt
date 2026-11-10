package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #776: actual drag-card draws must keep the same flat surface and shadow owner. */
class FlatNoneHoverDragTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun redrawsAllocateNoGlassAndKeepOneStableShadow() {
        val events = mutableListOf<String>()
        val redraw = mutableIntStateOf(0)
        lateinit var root: CompositionData
        val surfaces = HoverGroupRenderer::class.java.getDeclaredField("surfaces").apply { isAccessible = true }
        val shadows = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun currentShadow(): Any {
            val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HoverGroupRenderer, Boolean>())
            fun visit(table: CompositionData) {
                for (group in table.compositionGroups) {
                    group.data.filterIsInstance<HoverGroupRenderer>().forEach { owners.add(it) }
                    visit(group)
                }
            }
            visit(root)
            val actual = surfaces.get(owners.single()) as Map<*, *>
            return (actual.values.single() as HoverSurfaceRenderer).shadow
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { GlassWork.observer = { events.add(it) } }
            compose.setContent {
                root = currentComposer.compositionData
                CompositionLocalProvider(LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.FlatNone) },
                    LocalGlassAppearance provides GlassAppearance(renderer = GlassRendererMode.FlatNone),
                    LocalDensity provides Density(1f)) {
                    LocalicaTheme {
                        val rest = MediaItemOutline(Rect(40f, 48f, 240f, 112f), 12f)
                        val geometry = hoverGeometry(rest, Rect(0f, 0f, 280f, 160f), 1f)
                        val frame = HoverCardFrame({ Offset.Zero }, { redraw.intValue; geometry }, { Offset.Zero },
                            { rest.bounds }, { rest.bounds }, { 1f }, { 0f }, { 1f })
                        HoverDragCard(Modifier.size(280.dp, 160.dp).testTag("flat-drag"), frame, null,
                            LiftShadow(1f, .6f, Color.Black), stackShadow = { 1f }) {}
                    }
                }
            }
            repeat(64) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle { events.clear() }
            val reference = compose.onNodeWithTag("flat-drag").captureToImage().asAndroidBitmap()
            var changedImages = 0
            repeat(24) {
                compose.runOnIdle { redraw.intValue++ }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val image = compose.onNodeWithTag("flat-drag").captureToImage().asAndroidBitmap()
                if (!reference.sameAs(image)) changedImages++
                compose.runOnIdle { shadows.add(currentShadow()) }
            }
            compose.runOnIdle {
                assertEquals("Every actual drag redraw is flat; no optical allocation or preparation", emptyList<String>(), events)
                assertEquals("The same shadow survives every drag frame", 1, shadows.size)
                assertEquals("Unchanged geometry has an exactly stable native shadow", 0, changedImages)
            }
        } finally {
            compose.runOnIdle { GlassWork.observer = null }
            compose.mainClock.autoAdvance = true
        }
    }
}

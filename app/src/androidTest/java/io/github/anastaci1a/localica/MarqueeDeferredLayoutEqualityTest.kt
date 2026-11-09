package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarqueeDeferredLayoutEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun firstFramePauseBoundaryAndWidthReversalsMatchOriginalClock() = compare(false, false, LayoutDirection.Ltr)
    @Test fun minimumHeightAndWidthKeepTheOriginalTopStartPlacement() = compare(true, false, LayoutDirection.Ltr)
    @Test fun unboundedMinimumWidthKeepsOriginalRightToLeftPlacement() = compare(true, true, LayoutDirection.Rtl)

    private fun compare(minimum: Boolean, unbounded: Boolean, direction: LayoutDirection) {
        val title = if (unbounded) "Short" else "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        var reference by mutableStateOf(true)
        var viewport by mutableIntStateOf(120)
        var paused by mutableStateOf(false)
        var epoch by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.requiredSize(320.dp, 100.dp).background(Color.White).testTag("root")) {
                    key(reference, epoch) {
                        Layout(content = {
                            if (reference) ReferenceMarqueeTitle(title, false, Modifier.testTag("text"), style, paused)
                            else MarqueeTitle(title, false, Modifier.testTag("text"), style, paused)
                        }) { children, _ ->
                            val width = viewport.dp.roundToPx()
                            val child = children.single().measure(Constraints(
                                minWidth = if (minimum) width else 0,
                                maxWidth = if (unbounded) Constraints.Infinity else width,
                                minHeight = if (minimum) 64.dp.roundToPx() else 0,
                                maxHeight = 96.dp.roundToPx()))
                            layout(child.width, child.height) { child.placeRelative(0, 0) }
                        }
                    }
                }
            }
        } }
        data class Frame(val bounds: Rect, val width: Int, val height: Int, val pixels: IntArray)
        fun record(): Frame {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("text").fetchSemanticsNode().boundsInRoot
            val pixels = compose.onNodeWithTag("root").captureToImage().toPixelMap()
            return Frame(bounds, pixels.width, pixels.height,
                IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() })
        }
        fun arm(old: Boolean): List<Frame> {
            compose.runOnIdle { reference = old; viewport = 120; paused = false; epoch++ }
            val frames = mutableListOf<Frame>()
            compose.mainClock.advanceTimeByFrame(); frames += record()
            // Sample either side of the initial pause boundary and the first moving frame.
            for (delta in listOf(1872L, 16L, 16L, 64L)) {
                compose.mainClock.advanceTimeBy(delta); frames += record()
            }
            for ((width, stop) in listOf(180 to false, 260 to true, 120 to true, 180 to false, 120 to false)) {
                compose.runOnIdle { viewport = width; paused = stop }
                compose.mainClock.advanceTimeByFrame(); frames += record()
                compose.mainClock.advanceTimeBy(64); frames += record()
            }
            return frames
        }
        try {
            val a = arm(true)
            val b = arm(false)
            a.zip(b).forEachIndexed { i, (old, current) ->
                assertEquals("Bounds at sample $i", old.bounds, current.bounds)
                assertEquals(old.width, current.width); assertEquals(old.height, current.height)
                val changed = old.pixels.indices.count { old.pixels[it] != current.pixels[it] }
                assertEquals("Exact first-frame/clock pixels at sample $i", 0, changed)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}

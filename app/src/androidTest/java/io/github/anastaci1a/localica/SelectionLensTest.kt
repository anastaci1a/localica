package io.github.anastaci1a.localica

import android.graphics.Region
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp

class SelectionLensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lensBackdropNeverReplacesUnselectedViewportPixels() {
        var chosen by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = true)
            Box(Modifier.size(220.dp).testTag("selection-backing")) {
                Box(Modifier.fillMaxSize().recordFrostedBackdrop(source).background(Color.Red))
                Box(Modifier.fillMaxSize().background(Color.Blue))
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(40.dp).size(100.dp).selectionLens(chosen, false))
                    }
                }
            }
        } }
        val before = compose.onNodeWithTag("selection-backing").captureToImage().toPixelMap()
        compose.runOnIdle { chosen = true }
        val after = compose.onNodeWithTag("selection-backing").captureToImage().toPixelMap()
        val x = after.width - 10
        assertEquals("Sampling a background cannot repaint unselected space", before[x, 10], after[x, 10])
    }

    @Test fun firstSelectionRedrawsTheLensAndPartialSelectionRemovesIt() {
        var chosen by mutableStateOf(false)
        var partial by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(200.dp).background(Color.Black).testTag("scene")) {
                Box(Modifier.padding(20.dp)) {
                    Box(Modifier.size(160.dp, 80.dp).selectionLens(chosen, partial).background(Color(0xFF202020)).testTag("item"))
                }
            }
        } }
        val original = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        compose.runOnIdle { chosen = true }
        compose.onNodeWithTag("item").assertIsSelected()
        val selected = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        var brighter = 0
        for (y in 0 until original.height) for (x in 0 until original.width) {
            if (selected[x, y].red > original[x, y].red + .1f) brighter++
        }
        assertTrue("The first selection must draw a visible lens without another interaction: $brighter", brighter > 30)
        compose.runOnIdle { partial = true }
        val partialPixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        var residual = 0
        for (y in 0 until original.height) for (x in 0 until original.width) {
            if (partialPixels[x, y].red > original[x, y].red + .1f) residual++
        }
        assertEquals("Partial groups must not retain a lens", 0, residual)
    }

    @Test fun selectionAppearsImmediatelyAndDisappearsWithoutAnExitAnimation() {
        var chosen by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(200.dp).testTag("animated-lens")) {
                Box(Modifier.fillMaxSize().background(Color(0xFF404040)))
                Box(Modifier.padding(20.dp).size(160.dp, 100.dp).selectionLens(chosen, false))
            }
        } }
        val node = compose.onNodeWithTag("animated-lens")
        val original = node.captureToImage().toPixelMap()
        fun energy(): Float {
            val pixels = node.captureToImage().toPixelMap()
            var total = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width)
                total += kotlin.math.abs(pixels[x,y].red - original[x,y].red)
            return total
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { chosen = true }
        compose.mainClock.advanceTimeBy(32)
        val early = energy()
        compose.mainClock.advanceTimeBy(128)
        val middle = energy()
        assertTrue("Selection is visible immediately", early > 5f)
        assertEquals("There is no entrance animation", early, middle, 1f)
        compose.runOnIdle { chosen = false }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("Deselecting removes the lens immediately", energy() < 1f)
    }

    @Test fun lensLeavesCentralDetailCrispOnBothRenderers() {
        var chosen by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(220.dp).testTag("detail-lens")) {
                androidx.compose.foundation.Canvas(Modifier.padding(20.dp).size(180.dp, 140.dp).selectionLens(chosen, false)) {
                    drawRect(Color.DarkGray)
                    for (x in 0..18) drawRect(if (x % 2 == 0) Color.White else Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(x * 10.dp.toPx(), 0f),
                        size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
                }
            }
        } }
        val originalImage = compose.onNodeWithTag("detail-lens").captureToImage()
        val original = originalImage.toPixelMap()
        compose.runOnIdle { chosen = true }
        val selectedImage = compose.onNodeWithTag("detail-lens").captureToImage()
        val selected = selectedImage.toPixelMap()
        val d = original.width / 220f
        fun sharpEdges(pixels: androidx.compose.ui.graphics.PixelMap): Int {
            var count = 0
            for (y in (60*d).toInt()..(110*d).toInt()) for (x in (65*d).toInt()..(155*d).toInt())
                if (kotlin.math.abs(pixels[x,y].red - pixels[x + 2,y].red) > .7f) count++
            return count
        }
        // The new continuous cap may refract interior stripes, but cannot blur away
        // their detail. Pixel-for-pixel equality would require the old flat center.
        assertTrue("Fine detail remains sharp under the curved lens", sharpEdges(selected) >= sharpEdges(original) * .8f)
        assertEquals("Outside the selection is untouched", original[5, 5], selected[5, 5])
    }

    private fun region(vararg rectangles: Rect): Region {
        val path = connectedSelectionPath(rectangles.map { SelectionRegion(it) }, 16f)
        return Region().apply { setPath(path.asAndroidPath(), Region(-1000, -1000, 1000, 1000)) }
    }
    @Test fun sideNeighborsJoinWithoutCoveringAnUnselectedHole() {
        val shape = region(Rect(0f, 0f, 80f, 80f), Rect(88f, 0f, 168f, 80f),
            Rect(0f, 88f, 80f, 168f), Rect(176f, 88f, 256f, 168f))
        assertTrue(shape.contains(84, 40))
        assertTrue(shape.contains(40, 84))
        assertFalse("Unselected tile must remain uncovered", shape.contains(120, 120))
    }
    @Test fun wideRowsMergeWithoutCircularCapsOrInternalShoulders() {
        val shape = region(Rect(8f, 240f, 712f, 360f), Rect(8f, 370f, 712f, 490f))
        assertEquals(android.graphics.Rect(8, 240, 712, 490), shape.bounds)
        for (y in 330..400) {
            assertTrue("Continuous left side at $y", shape.contains(10, y))
            assertTrue("Continuous right side at $y", shape.contains(710, y))
        }
        assertFalse("No circle reaching into the header", shape.contains(360, 100))
        assertFalse("No circle reaching into the next song", shape.contains(360, 540))
    }

    @Test fun selectionHasABroadCurvedBodyAndAnUntouchedCenter() {
        var chosen by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(220.dp).testTag("lens-body")) {
                Box(Modifier.fillMaxSize().background(Color(0xFF404040)))
                Box(Modifier.padding(20.dp).size(180.dp, 120.dp).selectionLens(chosen, false))
            }
        } }
        val original = compose.onNodeWithTag("lens-body").captureToImage().toPixelMap()
        compose.runOnIdle { chosen = true }
        val selected = compose.onNodeWithTag("lens-body").captureToImage().toPixelMap()
        val density = original.width / 220f
        // Deep enough to reject the old 1dp outline; far enough from the corner to
        // test the curved face rather than anti-aliasing at the silhouette.
        var curved = 0
        for (y in (24 * density).toInt()..(31 * density).toInt()) {
            if (selected[(110 * density).toInt(), y].red > original[(110 * density).toInt(), y].red + .015f) curved++
        }
        assertTrue("The glass must have width, not just an outline", curved > 3)
        assertEquals(original[(110 * density).toInt(), (80 * density).toInt()].red,
            selected[(110 * density).toInt(), (80 * density).toInt()].red, .01f)
        assertEquals(original[(110 * density).toInt(), (180 * density).toInt()].red,
            selected[(110 * density).toInt(), (180 * density).toInt()].red, .01f)
    }

    @Test fun diagonalNeighborsStaySeparate() {
        val shape = region(Rect(0f, 0f, 80f, 80f), Rect(88f, 88f, 168f, 168f))
        assertFalse(shape.contains(84, 84))
        assertFalse(shape.contains(120, 40))
        assertFalse(shape.contains(40, 120))
        val split = region(Rect(0f, 0f, 80f, 80f))
        assertFalse(split.contains(84, 84))
    }

    @Test fun translatingASelectionReusesItsDistanceField() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val regions = listOf(SelectionRegion(Rect(0f, 0f, 300f, 200f)))
        val path = connectedSelectionPath(regions, 16f)
        val optics = ConnectedLensOptics()
        repeat(120) { index ->
            optics.effect(regions, path, 22f, 1f,
                androidx.compose.ui.geometry.Offset(0f, -index.toFloat()))
        }
        assertEquals("Scrolling must not rasterize/upload a field every frame", 1, optics.rasterizations)
    }

    @Test fun lensFollowsPlacementWithoutASecondLayoutCallback() {
        var y by mutableStateOf(20.dp)
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(220.dp, 260.dp).background(Color.Black).testTag("moving")) {
                Box(Modifier.offset(y = y).padding(horizontal = 20.dp).size(180.dp, 100.dp).selectionLens(true, false))
            }
        } }
        val before = compose.onNodeWithTag("moving").captureToImage().toPixelMap()
        compose.runOnIdle { y = 80.dp }
        val after = compose.onNodeWithTag("moving").captureToImage().toPixelMap()
        val d = before.width / 220f
        var initial = 0; var moved = 0; var stale = 0
        for (yy in (20*d).toInt()..(32*d).toInt()) for (x in (60*d).toInt()..(160*d).toInt()) {
            if (before[x, yy].red > .02f) initial++
            if (after[x, yy + (60*d).toInt()].red > .02f) moved++
            if (after[x, yy].red > .02f) stale++
        }
        assertTrue(initial > 20 && moved > 20)
        assertEquals("No lens remains at the previous row position", 0, stale)
    }
}

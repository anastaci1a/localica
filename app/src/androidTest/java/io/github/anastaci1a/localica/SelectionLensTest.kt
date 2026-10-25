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

    @Test fun disconnectedSelectionCannotChangeTheFirstGroupsOptics() {
        var second by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit)
            Box(Modifier.size(280.dp, 360.dp).testTag("independent-lenses")) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val cell = 8.dp.toPx()
                    for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                        drawRect(if ((x + y) % 2 == 0) Color.Red else Color.Blue,
                            androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell, cell))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Box(Modifier.fillMaxWidth().height(80.dp).selectionLens(true, false))
                            Spacer(Modifier.height(80.dp))
                            Box(Modifier.fillMaxWidth().height(80.dp).selectionLens(second, false))
                        }
                    }
                }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.snapshot != null }
        val before = compose.onNodeWithTag("independent-lenses").captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { second = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        val after = compose.onNodeWithTag("independent-lenses").captureToImage().toPixelMap()
        val density = before.width / 280f
        var difference = 0f; var count = 0
        for (y in (20 * density).toInt() until (92 * density).toInt() step 2)
            for (x in (20 * density).toInt() until (260 * density).toInt() step 2) {
                difference += kotlin.math.abs(before[x, y].red - after[x, y].red); count++
            }
        assertTrue("A disconnected arriving lens cannot fade the existing body: ${difference / count}", difference / count < .01f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun selectionRefractsItsBackingOnBothRenderers() {
        var chosen by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit)
            Box(Modifier.size(300.dp, 220.dp).testTag("selection-optics")) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val cell = 8.dp.toPx()
                    for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                        drawRect(if ((x + y) % 2 == 0) Color.Red else Color.Blue,
                            androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell, cell))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(20.dp).size(260.dp, 180.dp).selectionLens(chosen, false))
                    }
                }
            }
        } }
        val before = compose.onNodeWithTag("selection-optics").captureToImage().toPixelMap()
        compose.runOnIdle { chosen = true }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.snapshot != null }
        val after = compose.onNodeWithTag("selection-optics").captureToImage().toPixelMap()
        val density = before.width / 300f
        var changed = 0; var samples = 0
        for (y in (40 * density).toInt() until (180 * density).toInt() step 3)
            for (x in (40 * density).toInt() until (260 * density).toInt() step 3) {
                samples++
                if (kotlin.math.abs(before[x, y].red - after[x, y].red) > .25f) changed++
            }
        assertTrue("A selected lens needs spatial refraction across its body, not only an illuminated outline: $changed / $samples", changed > samples / 5)
    }

    @Test fun selectedContentIsRecordedOnceAndStillUpdates() {
        var color by mutableStateOf(Color.Red)
        val draws = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(180.dp).testTag("single-recording")) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().selectionLens(true, false)) {
                    draws.incrementAndGet(); drawRect(color)
                }
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { draws.set(0); color = Color.Blue }
        compose.waitForIdle()
        assertEquals("The viewport and lens must share one content recording", 1, draws.get())
        val pixels = compose.onNodeWithTag("single-recording").captureToImage().toPixelMap()
        assertTrue("A retained layer cannot freeze live content", pixels[pixels.width / 2, pixels.height / 2].blue > .9f)
    }

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

    @Test fun selectionEasesInAndOutAndCanReverseBeforeSettling() {
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
        try {
            compose.runOnIdle { chosen = true }
            // First deliver the target to composition. The following two frames
            // establish the animation epoch and render its first nonzero value.
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            val early = energy()
            compose.mainClock.advanceTimeBy(220)
            val settled = energy()
            assertTrue("Selection starts visibly without an onset delay", early > 5f)
            assertTrue("The lens eases into its complete appearance", settled > early + 1f)
            compose.runOnIdle { chosen = false }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            val departing = energy()
            assertTrue("Deselecting retains a fading lens", departing > 1f && departing < settled)
            compose.runOnIdle { chosen = true }
            compose.mainClock.advanceTimeByFrame()
            // Existing animations advance before recomposition accepts reversal.
            // Compare against that displayed handoff, not the previous exit frame.
            val handoff = energy()
            assertTrue("Re-selection retains the displayed lens", handoff > 1f && handoff < settled)
            compose.mainClock.advanceTimeBy(32)
            val reversed = energy()
            assertTrue("Re-selection reverses from the displayed lens, not from zero", reversed > handoff)
            compose.mainClock.advanceTimeBy(220)
            assertEquals("Reversal restores the same settled material", settled, energy(), 1f)
            compose.runOnIdle { chosen = false }
            compose.mainClock.advanceTimeBy(220)
            assertTrue("The completed exit leaves no stale lens", energy() < 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun lensLeavesForegroundDetailInPlaceOnBothRenderers() {
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
        var displaced = 0
        for (y in (40*d).toInt()..(140*d).toInt()) for (x in (35*d).toInt()..(185*d).toInt()) {
            if ((selected[x,y].red > .5f) != (original[x,y].red > .5f)) displaced++
        }
        // Background bends, but row content is on the front surface. Test across
        // the curved shoulders as well as the center; sharp-but-magnified text fails.
        assertTrue("Foreground stripes cannot move or duplicate under selection: $displaced", displaced < 4)
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
        val optics = LensSelectionRenderer()
        repeat(120) { index ->
            optics.effect(regions, path, 16f,
                androidx.compose.ui.geometry.Offset(0f, -index.toFloat()), 1.35f)
        }
        assertEquals("Simple shapes need no distance texture while scrolling", 0, optics.rasterizations)
    }

    @Test fun translatingAnIrregularSelectionKeepsItsMapButChangingItsHoleRebuildsIt() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val regions = listOf(SelectionRegion(Rect(0f, 0f, 80f, 80f)),
            SelectionRegion(Rect(88f, 0f, 168f, 80f)), SelectionRegion(Rect(0f, 88f, 80f, 168f)))
        val optics = LensSelectionRenderer()
        val path = connectedSelectionPath(regions, 16f)
        repeat(120) { index ->
            optics.effect(regions, path, 16f,
                androidx.compose.ui.geometry.Offset(.37f, -index * 1.13f), 1.35f)
        }
        assertEquals("Translation must reuse the irregular shape's numeric field", 1, optics.rasterizations)
        val changed = regions.dropLast(1) + SelectionRegion(Rect(88f, 88f, 168f, 168f))
        optics.effect(changed, connectedSelectionPath(changed, 16f), 16f,
            androidx.compose.ui.geometry.Offset.Zero, 1.35f)
        assertEquals("A changed hole cannot reuse stale geometry", 2, optics.rasterizations)
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

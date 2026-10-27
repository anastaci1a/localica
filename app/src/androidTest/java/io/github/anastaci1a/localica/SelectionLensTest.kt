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

    /** Four fixed captures: baseline + selected for each orientation, with both controls in
     * the same capture. Optical differences are evidence, not a new parity tolerance. */
    @Test fun joinedPairsExposeRenderedDifferencesFromSameOrientationRoundRectControls() {
        var horizontal by mutableStateOf(false)
        var chosen by mutableStateOf(false)
        val observed = ShapeControlObservation(2)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                LocalReducedMotion provides true,
                LocalGlassAppearance provides LocalGlassAppearance.current.copy(depth = 1.35f)) {
                val cells = shapeControlCells(horizontal)
                val outer = if (horizontal) Rect(16f, 16f, 184f, 96f) else Rect(16f, 16f, 96f, 184f)
                Row(Modifier.testTag("joined-shape-controls")) {
                    ShapeControlPane(cells, cells, chosen, observed, 0)
                    // A real single-item host is the optical control. The independent native
                    // RoundRect below, not production path generation, is the contour oracle.
                    ShapeControlPane(listOf(outer), cells, chosen, observed, 1)
                }
            }
        } }
        for (across in listOf(false, true)) {
            compose.runOnIdle { horizontal = across; chosen = false }
            compose.waitForIdle()
            val name = if (across) "horizontal" else "vertical"
            val before = captureShapeControl("joined-shape-controls", "$name-baseline").toPixelMap()
            compose.runOnIdle { chosen = true }
            awaitShapeControl(observed, listOf(2, 1))
            val after = captureShapeControl("joined-shape-controls", "$name-selected").toPixelMap()
            assertEquals(400, before.width); assertEquals(200, before.height)
            assertEquals(before.width, after.width); assertEquals(before.height, after.height)
            val cells = shapeControlCells(across)
            val outer = if (across) Rect(16f, 16f, 184f, 96f) else Rect(16f, 16f, 96f, 184f)
            val expected = independentRoundRect(outer)
            assertSubmittedContour(observed, 0, cells, expected)
            assertSubmittedContour(observed, 1, listOf(outer), expected)
            var error = 0.0; var maximum = 0f; var samples = 0
            for (y in 0 until 200) for (x in 0 until 200) {
                assertEquals("Both $name controls start over the same local backing at $x/$y", before[x, y], before[x + 200, y])
                if (expected.contains(x, y)) {
                    val a = after[x, y]; val b = after[x + 200, y]
                    val delta = (kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)) / 3f
                    error += delta; maximum = maxOf(maximum, delta); samples++
                }
            }
            for (pane in 0..1) {
                assertShapeForeground(before, after, cells, pane * 200)
                for ((x, y) in listOf(4 to 4, 195 to 195))
                    assertEquals("$name leaves unselected viewport pixels untouched", before[x + pane * 200, y], after[x + pane * 200, y])
            }
            println("ComplexSelection control=$name depth=1.35 density=1 meanRGBDifference=${error / samples} " +
                "maxRGBDifference=$maximum samples=$samples pairClassifierAnalytic=${simpleLensGeometry(cells.map(::SelectionRegion), 16f) != null}; " +
                "same-orientation single-item reference; descriptive only, not a parity or owner-symptom pass")
        }
    }

    /** Two fixed captures. This is a static synthetic L, not a real browser pinch or the
     * owner's unseen screenshot. Its contour oracle is the union of two independent bars. */
    @Test fun irregularLRetainsItsIndependentContourUnselectedGapAndSharpForeground() {
        var chosen by mutableStateOf(false)
        val cells = listOf(Rect(16f, 16f, 96f, 96f), Rect(104f, 16f, 184f, 96f), Rect(16f, 104f, 96f, 184f))
        val observed = ShapeControlObservation(1)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                LocalReducedMotion provides true,
                LocalGlassAppearance provides LocalGlassAppearance.current.copy(depth = 1.35f)) {
                Box(Modifier.testTag("irregular-shape-control")) { ShapeControlPane(cells, cells, chosen, observed, 0) }
            }
        } }
        compose.waitForIdle()
        val before = captureShapeControl("irregular-shape-control", "l-baseline").toPixelMap()
        compose.runOnIdle { chosen = true }
        awaitShapeControl(observed, listOf(3))
        val after = captureShapeControl("irregular-shape-control", "l-selected").toPixelMap()
        assertEquals(200, before.width); assertEquals(200, before.height)
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        val expected = independentRoundRect(Rect(16f, 16f, 184f, 96f)).apply {
            op(independentRoundRect(Rect(16f, 16f, 96f, 184f)), Region.Op.UNION)
        }
        assertSubmittedContour(observed, 0, cells, expected)
        assertTrue("Horizontal shared-side bridge is inside", expected.contains(100, 56))
        assertTrue("Vertical shared-side bridge is inside", expected.contains(56, 100))
        assertFalse("Missing lower-right tile stays outside", expected.contains(140, 140))
        assertShapeForeground(before, after, cells, 0)
        for (y in 116..176 step 4) for (x in 116..176 step 4) {
            assertFalse(expected.contains(x, y))
            assertEquals("The rendered missing tile cannot be filled by a group hull at $x/$y", before[x, y], after[x, y])
        }
        for ((x, y) in listOf(4 to 4, 195 to 195))
            assertEquals("L leaves the surrounding viewport untouched", before[x, y], after[x, y])
        var error = 0.0; var samples = 0
        for (y in 0 until 200) for (x in 0 until 200) if (expected.contains(x, y)) {
            error += kotlin.math.abs(after[x, y].red - before[x, y].red); samples++
        }
        println("ComplexSelection control=L depth=1.35 density=1 meanRedChange=${error / samples} samples=$samples; " +
            "contour/gap/foreground contracts only, no optical-quality or real-pinch acceptance")
    }

    private class ShapeControlObservation(count: Int) {
        val sources = arrayOfNulls<FrostedBackdrop>(count)
        val scenes = arrayOfNulls<SelectionScene>(count)
    }

    private fun shapeControlCells(horizontal: Boolean) = listOf(Rect(16f, 16f, 96f, 96f),
        if (horizontal) Rect(104f, 16f, 184f, 96f) else Rect(16f, 104f, 96f, 184f))

    @Composable private fun ShapeControlPane(regions: List<Rect>, foreground: List<Rect>, chosen: Boolean,
        observed: ShapeControlObservation, index: Int) {
        val source = rememberFrostedBackdrop(Unit)
        SideEffect { observed.sources[index] = source }
        Box(Modifier.size(200.dp)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                for (y in 0..24) for (x in 0..24) drawRect(if ((x + y) % 2 == 0) Color.Red else Color.Blue,
                    androidx.compose.ui.geometry.Offset(x * 8f, y * 8f), androidx.compose.ui.geometry.Size(8f, 8f))
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                ConnectedSelectionHost(Modifier.fillMaxSize()) {
                    val scene = requireNotNull(LocalSelectionScene.current)
                    SideEffect { observed.scenes[index] = scene }
                    regions.forEach { rect ->
                        Box(Modifier.absoluteOffset(rect.left.dp, rect.top.dp).size(rect.width.dp, rect.height.dp)
                            .selectionLens(chosen, false))
                    }
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        foreground.forEach { rect ->
                            repeat(6) { stripe -> drawRect(if (stripe % 2 == 0) Color.White else Color.Black,
                                androidx.compose.ui.geometry.Offset(rect.left + 28f + stripe * 4f, rect.top + 28f),
                                androidx.compose.ui.geometry.Size(4f, 24f)) }
                        }
                    }
                }
            }
        }
    }

    private fun awaitShapeControl(observed: ShapeControlObservation, counts: List<Int>) {
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { observed.sources.all { it?.snapshot != null } }
        compose.runOnIdle {
            counts.forEachIndexed { index, count ->
                val scene = requireNotNull(observed.scenes[index])
                assertEquals("Actual host drew all selected bodies", count, scene.lastDrawnRegions.size)
                assertTrue(scene.regions.values.all { it.selected && it.visibility() == 1f })
            }
        }
    }

    private fun independentRoundRect(bounds: Rect): Region {
        val path = android.graphics.Path().apply {
            addRoundRect(android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom), 24f, 24f,
                android.graphics.Path.Direction.CW)
        }
        return Region().apply { setPath(path, Region(0, 0, 200, 200)) }
    }

    private fun assertSubmittedContour(observed: ShapeControlObservation, index: Int, expectedRegions: List<Rect>, expected: Region) {
        compose.runOnIdle {
            val regions = requireNotNull(observed.scenes[index]).lastDrawnRegions
            assertEquals("Every expected body was submitted", expectedRegions.size, regions.size)
            assertEquals("Scene-map iteration order does not define geometry", expectedRegions.toSet(), regions.map { it.bounds }.toSet())
            val actual = Region().apply { setPath(connectedSelectionPath(regions, 16f).asAndroidPath(), Region(0, 0, 200, 200)) }
            assertTrue("Submitted contour matches an independent native RoundRect oracle",
                Region(actual).apply { op(expected, Region.Op.XOR) }.isEmpty)
        }
    }

    private fun assertShapeForeground(before: androidx.compose.ui.graphics.PixelMap, after: androidx.compose.ui.graphics.PixelMap,
        cells: List<Rect>, offset: Int) {
        for (cell in cells) for (stripe in 0..5) for (dy in listOf(32, 40, 48)) {
            val x = offset + cell.left.toInt() + 30 + stripe * 4
            val y = cell.top.toInt() + dy
            val white = stripe % 2 == 0
            assertEquals("Baseline marker is spatially registered", white, before[x, y].red > .5f)
            assertEquals("Opaque foreground stripes cannot move or duplicate under a connected lens", white, after[x, y].red > .5f)
        }
    }

    private fun captureShapeControl(tag: String, name: String): androidx.compose.ui.graphics.ImageBitmap =
        compose.onNodeWithTag(tag).captureToImage().also { image ->
            // Retain each fixed original before pixel inspection. No capture-driven retries.
            val output = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            val directory = if (output == null)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            else java.io.File(output, "complex-selection-captures").apply { check(isDirectory || mkdirs()) }
            java.io.File(directory, "complex-selection-$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
}

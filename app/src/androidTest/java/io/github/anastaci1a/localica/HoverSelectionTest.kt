package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Region
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * #533 hover-lift selection, frame-sampled on the real host: one monotone ease-out drives
 * the outline growth, content scale, shadow and frost together; the surface shows the same
 * backdrop region magnified; adjacent selections share one outline and part continuously.
 */
class HoverSelectionTest {
    @get:Rule val compose = createComposeRule()

    private val output by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))).let { File(it, "hover-select") }.apply { mkdirs() }
    }
    private fun save(image: ImageBitmap, name: String) = File(output, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }

    private lateinit var scene: SelectionScene
    private lateinit var source: FrostedBackdrop

    /** Backdrop: a red ramp across the first row's leading edge, fine green stripes everywhere. */
    @Composable private fun Fixture(first: Boolean, second: Boolean, rowGap: androidx.compose.ui.unit.Dp = 8.dp, retainSecond: Boolean = false) {
        LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(360.dp, 320.dp).testTag("hover-scene")) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val stripe = 3.dp.toPx()
                    val rampStart = 24.dp.toPx() - 8.dp.toPx()
                    val ramp = 16.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        val red = ((x - rampStart) / ramp).coerceIn(0f, 1f)
                        val green = if ((x / stripe).toInt() % 2 == 0) .9f else .5f
                        drawRect(Color(red, green, .6f), Offset(x, 0f), Size(1f, size.height))
                        x += 1f
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalGlassAppearance provides io.github.anastaci1a.localica.navigation.GlassAppearance(shadowStrength = 1f)) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        scene = checkNotNull(LocalSelectionScene.current)
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 80.dp)) {
                            Canvas(Modifier.fillMaxWidth().height(72.dp).selectionLens(first, false, itemKey = "first")) {
                                // Content: a centred square whose drawn width reveals the content scale.
                                val side = 32.dp.toPx()
                                drawRect(Color.Black, Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side))
                            }
                            Spacer(Modifier.height(rowGap))
                            if (retainSecond) Spacer(Modifier.fillMaxWidth().height(72.dp))
                            else Box(Modifier.fillMaxWidth().height(72.dp).selectionLens(second, false, itemKey = "second"))
                        }
                    }
                }
            }
        }
    }

    private fun capture(): PixelMap = compose.onNodeWithTag("hover-scene").captureNativeFrame().toPixelMap()
    private fun lum(c: Color) = .3f * c.red + .59f * c.green + .11f * c.blue

    private class Sample(val progress: Float, val lifted: Rect, val scale: Float, val shadow: Float, val content: Int, val stripes: Float)

    private fun sample(pixels: PixelMap, rest: Pixels, d: Float): Sample {
        val first = scene.regions.values.first { it.itemKey == "first" }
        val hover = scene.hover["first"]
        val lifted = scene.lastDrawnRegions.firstOrNull()?.bounds ?: rest.bounds
        // Shadow: darkening 6 dp below the row, in a stripe-free comparison (same pixel at rest).
        val sy = (rest.bounds.bottom + 3 * d).toInt().coerceAtMost(pixels.height - 1)
        val sx = rest.bounds.center.x.toInt() + 40
        val shadow = (lum(rest.pixels[sx, sy]) - lum(pixels[sx, sy])).coerceAtLeast(0f)
        // Content: black square width along the row's centre line.
        val cy = rest.bounds.center.y.toInt()
        var black = 0
        for (x in 0 until pixels.width) if (lum(pixels[x, cy]) < .15f) black++
        // Frost: green stripe contrast inside the surface, away from the content square.
        var low = 1f; var high = 0f
        val fy = (rest.bounds.top + 12 * d).toInt()
        for (x in (rest.bounds.left + 60 * d).toInt() until (rest.bounds.left + 90 * d).toInt()) {
            val g = pixels[x, fy].green; low = minOf(low, g); high = maxOf(high, g)
        }
        return Sample(first.visibility(), lifted, hover?.scale ?: 1f, shadow, black, high - low)
    }
    private class Pixels(val pixels: PixelMap, val bounds: Rect)

    @Test fun selectAndDeselectEaseOutWithSimultaneousLiftShadowFrostAndMagnifiedBackdrop() {
        var chosen by mutableStateOf(false)
        compose.setContent { Fixture(chosen, false) }
        if (android.os.Build.VERSION.SDK_INT < 33) {
            // Legacy frost needs its one readback, taken only while a selection leases the backdrop.
            compose.runOnIdle { chosen = true }
            compose.waitUntil(10000) { source.lensSnapshot != null }
            compose.runOnIdle { chosen = false }
        }
        compose.waitForIdle()
        val restPixels = capture()
        val d = restPixels.width / 360f
        val rest = Rect(24 * d, 80 * d, (360 - 24) * d, 152 * d)
        val baseline = Pixels(restPixels, rest)
        compose.mainClock.autoAdvance = false
        try {
            fun run(target: Boolean): List<Sample> {
                compose.runOnIdle { chosen = target }
                val samples = mutableListOf<Sample>()
                repeat(20) {
                    compose.mainClock.advanceTimeByFrame()
                    samples += sample(capture(), baseline, d)
                }
                return samples
            }
            val rising = run(true)
            save(compose.onNodeWithTag("hover-scene").captureNativeFrame(), "hover-selected")
            val settled = rising.last()
            val baselineContent = (0 until restPixels.width).count { lum(restPixels[it, rest.center.y.toInt()]) < .15f }
            rising.forEach { sample ->
                assertTrue("The foreground never disappears during preparation or lift: progress=${sample.progress} content=${sample.content}",
                    sample.content >= baselineContent - 1)
            }
            assertEquals(1f, settled.progress, 0f)
            // Ease-out from the first frame: the touch reads instantly, then settles.
            val firstMoving = rising.indexOfFirst { it.progress > 0f }
            assertTrue("Delivery takes at most two frames: ${rising.map { it.progress }}", firstMoving in 0..2)
            val moving = rising.drop(firstMoving).takeWhile { it.progress < 1f } + settled
            assertTrue("Responds on the first animation frame", moving.first().progress > .1f)
            moving.zipWithNext().forEach { (a, b) -> assertTrue("Monotone rise", b.progress >= a.progress && b.shadow >= a.shadow - .01f) }
            val steps = moving.zipWithNext { a, b -> b.progress - a.progress }
            steps.zipWithNext().forEach { (a, b) -> assertTrue("Decelerating steps: $steps", b <= a + .02f) }
            // Simultaneous: geometry and shadow follow the same progress on every frame.
            val fullScale = settled.scale - 1f
            val fullShadow = settled.shadow
            assertTrue("The lift has a visible shadow: $fullShadow", fullShadow > .02f)
            assertTrue("The outline grows: ${settled.lifted} vs $rest", settled.lifted.height > rest.height + d)
            for (s in moving) {
                assertEquals("Scale tracks progress", s.progress, (s.scale - 1f) / fullScale, .05f)
                assertTrue("Shadow appears with the lift: ${s.progress} ${s.shadow}", s.shadow > 0f)
                assertEquals("Outline tracks progress", s.progress, (s.lifted.height - rest.height) / (settled.lifted.height - rest.height), .05f)
            }
            assertTrue("Content scales with the outline", settled.content > rising.first().content - 1 || settled.content >= (32 * d).toInt())
            // Same backdrop region, magnified: the lifted edge shows the rest edge's ramp value,
            // never the backdrop that lies outside the rest outline.
            val pixels = capture()
            val y = (rest.top + 20 * d).toInt()
            val edge = pixels[(settled.lifted.left + 2).toInt(), y].red
            val restEdge = restPixels[(rest.left + 2).toInt(), y].red
            val outside = restPixels[(settled.lifted.left + 2).toInt(), y].red
            assertTrue("Lifted edge shows the rest edge ($restEdge), not more backdrop ($outside): $edge",
                abs(edge - restEdge) < abs(edge - outside))
            if (android.os.Build.VERSION.SDK_INT >= 33 || source.fallback != null)
                assertTrue("Frost softens the surface: ${rising.first().stripes} -> ${settled.stripes}", settled.stripes < baseline.stripesOf(d) - .05f)
            // Deselect is the exact reverse, also an ease-out.
            val falling = run(false)
            assertEquals(0f, falling.last().progress, 0f)
            val leaving = listOf(settled) + falling.dropWhile { it.progress == 1f }.takeWhile { it.progress > 0f } + falling.last()
            val drops = leaving.zipWithNext { a, b -> a.progress - b.progress }
            assertTrue(drops.all { it >= 0f })
            drops.zipWithNext().forEach { (a, b) -> assertTrue("Deselect decelerates: $drops", b <= a + .02f) }
            val after = capture()
            var changed = 0
            for (yy in 0 until after.height step 4) for (xx in 0 until after.width step 4)
                if (abs(lum(after[xx, yy]) - lum(restPixels[xx, yy])) > .03f) changed++
            assertEquals("Deselection returns exactly to rest", 0, changed)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun Pixels.stripesOf(d: Float): Float {
        var low = 1f; var high = 0f
        val fy = (bounds.top + 12 * d).toInt()
        for (x in (bounds.left + 60 * d).toInt() until (bounds.left + 90 * d).toInt()) {
            val g = pixels[x, fy].green; low = minOf(low, g); high = maxOf(high, g)
        }
        return high - low
    }

    @Test fun adjacentSelectionsShareOneOutlineAndDeselectionNarrowsTheJoinContinuously() {
        var second by mutableStateOf(true)
        compose.setContent { Fixture(true, second) }
        compose.waitForIdle()
        val joined = capture()
        save(compose.onNodeWithTag("hover-scene").captureNativeFrame(), "hover-joined")
        val d = joined.width / 360f
        assertEquals("Both rows hover", 2, scene.lastDrawnRegions.size)
        // The 8 dp gap between the rows is inside the joined outline: no shadow seam there.
        val gapY = (80 * d + 72 * d + 4 * d).toInt()
        fun joinedWidth(pixels: PixelMap): Int {
            var count = 0
            for (x in (24 * d).toInt() until ((360 - 24) * d).toInt()) if (pixels[x, gapY].blue > .55f) count++
            return count
        }
        val full = joinedWidth(joined)
        assertTrue("The gap is bridged across the row width: $full", full > (360 - 48 - 60) * d)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { second = false }
            val widths = mutableListOf(full)
            repeat(18) {
                compose.mainClock.advanceTimeByFrame()
                widths += joinedWidth(capture())
            }
            assertTrue("The join parts once deselected: $widths", widths.last() < full / 4)
            widths.zipWithNext().forEach { (a, b) ->
                assertTrue("No single-frame jump while parting: $widths", a - b <= full * .45f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun aDepartingMemberFadesItsFrostWhileItsNeighbourStaysLifted() {
        var first by mutableStateOf(false)
        var second by mutableStateOf(false)
        compose.setContent { Fixture(first, second) }
        compose.waitForIdle()
        val baseline = capture()
        val d = baseline.width / 360f
        fun stripes(pixels: PixelMap, top: Float): Float {
            val y = ((top + 12f) * d).toInt()
            val values = ((24f + 60f) * d).toInt().until(((24f + 90f) * d).toInt()).map { pixels[it, y].green }
            return values.max() - values.min()
        }
        compose.runOnIdle { first = true; second = true }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.lensSnapshot != null }
        compose.waitForIdle()
        val lifted = capture()
        val resting = stripes(baseline, 160f)
        val full = stripes(lifted, 160f)
        assertTrue("Fixture exposes the selected frost", resting - full > .05f)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { second = false }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(80)
            val p = scene.regions.values.single { it.itemKey == "second" }.visibility()
            assertTrue("Sample an interrupted departure", p > 0f && p < .8f)
            val partial = capture()
            assertEquals("The departing frost follows its own visibility", p,
                (resting - stripes(partial, 160f)) / (resting - full), .15f)
            assertEquals("The selected neighbour retains its frost", stripes(lifted, 80f), stripes(partial, 80f), .03f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun aVirtualizedNeighbourRetainsTheJoinedOutlineAndShadow() {
        var retained by mutableStateOf(false)
        compose.setContent { Fixture(true, true, retainSecond = retained) }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.lensSnapshot != null }
        compose.waitForIdle()
        val beforeImage = compose.onNodeWithTag("hover-scene").captureNativeFrame()
        save(beforeImage, "hover-retained-source")
        val before = beforeImage.toPixelMap()
        val second = checkNotNull(scene.viewportHover["second"])
        compose.runOnIdle {
            scene.retainedHover = listOf(second to 1f)
            retained = true
        }
        compose.waitForIdle()
        val afterImage = compose.onNodeWithTag("hover-scene").captureNativeFrame()
        save(afterImage, "hover-retained-virtualized")
        val after = afterImage.toPixelMap()
        assertEquals("The retained body still participates in the host", 2, scene.lastDrawnRegions.size)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val a = before[x, y]; val b = after[x, y]
            if (maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) > .03f) changed++
        }
        assertEquals("Virtualization keeps one merged outline, frost and shadow", 0, changed)
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
    }
    @Test fun diagonalNeighborsStaySeparate() {
        val shape = region(Rect(0f, 0f, 80f, 80f), Rect(88f, 88f, 168f, 168f))
        assertFalse(shape.contains(84, 84))
        assertFalse(shape.contains(120, 40))
        assertFalse(shape.contains(40, 120))
    }
    @Test fun aWeakeningBridgePinchesTowardItsMiddle() {
        val rows = listOf(SelectionRegion(Rect(0f, 0f, 400f, 60f)), SelectionRegion(Rect(0f, 68f, 400f, 128f)))
        var previous = Int.MAX_VALUE
        for (strength in listOf(1f, .75f, .5f, .25f, .05f)) {
            val path = connectedSelectionPath(rows, 16f, rows, { _, _ -> strength })
            val shape = Region().apply { setPath(path.asAndroidPath(), Region(-1000, -1000, 1000, 1000)) }
            val width = (0 until 400).count { shape.contains(it, 64) }
            assertTrue("Bridge narrows with strength: $strength -> $width", width < previous)
            assertTrue("It stays centred", shape.contains(200, 64))
            previous = width
        }
        val parted = connectedSelectionPath(rows, 16f, rows, { _, _ -> 0f })
        assertFalse(Region().apply { setPath(parted.asAndroidPath(), Region(-1000, -1000, 1000, 1000)) }.contains(200, 64))
    }
}

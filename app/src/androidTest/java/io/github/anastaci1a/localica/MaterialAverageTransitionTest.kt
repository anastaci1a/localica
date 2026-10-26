package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Route evidence plus native pixels; neither certifies ancestor clipping or live legacy pixels. */
class MaterialAverageTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialRoundedContributorsRemainUniformThroughOpeningAndReverseWeights() {
        val fixture = Fixture(withHome = true)
        fixture.mount()
        val weights = listOf(0f, .25f, .75f, 1f, .75f, .25f, 0f)
        val scales = listOf(1f, .85f, .7f, 1f, .7f, .85f, 1f)
        weights.forEachIndexed { index, weight ->
            fixture.stage(index + 1) { fixture.weight = weight; fixture.scale = scales[index] }
            fixture.awaitPublication()
            val pixels = fixture.captureOnce()
            fixture.verifyRoutes(weight)
            val colors = listOf(.3f, .5f, .7f).map { fixture.pixel(pixels, it, .5f) }
            colors.forEach { color ->
                assertEquals("Home red weight=$weight", weight, color.red, .12f)
                assertEquals("Destination blue weight=$weight", 1f - weight, color.blue, .12f)
                assertTrue("No green source in this fixture: $color", color.green < .12f)
            }
            for (channel in listOf<(Color) -> Float>({ it.red }, { it.green }, { it.blue })) {
                assertTrue("Interior mean stays uniform with ordinary shading, stage=$index colors=$colors",
                    colors.maxOf(channel) - colors.minOf(channel) < .06f)
            }
        }
    }

    @Test fun replacementUsesCurrentPublicationAndForegroundNeverEntersAverageRoute() {
        val fixture = Fixture(withHome = false)
        fixture.mount()
        fixture.stage(1) {}
        fixture.awaitPublication()
        val first = fixture.captureOnce()
        fixture.verifyRoutes(0f)
        assertBlue(fixture.pixel(first, .3f, .5f))
        var retired: MaterialAverageRaster? = null
        var oldSnapshot: Any? = null
        compose.runOnIdle {
            oldSnapshot = fixture.destination.snapshot
            if (Build.VERSION.SDK_INT < 33) {
                retired = requireNotNull(MaterialAverageRaster.current(fixture.destination))
                assertTrue(requireNotNull(retired).isCurrent())
            }
        }
        fixture.stage(2) { fixture.replacement = true }
        fixture.awaitPublication(oldSnapshot)
        val replaced = fixture.captureOnce()
        fixture.verifyRoutes(0f)
        assertRed(fixture.pixel(replaced, .3f, .5f))
        if (Build.VERSION.SDK_INT < 33) compose.runOnIdle {
            assertFalse("Previous completed raster is retired after replacement", requireNotNull(retired).isCurrent())
            val now = requireNotNull(MaterialAverageRaster.current(fixture.destination))
            assertTrue(now.isCurrent())
            assertEquals(fixture.destination.snapshotReadbackRequest, now.publication)
            assertNotEquals(requireNotNull(retired).publication, now.publication)
        }
        compose.runOnIdle { oldSnapshot = fixture.destination.snapshot }
        fixture.stage(3) { fixture.foreground = true }
        fixture.awaitPublication(oldSnapshot)
        val detailed = fixture.captureOnce()
        assertTrue("Foreground-containing recording must not report material averaging",
            fixture.currentRoutes().isEmpty())
        // These actual placed points remain inside the source; the lower red rectangle is foreground.
        assertBlue(fixture.pixel(detailed, .1f, .25f))
        assertRed(fixture.pixel(detailed, .1f, .75f))
        if (Build.VERSION.SDK_INT < 33) compose.runOnIdle {
            assertNull("Foreground provenance has no material raster", MaterialAverageRaster.current(fixture.destination))
            assertFalse(requireNotNull(retired).isCurrent())
        }
    }

    private fun assertBlue(color: Color) {
        assertTrue("Expected actual blue content, got $color", color.blue > .8f && color.red < .15f && color.green < .15f)
    }
    private fun assertRed(color: Color) {
        assertTrue("Expected actual red content, got $color", color.red > .8f && color.blue < .15f && color.green < .15f)
    }

    private inner class Fixture(val withHome: Boolean) {
        var weight by mutableFloatStateOf(0f)
        var scale by mutableFloatStateOf(1f)
        var replacement by mutableStateOf(false)
        var foreground by mutableStateOf(false)
        private val stage = AtomicInteger(1)
        private val routes = ConcurrentLinkedQueue<Pair<Int, MaterialAverageSamplingFrame>>()
        lateinit var destination: FrostedBackdrop
        private lateinit var home: FrostedBackdrop
        private lateinit var sceneCoordinates: LayoutCoordinates
        private lateinit var sourceCoordinates: LayoutCoordinates
        private lateinit var homeCoordinates: LayoutCoordinates
        private lateinit var lensCoordinates: LayoutCoordinates
        private lateinit var view: View
        private var cornerPixels = 0f

        fun mount() {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(
                    blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f),
                    LocalMaterialAverageSamplingObserver provides { routes += stage.get() to it }) {
                    destination = rememberFrostedBackdrop(replacement to foreground, dark = false, glassBlur = 0f, whiteTint = 0f)
                    home = rememberFrostedBackdrop(Unit, dark = false, glassBlur = 0f, whiteTint = 0f)
                    cornerPixels = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
                    SideEffect {
                        destination.materialOnly = !foreground; destination.materialCorner = cornerPixels
                        home.materialOnly = true; home.materialCorner = cornerPixels
                    }
                    Box(Modifier.size(300.dp, 220.dp).testTag("average-transition-scene")
                        .onGloballyPositioned { sceneCoordinates = it }) {
                        Canvas(Modifier.offset(40.dp, 40.dp).size(120.dp, 100.dp)
                            .clip(RoundedCornerShape(16.dp)).onGloballyPositioned { sourceCoordinates = it }
                            .recordFrostedBackdrop(destination)) {
                            drawRect(if (replacement && !foreground) Color.Red else Color.Blue)
                            if (foreground) drawRect(Color.Red, Offset(0f, size.height / 2f),
                                androidx.compose.ui.geometry.Size(size.width, size.height / 2f))
                        }
                        if (withHome) Canvas(Modifier.offset(40.dp, 40.dp).size(120.dp, 100.dp)
                            .clip(RoundedCornerShape(16.dp)).onGloballyPositioned { homeCoordinates = it }
                            .recordFrostedBackdrop(home)) { drawRect(Color.Red) }
                        LiquidGlassSurface(Modifier.offset(120.dp, 65.dp).size(120.dp, 50.dp)
                            .graphicsLayer { scaleX = scale; scaleY = .9f }
                            .onGloballyPositioned { lensCoordinates = it }.testTag("average-transition-lens"),
                            source = destination, homeSource = home.takeIf { withHome }, homeWeight = { weight },
                            cornerRadius = 10.dp, refraction = 0f, pressFeedback = false, shadow = false)
                    }
                }
            } }
            compose.waitForIdle()
        }

        fun stage(value: Int, change: () -> Unit) {
            compose.runOnIdle {
                if (stage.get() != value) { routes.clear(); stage.set(value) }
                change()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        fun awaitPublication(previousSnapshot: Any? = null) {
            if (Build.VERSION.SDK_INT < 33) {
                // Wait the real owned readback, never optical passing values. Existing capture deadlines remain bounded.
                compose.waitUntil(5000) {
                    destination.snapshot != null && destination.fallback != null &&
                        destination.snapshotReadbackRequest == destination.readbackRequest &&
                        (previousSnapshot == null || destination.snapshot !== previousSnapshot) &&
                        (!withHome || home.snapshot != null && home.fallback != null &&
                            home.snapshotReadbackRequest == home.readbackRequest)
                }
            }
            // Commit the published snapshot/provenance before the one native capture for this fixed stage.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        fun captureOnce(): PixelMap {
            val drawn = CountDownLatch(1)
            val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
            compose.runOnIdle {
                view.viewTreeObserver.addOnDrawListener(listener)
                view.postInvalidateOnAnimation()
            }
            try {
                assertTrue("Native draw after stage/publication", drawn.await(5, TimeUnit.SECONDS))
            } finally {
                compose.runOnIdle { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
            }
            return capturePopupPixels(compose, compose.onNodeWithTag("average-transition-scene")) { view }
        }

        fun currentRoutes() = routes.filter { it.first == stage.get() }.map { it.second }

        fun verifyRoutes(homeWeight: Float) = compose.runOnIdle {
            val expected = mutableListOf(Triple(destination, sourceCoordinates, 1f - homeWeight))
            if (withHome) expected += Triple(home, homeCoordinates, homeWeight)
            val observed = currentRoutes()
            for ((source, coordinates, contribution) in expected) {
                val frames = observed.filter { it.sourceIdentity == System.identityHashCode(source) }
                if (contribution == 0f) { assertTrue("Zero-weight contributor not sampled", frames.isEmpty()); continue }
                assertTrue("Actual Canvas reports active contributor", frames.isNotEmpty())
                val latest = frames.last()
                assertEquals(Build.VERSION.SDK_INT < 33, latest.legacy)
                assertEquals(contribution, latest.weight, .0001f)
                val count = independentValidCount(coordinates)
                assertTrue("Fixture truly straddles recorded rounded coverage: $count", count in 1..63)
                assertEquals("Independent placed-coordinate mask", count, latest.validSamples)
                if (Build.VERSION.SDK_INT < 33) assertEquals(source.snapshotReadbackRequest, requireNotNull(latest.publication))
                else assertNull(latest.publication)
            }
        }

        private fun independentValidCount(source: LayoutCoordinates): Int {
            val width = source.size.width.toDouble(); val height = source.size.height.toDouble()
            val radius = cornerPixels.toDouble().coerceAtMost(minOf(width, height) / 2)
            return (0 until 64).count { index ->
                val local = Offset(lensCoordinates.size.width * ((index % 8 + .5f) / 8f),
                    lensCoordinates.size.height * ((index / 8 + .5f) / 8f))
                val point = source.localPositionOf(lensCoordinates, local)
                val x = point.x.toDouble(); val y = point.y.toDouble()
                val dx = maxOf(radius - x, 0.0, x - (width - radius))
                val dy = maxOf(radius - y, 0.0, y - (height - radius))
                x >= 0 && y >= 0 && x <= width && y <= height && dx * dx + dy * dy <= radius * radius
            }
        }

        fun pixel(pixels: PixelMap, xFraction: Float, yFraction: Float): Color {
            val native = compose.runOnIdle {
                sceneCoordinates.localPositionOf(lensCoordinates, Offset(
                    lensCoordinates.size.width * xFraction, lensCoordinates.size.height * yFraction))
            }
            val x = native.x.roundToInt(); val y = native.y.roundToInt()
            assertTrue("Placed sample lies inside captured scene", x in 0 until pixels.width && y in 0 until pixels.height)
            return pixels[x, y]
        }
    }
}

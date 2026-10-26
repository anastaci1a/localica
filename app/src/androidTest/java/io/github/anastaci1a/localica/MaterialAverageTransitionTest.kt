package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import kotlin.math.pow

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

    @Test fun requestedHomeWaitsForItsProducerAndKeepsCapturedBasisAfterConsumerMoves() {
        val fixture = HomePolicyFixture()
        fixture.mount()
        fixture.ready(homeRequired = false)
        val unavailable = fixture.capture()
        assertTrue("An unmounted requested home must not report a destination fallback", fixture.events().isEmpty())
        assertFalse("No home recording exists yet", compose.runOnIdle { fixture.home.recorded })
        fixture.assertChannels(Color.White, fixture.pixel(unavailable), "Missing home leaves the explicit white host")

        fixture.change(2) { fixture.homeMounted = true }
        fixture.ready(homeRequired = true)
        val mounted = fixture.capture()
        fixture.verify(homeWeight = 1f, captured = false)
        fixture.assertChannels(fixture.expected(0.0, 1.0, 0.0), fixture.pixel(mounted), "Current home readiness")
        compose.runOnIdle { fixture.freezeHome() }

        fixture.change(3) { fixture.consumerLeft = 40; fixture.consumerScale = .6f; fixture.weight = .6f }
        fixture.ready(homeRequired = true)
        val blended = fixture.capture()
        fixture.verify(homeWeight = .6f, captured = true)
        fixture.assertChannels(fixture.expected(0.0, .6, .4), fixture.pixel(blended), "Post-blend nonneutral mapping")

        fixture.change(4) { fixture.destinationMounted = false; fixture.weight = 1f }
        fixture.ready(homeRequired = true)
        compose.runOnIdle { assertFalse("Destination placement is genuinely detached", fixture.destinationCoordinates.isAttached) }
        val homeOnly = fixture.capture()
        fixture.verify(homeWeight = 1f, captured = true)
        fixture.assertChannels(fixture.expected(0.0, 1.0, 0.0), fixture.pixel(homeOnly), "Pure captured home survives destination detachment")
    }

    private inner class HomePolicyFixture {
        var homeMounted by mutableStateOf(false)
        var destinationMounted by mutableStateOf(true)
        var consumerLeft by mutableIntStateOf(120)
        var consumerScale by mutableFloatStateOf(1f)
        var weight by mutableFloatStateOf(1f)
        private var captured by mutableStateOf<HomeBackdropFootprint?>(null)
        private var capturedPoints: List<Offset>? = null
        private val phase = AtomicInteger(1)
        private val observed = ConcurrentLinkedQueue<Pair<Int, MaterialAverageSamplingFrame>>()
        lateinit var home: FrostedBackdrop
        private lateinit var destination: FrostedBackdrop
        lateinit var destinationCoordinates: LayoutCoordinates
        private lateinit var homeCoordinates: LayoutCoordinates
        private lateinit var consumerCoordinates: LayoutCoordinates
        private lateinit var rootCoordinates: LayoutCoordinates
        private lateinit var view: View
        private var radius = 0f

        fun mount() {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                radius = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = .3f,
                    contrast = .2f, contrastWeight = 2f, shadowStrength = 0f),
                    LocalMaterialAverageSamplingObserver provides { observed += phase.get() to it }) {
                    home = rememberFrostedBackdrop(Unit, dark = false, glassBlur = 0f, whiteTint = 0f)
                    destination = rememberFrostedBackdrop(Unit, dark = false, glassBlur = 0f, whiteTint = 0f)
                    SideEffect {
                        home.materialOnly = true; home.materialCorner = radius
                        destination.materialOnly = true; destination.materialCorner = radius
                    }
                    Box(Modifier.size(300.dp, 220.dp).background(Color.White).testTag("home-policy-scene")
                        .onGloballyPositioned { rootCoordinates = it }) {
                        if (destinationMounted) Canvas(Modifier.offset(40.dp, 40.dp).size(120.dp, 100.dp)
                            .clip(RoundedCornerShape(16.dp)).onGloballyPositioned { destinationCoordinates = it }
                            .recordFrostedBackdrop(destination)) { drawRect(Color.Blue) }
                        if (homeMounted) Canvas(Modifier.offset(40.dp, 40.dp).size(120.dp, 100.dp)
                            .clip(RoundedCornerShape(16.dp)).onGloballyPositioned { homeCoordinates = it }
                            .recordFrostedBackdrop(home)) {
                            drawRect(Color.Red)
                            drawRect(Color.Green, Offset(size.width / 2f, 0f),
                                androidx.compose.ui.geometry.Size(size.width / 2f, size.height))
                        }
                        LiquidGlassSurface(Modifier.offset(consumerLeft.dp, 65.dp).size(120.dp, 50.dp)
                            .graphicsLayer { scaleX = consumerScale; scaleY = .9f }
                            .onGloballyPositioned { consumerCoordinates = it }, source = destination,
                            homeSource = home, homeWeight = { weight },
                            homeFootprint = captured?.let { frozen -> { frozen } },
                            cornerRadius = 10.dp, refraction = 0f, pressFeedback = false, shadow = false)
                    }
                }
            } }
            compose.waitForIdle()
        }

        fun change(value: Int, action: () -> Unit) {
            compose.runOnIdle { observed.clear(); phase.set(value); action() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        fun ready(homeRequired: Boolean) {
            compose.waitUntil(5000) {
                fun published(source: FrostedBackdrop) = source.recorded && source.snapshot != null && source.fallback != null &&
                    source.snapshotReadbackRequest == source.readbackRequest
                if (Build.VERSION.SDK_INT < 33) (!destinationMounted || published(destination)) && (!homeRequired || published(home))
                else (!destinationMounted || destination.recorded) && (!homeRequired || home.recorded)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        fun freezeHome() {
            val width = consumerCoordinates.size.width.toFloat()
            val height = consumerCoordinates.size.height.toFloat()
            val screenOrigin = IntArray(2)
            view.getLocationOnScreen(screenOrigin)
            val toScreen = requireNotNull(BackdropRegistration.fromMapping(width, height) {
                consumerCoordinates.localToRoot(it) + Offset(screenOrigin[0].toFloat(), screenOrigin[1].toFloat())
            })
            capturedPoints = pointsIn(homeCoordinates).toList()
            captured = requireNotNull(HomeBackdropFootprint.create(androidx.compose.ui.geometry.Size(width, height), toScreen))
            val actualCorners = listOf(Offset.Zero, Offset(width, 0f), Offset(width, height), Offset(0f, height))
                .map { homeCoordinates.localPositionOf(consumerCoordinates, it) }
            val reported = requireNotNull(requireNotNull(captured).cornersInSource(home.location.registrationToScreen()))
            actualCorners.zip(reported).forEach { (actual, mapped) ->
                assertEquals(actual.x, mapped.x, .01f); assertEquals(actual.y, mapped.y, .01f)
            }
            assertTrue("Captured footprint includes only the real green half when valid",
                requireNotNull(capturedPoints).filter { inside(it, homeCoordinates) }.all { it.x > homeCoordinates.size.width / 2f })
        }

        private fun pointsIn(source: LayoutCoordinates) = (0 until 64).map { index ->
            source.localPositionOf(consumerCoordinates, Offset(consumerCoordinates.size.width * ((index % 8 + .5f) / 8f),
                consumerCoordinates.size.height * ((index / 8 + .5f) / 8f)))
        }

        private fun inside(point: Offset, source: LayoutCoordinates): Boolean {
            val width = source.size.width.toDouble(); val height = source.size.height.toDouble()
            val r = radius.toDouble().coerceAtMost(minOf(width, height) / 2)
            val x = point.x.toDouble(); val y = point.y.toDouble()
            val dx = maxOf(r - x, 0.0, x - (width - r)); val dy = maxOf(r - y, 0.0, y - (height - r))
            return x >= 0 && y >= 0 && x <= width && y <= height && dx * dx + dy * dy <= r * r
        }

        fun events() = observed.filter { it.first == phase.get() }.map { it.second }

        fun verify(homeWeight: Float, captured: Boolean) = compose.runOnIdle {
            val draws = events()
            val homePoints = if (captured) requireNotNull(capturedPoints) else pointsIn(homeCoordinates)
            val homeCount = homePoints.count { inside(it, homeCoordinates) }
            assertTrue("Home uses a partially covered recording", homeCount in 1..63)
            val homeFrame = draws.lastOrNull { it.home }
            assertNotNull("Requested home produced route evidence", homeFrame)
            homeFrame!!
            assertEquals(System.identityHashCode(home), homeFrame.sourceIdentity)
            assertEquals(homeWeight, homeFrame.weight, .0001f)
            assertEquals(homeCount, homeFrame.validSamples)
            assertEquals(Build.VERSION.SDK_INT < 33, homeFrame.legacy)
            if (Build.VERSION.SDK_INT < 33) assertEquals(home.snapshotReadbackRequest, requireNotNull(homeFrame.publication))
            else assertNull(homeFrame.publication)
            if (homeWeight < 1f) {
                val primary = requireNotNull(draws.lastOrNull { !it.home })
                val primaryCount = pointsIn(destinationCoordinates).count { inside(it, destinationCoordinates) }
                assertEquals(64, primaryCount)
                assertNotEquals("Captured/current masks must genuinely differ", homeCount, primaryCount)
                assertTrue("Current home crop differs from the immutable captured crop",
                    pointsIn(homeCoordinates) != homePoints)
                assertEquals(primaryCount, primary.validSamples)
                assertEquals(System.identityHashCode(destination), primary.sourceIdentity)
                assertEquals(1f - homeWeight, primary.weight, .0001f)
                if (Build.VERSION.SDK_INT < 33) assertEquals(destination.snapshotReadbackRequest, requireNotNull(primary.publication))
            } else assertTrue("Pure home neither samples nor requires destination placement", draws.none { !it.home })
        }

        fun expected(red: Double, green: Double, blue: Double): Color {
            val luma = .2126 * red + .7152 * green + .0722 * blue
            // Independent algebraic form, away from the shader's epsilon endpoints.
            val tint = .3 * (1.0 - luma) / (1.0 - .3 * luma)
            fun mapped(value: Double): Float {
                val lifted = value + (1.0 - value) * tint
                return (lifted + .2 * (1.0 - lifted).pow(2)).toFloat()
            }
            return Color(mapped(red), mapped(green), mapped(blue))
        }

        fun assertChannels(expected: Color, actual: Color, label: String) {
            assertEquals("$label red", expected.red, actual.red, .06f)
            assertEquals("$label green", expected.green, actual.green, .06f)
            assertEquals("$label blue", expected.blue, actual.blue, .06f)
            assertEquals("$label opaque", 1f, actual.alpha, .01f)
        }

        fun pixel(pixels: PixelMap): Color {
            val point = compose.runOnIdle { rootCoordinates.localPositionOf(consumerCoordinates,
                Offset(consumerCoordinates.size.width / 2f, consumerCoordinates.size.height / 2f)) }
            val x = point.x.roundToInt(); val y = point.y.roundToInt()
            assertTrue("Actual placed center fits native capture", x in 0 until pixels.width && y in 0 until pixels.height)
            return pixels[x, y]
        }

        fun capture(): PixelMap {
            val drawn = CountDownLatch(1)
            val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
            compose.runOnIdle { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
            try { assertTrue("Native home-policy draw", drawn.await(5, TimeUnit.SECONDS)) }
            finally { compose.runOnIdle { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
            return capturePopupPixels(compose, compose.onNodeWithTag("home-policy-scene")) { view }
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
                        // The foreground negative uses the existing detailed role, which is
                        // prepared after publication. With neutral tint/blur its identity is exact.
                        (!foreground || destination.lensSnapshot === destination.snapshot) &&
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

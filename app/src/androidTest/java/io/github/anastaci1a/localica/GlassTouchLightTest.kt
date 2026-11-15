package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

class GlassTouchLightTest {
    @get:Rule val compose = localicaComposeRule()
    private val width = 96
    private val height = 80
    private val extent = Size(width.toFloat(), height.toFloat())
    private var background = Color(0xff405060)
    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also {
        image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }
    private fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255
    private fun render(glow: GlassGlow, circle: Boolean, cache: GlassRimCache = GlassRimCache(), mask: Boolean = false,
        tone: GlassTone = GlassTone(), shape: GlassShape = GlassShape(), vertical: Boolean = false, density: Float = 1f): IntArray {
        val image = ImageBitmap(width, height)
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), extent) {
                drawRect(if (mask) Color.Black else background)
                val path = Path().apply {
                    if (circle) addOval(Rect(Offset(8f, 0f), Size(80f, 80f)))
                    else addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(18f)))
                }
                clipPath(path) {
                    if (mask) drawRect(Color.White) else drawContactGlow(glow, shape, vertical, tone, cache)
                }
            }
            return pixels(image)
        } finally { image.asAndroidBitmap().recycle() }
    }
    private fun expected(mask: IntArray, point: Offset, x: Int, y: Int, shift: Int, big: Float, little: Float,
        shape: GlassShape = GlassShape(), vertical: Boolean = false, white: Float = 255f): Int {
        val sx = exp((if (vertical) -shape.strain else shape.strain) - shape.depth / 2)
        val sy = exp((if (vertical) shape.strain else -shape.strain) - shape.depth / 2)
        val center = Offset(width * (.5f + (point.x - .5f) / sx), height * (.5f + (point.y - .5f) / sy))
        val radius = min(.75f * min(width, height), 80f)
        val falloff = max(0f, 1 - (Offset(x + .5f, y + .5f) - center).getDistance() / radius)
        val coverage = channel(mask[y * width + x], 0) / 255f
        val first = (channel(background.toArgb(), shift) + (white - channel(background.toArgb(), shift)) * .12f * big * coverage).roundToInt()
        return (first + (white - first) * .12f * little * falloff * coverage).roundToInt()
    }

    @Test fun broadAndLocalFillsMatchIndependentCoverageAndRejectWrongChannels() {
        val maxima = IntArray(3)
        val residuals = IntArray(6)
        val sensitivity = IntArray(5)
        for (backdrop in listOf(Color(0xff101418), Color(0xff405060), Color(0xffd0d8e0))) for (circle in listOf(false, true)) {
            background = backdrop
            val cache = GlassRimCache()
            val prepared = cache.brushes(extent.center, GlassTone())
            render(GlassGlow(), circle, cache)
            assertFalse("idle never prepares a radial shader", prepared.light.isInitialized())
            render(GlassGlow(1f, 0f), circle, cache)
            assertFalse("broad-only never prepares a radial shader", prepared.light.isInitialized())
            val mask = render(GlassGlow(), circle, mask = true)
            for ((op, alphas) in listOf(1f to 0f, 0f to 1f, 1f to 1f).withIndex()) {
                val points = listOf(Offset(.5f, .5f), Offset(.25f, .5f), Offset(.75f, .5f))
                val actual = points.map { render(GlassGlow(alphas.first, alphas.second, it), circle, cache) }
                for ((p, point) in points.withIndex()) for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16)) {
                    val value = channel(actual[p][y * width + x], shift)
                    val oracle = expected(mask, point, x, y, shift, alphas.first, alphas.second)
                    maxima[op] = max(maxima[op], abs(value - oracle))
                    if (channel(mask[y * width + x], 0) == 0)
                        assertEquals("outside op=$op point=$point ($x,$y)", background.toArgb(), actual[p][y * width + x])
                    if (p == 0) for (axis in 0..2) {
                        val px = if (axis == 1) x else width - 1 - x
                        val py = if (axis == 1) height - 1 - y else y
                        val a = if (axis == 2) actual[1] else actual[0]
                        val b = if (axis == 2) actual[2] else actual[0]
                        val pa = if (axis == 2) points[1] else points[0]
                        val pb = if (axis == 2) points[2] else points[0]
                        val observed = channel(a[y * width + x], shift) - channel(b[py * width + px], shift)
                        val predicted = expected(mask, pa, x, y, shift, alphas.first, alphas.second) -
                            expected(mask, pb, px, py, shift, alphas.first, alphas.second)
                        val i = (if (op == 2) 3 else 0) + axis
                        residuals[i] = max(residuals[i], abs(observed - predicted))
                    }
                }
            }
            val combined = render(GlassGlow(1f, 1f, Offset(.5f, .5f)), circle, cache)
            val alternatives = listOf(GlassGlow(0f, 1f, Offset(.5f, .5f)), GlassGlow(1f, 0f, Offset(.5f, .5f)),
                GlassGlow(2f, 1f, Offset(.5f, .5f)), GlassGlow(1f, 2f, Offset(.5f, .5f)))
                .map { render(it, circle, cache) } + listOf(render(GlassGlow(1f, 1f, Offset(.5f, .5f)), circle, cache, density = .5f))
            alternatives.forEachIndexed { index, alternative ->
                for (i in combined.indices) for (shift in listOf(0, 8, 16))
                    sensitivity[index] = max(sensitivity[index], abs(channel(alternative[i], shift) - channel(combined[i], shift)))
            }
            val local = render(GlassGlow(0f, 1f, Offset(.5f, .5f)), circle, cache)
            assertTrue(channel(local[40 * width + 48], 16) > channel(local[40 * width + 80], 16))
            for (x in 48..80) {
                val red = channel(background.toArgb(), 16)
                val idealA = red + (255 - red) * .12f * (1 - abs(x + .5f - 48) / 60)
                val idealB = red + (255 - red) * .12f * (1 - abs(x + 1.5f - 48) / 60)
                assertTrue(idealA >= idealB)
                assertTrue(idealA.roundToInt() >= idealB.roundToInt())
                assertTrue(channel(local[40 * width + x + 1], 16) - channel(local[40 * width + x], 16) <= 2)
            }
            val brush = prepared.light.value
            render(GlassGlow(0f, 1f, Offset(1.25f, -.25f)), circle, cache, density = .5f)
            assertSame("move/support changes reuse the unit brush", brush, prepared.light.value)
            val changed = cache.brushes(extent.center, GlassTone(.25f, lightInk = true)).light.value
            assertNotSame("tone change replaces preparation", brush, changed)
            val toned = render(GlassGlow(1f, 1f, Offset(.5f, .5f)), circle, tone = GlassTone(.25f, lightInk = true))
            for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16))
                assertTrue("tone-mapped two-fill oracle ($x,$y)", abs(channel(toned[y * width + x], shift) -
                    expected(mask, Offset(.5f, .5f), x, y, shift, 1f, 1f, white = 255f * .75f)) <= 2)
            for (vertical in listOf(false, true)) for (point in listOf(Offset(.2f, .7f), Offset(1.25f, -.25f))) {
                val shape = GlassShape(.2f, -.2f)
                val transformed = render(GlassGlow(1f, 1f, point), circle, shape = shape, vertical = vertical)
                for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16))
                    assertTrue("deformed mapping ($x,$y)", abs(channel(transformed[y * width + x], shift) -
                        expected(mask, point, x, y, shift, 1f, 1f, shape, vertical)) <= 2)
            }
        }
        println("two-fill maxima=${maxima.toList()} symmetry=${residuals.toList()} sensitivity=${sensitivity.toList()}")
        for (op in maxima.indices) assertTrue("oracle op=$op max=${maxima[op]}", maxima[op] <= if (op == 2) 2 else 1)
        for (i in residuals.indices) assertTrue("symmetry $i max=${residuals[i]}", residuals[i] <= if (i < 3) 2 else 4)
        for (i in sensitivity.indices) assertTrue("wrong-channel/support $i witness=${sensitivity[i]}", sensitivity[i] > 2)
    }

    @Test fun actualSurfaceHasVisibleHideAndByteExactIdleSettledAndDisabledPixels() {
        var strength by mutableFloatStateOf(1f)
        val contact = GlassContact()
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var ownerSpeed = 1f
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { EngineTheme(clear = true) { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(contrast = 0f, touchLight = strength)) {
                val speed = LocalMotionSpeed.current
                SideEffect { ownerSpeed = speed }
                source = rememberFrostedBackdrop(Unit, dark = true)
                Box(Modifier.size(240.dp, 160.dp).testTag("frame")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(background)
                        repeat(12) { x -> drawRect(if (x % 2 == 0) Color(0xffac7370) else Color(0xff537b9c),
                            Offset(x * size.width / 12, 0f), Size(size.width / 12, size.height)) }
                    }
                    LiquidGlassSurface(Modifier.padding(24.dp).size(160.dp, 80.dp).testTag("light"), source,
                        cornerRadius = 24.dp, shadow = false, sharedContact = contact, contactPresentation = { GlassShape() })
                }
            }
        } } } }
        compose.awaitGlassBackingSnapshot(source)
        val node = compose.onNodeWithTag("light")
        val frame = compose.onNodeWithTag("frame")
        compose.mainClock.autoAdvance = false
        for (mode in GlassRendererMode.entries) {
            compose.runOnIdle { runtime.request(mode); strength = 1f }
            compose.mainClock.advanceTimeBy(1500)
            val idle = pixels(frame.captureToImage())
            assertArrayEquals(idle, pixels(frame.captureToImage()))
            node.performTouchInput { down(Offset(width * .25f, height * .5f)) }
            var firstPublication = -1L
            repeat(5) { sample ->
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    val glow = contact.state.glow.value
                    if (glow.big > 0f) {
                        if (firstPublication < 0) firstPublication = compose.mainClock.currentTime
                        val seconds = (1.0 / 60 + (compose.mainClock.currentTime - firstPublication) / 1000.0) * ownerSpeed
                        val w = 2 * PI / .1
                        val gain = (1 - (1 + w * seconds) * exp(-w * seconds)).toFloat()
                        val smallToLarge = (80f - 44f) / 116f
                        assertEquals("production broad response mode=$mode sample=$sample", gain, glow.big / (1 - smallToLarge), .09f)
                        assertEquals("production local response mode=$mode sample=$sample", gain,
                            glow.little / (.3f * (1 - smallToLarge) + .2f * smallToLarge), .09f)
                    } else assertTrue("owner publishes within two frames mode=$mode", sample < 2)
                }
            }
            assertTrue("production intermediate response was observed mode=$mode", firstPublication >= 0)
            compose.mainClock.advanceTimeBy(150)
            assertFalse("held mode=$mode", idle.contentEquals(pixels(frame.captureToImage())))
            compose.mainClock.advanceTimeBy(1500)
            compose.runOnIdle { assertTrue(contact.state.momentum.settled) }
            val stable = pixels(frame.captureToImage())
            compose.mainClock.advanceTimeBy(200)
            assertArrayEquals("settled held pixels mode=$mode", stable, pixels(frame.captureToImage()))
            node.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(150)
            assertFalse("hide is still visible mode=$mode", idle.contentEquals(pixels(frame.captureToImage())))
            compose.mainClock.advanceTimeBy(2000)
            compose.runOnIdle {
                assertTrue(contact.state.momentum.settled)
                assertEquals(0f, contact.state.glow.value.big, 0f)
                assertEquals(0f, contact.state.glow.value.little, 0f)
            }
            assertArrayEquals("fully settled release mode=$mode", idle, pixels(frame.captureToImage()))
            compose.runOnIdle { strength = 0f }
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(200)
            assertArrayEquals("zero strength held mode=$mode", idle, pixels(frame.captureToImage()))
            node.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(2000)
            assertArrayEquals("zero strength released mode=$mode", idle, pixels(frame.captureToImage()))
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(1f, GlassAppearance(touchLight = value).normalized().touchLight, 0f)
        assertEquals(1f, GlassAppearance().touchLight, 0f)
        assertEquals(0f, GlassAppearance(touchLight = -1f).normalized().touchLight, 0f)
        assertEquals(1f, GlassAppearance(touchLight = 2f).normalized().touchLight, 0f)
    }

    @Test fun initiatingPointerPublishesNormalizedAndOutsidePositionsWithoutTakingTheGesture() {
        val contact = GlassContact()
        compose.setContent { EngineTheme { LiquidGlassSurface(Modifier.size(160.dp, 80.dp).testTag("contact"),
            source = null, shadow = false, sharedContact = contact, contactPresentation = { GlassShape() }) } }
        val node = compose.onNodeWithTag("contact")
        node.performTouchInput { down(Offset(width * .25f, height * .75f)) }
        compose.runOnIdle {
            assertTrue(contact.pressed)
            assertEquals(.25f, contact.point.x, .001f)
            assertEquals(.75f, contact.point.y, .001f)
        }
        node.performTouchInput { moveTo(Offset(width * 1.25f, height * -.25f)) }
        compose.runOnIdle {
            assertTrue(contact.pressed)
            assertEquals(1.25f, contact.point.x, .001f)
            assertEquals(-.25f, contact.point.y, .001f)
        }
        node.performTouchInput { up() }
        compose.runOnIdle { assertFalse(contact.pressed) }
    }

    @Test fun oneOwnerSurvivesRepressAndHandoffWhileDisabledGlowSchedulesNothing() {
        val shared = GlassBodyState()
        val reference = GlassBodyState()
        val reads = AtomicInteger()
        var held by mutableStateOf(false)
        var modal by mutableStateOf(false)
        var point by mutableStateOf(Offset(.2f, .7f))
        var strength by mutableFloatStateOf(0f)
        var reduced by mutableStateOf(false)
        var ownerSpeed = 1f
        compose.setContent { EngineTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(touchLight = strength), LocalReducedMotion provides reduced) {
                val speed = LocalMotionSpeed.current
                SideEffect { ownerSpeed = speed }
                rememberGlassBody({ reads.incrementAndGet(); 0f }, contact = { if (held) 1f else 0f }, state = shared,
                    active = { !modal }, point = { point })
                rememberGlassBody({ reads.incrementAndGet(); 0f }, contact = { if (held) 1f else 0f }, state = shared,
                    active = { modal }, point = { point })
                rememberGlassBody({ 0f }, contact = { if (held) 1f else 0f }, state = reference, active = { !modal })
                rememberGlassBody({ 0f }, contact = { if (held) 1f else 0f }, state = reference, active = { modal })
                Box(Modifier.size(40.dp))
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1000)
        for (frame in 0 until 180) {
            compose.runOnIdle {
                if (frame in listOf(0, 34, 74)) held = true
                if (frame in listOf(17, 35, 91)) held = false
                if (frame in listOf(23, 62)) modal = !modal
                point = Offset((frame % 5) / 4f, .7f)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("disabled shape frame=$frame", reference.output.value, shared.output.value)
                assertEquals("disabled clock convergence frame=$frame", reference.momentum.settled, shared.momentum.settled)
                assertEquals(GlassGlow(), shared.glow.value)
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.runOnIdle { assertTrue(shared.momentum.settled); reads.set(0); point = Offset(.9f, .1f) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("disabled point changes do not wake either owner", 0, reads.get())
        compose.runOnIdle { strength = 1f; held = true }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertTrue(shared.momentum.settled); point = Offset(.6f, .4f) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("point alone wakes the settled clock", shared.glow.value.point.x in .6f.. .9f && shared.glow.value.point.x != .9f)
            assertTrue("centre follows rather than snapping on MOVE", shared.glow.value.point.x != .6f)
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertEquals(Offset(.6f, .4f), shared.glow.value.point) }
        val model = compose.runOnIdle { GlassMomentum().also { it.blendFrom(shared.momentum) } }
        val before = compose.runOnIdle { shared.glow.value }
        compose.runOnIdle { modal = !modal; point = Offset(.1f, .9f) }
        compose.runOnIdle { assertEquals("handoff has no reset", before, shared.glow.value) }
        model.targetGlow(Offset(.1f, .9f), 1f, 1f)
        model.advance(0f, ownerSpeed / 60, contact = 1f)
        model.finishSettlement()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("handoff integrates exactly once", model.glow, shared.glow.value)
            assertEquals(model.shape, shared.output.value)
            held = false
        }
        compose.mainClock.advanceTimeBy(32)
        val repressed = compose.runOnIdle { GlassMomentum().also { it.blendFrom(shared.momentum) } }
        compose.runOnIdle { point = Offset(.7f, .4f); held = true }
        repressed.targetGlow(Offset(.7f, .4f), 1f, 1f)
        repressed.advance(0f, .016f * ownerSpeed, contact = 1f)
        repressed.finishSettlement()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("re-press keeps the running owner's delta and energy", repressed.glow, shared.glow.value)
            held = false
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.runOnIdle { assertTrue(shared.momentum.settled); reads.set(0) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("enabled fully released owner suspends", 0, reads.get())
        compose.runOnIdle { held = true }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(GlassShape(), shared.output.value)
            assertEquals(GlassGlow(1f, 1f, Offset(.7f, .4f)), shared.glow.value)
            held = false
            point = Offset(.1f, .9f)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(GlassGlow(0f, 0f, Offset(.7f, .4f)), shared.glow.value) }
    }
}

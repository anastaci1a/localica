package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.*

class GlassTouchLightTest {
    @get:Rule val compose = localicaComposeRule()
    private val width = 96
    private val height = 80
    private val extent = Size(width.toFloat(), height.toFloat())
    private val background = Color(0xff405060)
    private fun pixels(image: ImageBitmap): IntArray = IntArray(image.width * image.height).also {
        image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }
    private fun render(point: Offset, alpha: Float, radius: Float, circle: Boolean, cache: GlassRimCache = GlassRimCache(), rimGain: Float = 2f): IntArray {
        val image = ImageBitmap(width, height)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), extent) {
                drawRect(background)
                val path = Path().apply { if (circle) addOval(Rect(Offset(8f, 0f), Size(80f, 80f)))
                    else addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(18f))) }
                clipPath(path) { drawContactLight({ check(alpha > 0f) { "zero alpha read point" }; point },
                    { check(alpha > 0f) { "zero alpha read path" }; path }, alpha, GlassTone(), cache, radius, rimGain) }
            }
            return pixels(image)
        } finally { image.asAndroidBitmap().recycle() }
    }
    private fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255
    private fun diff(a: IntArray, b: IntArray, x: Int, y: Int, shift: Int) = channel(a[y * width + x], shift) - channel(b[y * width + x], shift)

    @Test fun radialFillMatchesAnalyticSrcOverAndKeepsTheOutsideUntouched() {
        val radius = extent.center.getDistance()
        val dark = render(extent.center, 0f, radius, true)
        val lit = render(extent.center, .12f, radius, true)
        var realError = 0f; var byteError = 0; var worst = ""
        for (y in 0 until height) for (x in 0 until width) {
            val distance = (Offset(x + .5f, y + .5f) - extent.center).getDistance()
            if (distance > 41f) assertEquals("outside $x,$y", dark[y * width + x], lit[y * width + x])
            if (distance < 35f) for (shift in listOf(0, 8, 16)) {
                val base = channel(dark[y * width + x], shift)
                val expected = base + (255 - base) * .12f * (1 - distance / radius)
                val actual = channel(lit[y * width + x], shift)
                val error = abs(expected - actual)
                if (error > realError) { realError = error; worst = "$x,$y channel=$shift expected=$expected actual=$actual" }
                byteError = max(byteError, abs(expected.roundToInt() - actual))
            }
        }
        val fillOnly = render(extent.center, .12f, radius, false, rimGain = 0f)
        val both = render(extent.center, .12f, radius, false)
        val maxima = IntArray(3); val witnesses = Array(3) { "" }
        for (y in 18 until height - 18) for (x in listOf(0, width - 1)) for (shift in listOf(0, 8, 16)) {
            val falloff = 1 - (Offset(x + .5f, y + .5f) - extent.center).getDistance() / radius
            val base = channel(background.toArgb(), shift)
            val fill = channel(fillOnly[y * width + x], shift)
            val actual = channel(both[y * width + x], shift)
            val expected = listOf(base + (255 - base) * .12f * falloff,
                fill + (255 - fill) * .24f * falloff,
                255 - (255 - base) * (1 - .12f * falloff) * (1 - .24f * falloff))
            for (i in 0..2) {
                val error = abs(expected[i].roundToInt() - if (i == 0) fill else actual)
                if (error > maxima[i]) { maxima[i] = error; witnesses[i] = "$x,$y channel=$shift expected=${expected[i]} fill=$fill actual=$actual" }
            }
        }
        android.util.Log.i("Localica642", "analytic realMax=$realError roundedMax=$byteError worst=$worst; straightRim fill/second/composed=${maxima.toList()} witnesses=${witnesses.toList()}")
        assertTrue("SrcOver realMax=$realError roundedMax=$byteError worst=$worst", byteError <= 1)
        for (i in 0..2) assertTrue("native op=$i error=${maxima[i]} witness=${witnesses[i]}", maxima[i] <= if (i == 2) 2 else 1)
        val atCenter = diff(lit, dark, 48, 40, 16)
        val nearEdge = diff(lit, dark, 48, 4, 16)
        val atRim = diff(lit, dark, 48, 0, 16)
        assertTrue(atCenter > nearEdge)
        assertTrue("the rim adds light at the same radial distance", atRim > diff(lit, dark, 48, 3, 16))
        for (x in 48..80) assertTrue(diff(lit, dark, x, 40, 16) >= diff(lit, dark, x + 1, 40, 16))
    }

    @Test fun centeredAndMirroredContactsKeepBothAxesSymmetricAndReusePreparation() {
        val radius = extent.center.getDistance()
        val maxima = IntArray(6); val witnesses = Array(6) { "" }
        for (circle in listOf(false, true)) {
            val cache = GlassRimCache()
            val dark = render(extent.center, 0f, radius, circle)
            val lit = render(extent.center, .12f, radius, circle, cache)
            val left = render(Offset(24f, 40f), .12f, radius, circle, cache)
            val right = render(Offset(72f, 40f), .12f, radius, circle, cache)
            val fill = render(extent.center, .12f, radius, circle, rimGain = 0f)
            val fillLeft = render(Offset(24f, 40f), .12f, radius, circle, rimGain = 0f)
            val fillRight = render(Offset(72f, 40f), .12f, radius, circle, rimGain = 0f)
            for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16)) {
                for (op in 0..1) for (axis in 0..2) {
                    val centered = if (op == 0) fill else lit
                    val a = if (axis == 2) (if (op == 0) fillLeft else left) else centered
                    val b = if (axis == 2) (if (op == 0) fillRight else right) else centered
                    val px = if (axis == 1) x else width - 1 - x
                    val py = if (axis == 1) height - 1 - y else y
                    val first = diff(a, dark, x, y, shift)
                    val second = diff(b, dark, px, py, shift)
                    val error = abs(first - second)
                    val i = op * 3 + axis
                    if (error > maxima[i]) {
                        maxima[i] = error
                        witnesses[i] = "ops=${op + 1} circle=$circle channel=$shift ($x,$y) pairedWith=$px,$py first=$first second=$second error=$error"
                    }
                }
            }
            assertSame(cache.light(Offset.Zero, radius, GlassTone()), cache.light(extent.center, radius * 2, GlassTone()))
            assertSame(cache.lightStroke(2.6f), cache.lightStroke(2.6f))
            assertNotSame(cache.light(Offset.Zero, radius, GlassTone()), cache.light(Offset.Zero, radius, GlassTone(.25f)))
        }
        android.util.Log.i("Localica642", "symmetry fill then composed x/y/mirrorMax=${maxima.toList()} worstPairs=${witnesses.toList()}")
        for (i in 0..5) assertTrue("symmetry axis=$i max=${maxima[i]} pair=${witnesses[i]}", maxima[i] <= if (i < 3) 2 else 4)
    }

    @Test fun actualSurfaceReturnsByteExactlyAfterReleaseAndStrengthZeroDrawsNothing() {
        var strength by mutableFloatStateOf(1f)
        var contact = GlassContact()
        compose.setContent { EngineTheme(clear = true) {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(contrast = 0f, touchLight = strength)) {
                val source = rememberFrostedBackdrop(Unit)
                Box(Modifier.size(240.dp, 160.dp).testTag("frame")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(background)
                        for (x in 0..12) drawRect(if (x % 2 == 0) Color(0xffac7370) else Color(0xff537b9c),
                            Offset(x * size.width / 12, 0f), Size(size.width / 12, size.height))
                    }
                    LiquidGlassSurface(Modifier.padding(24.dp).size(160.dp, 80.dp).testTag("light"), source,
                        cornerRadius = 24.dp, shadow = false, sharedContact = contact, contactPresentation = { GlassShape() })
                }
            }
        } }
        val frame = compose.onNodeWithTag("frame")
        val node = compose.onNodeWithTag("light")
        compose.waitForIdle()
        val idle = pixels(frame.captureToImage())
        assertArrayEquals(idle, pixels(frame.captureToImage()))
        node.performTouchInput { down(Offset(width * .25f, height * .5f)) }
        compose.mainClock.advanceTimeBy(200)
        assertFalse("a held surface lights", idle.contentEquals(pixels(frame.captureToImage())))
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(320)
        compose.waitForIdle()
        assertArrayEquals("release is byte exact", idle, pixels(frame.captureToImage()))
        compose.runOnIdle { strength = 0f }
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        assertArrayEquals("zero strength is byte exact while held", idle, pixels(frame.captureToImage()))
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(320)
        assertArrayEquals(idle, pixels(frame.captureToImage()))
        val digest = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(idle.size * 4).apply { idle.forEach(::putInt) }.array()).joinToString("") { "%02x".format(it) }
        android.util.Log.i("Localica642", "idleSha256=$digest pixels=${idle.size}")
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
            File(it, "glass-touch-idle.sha256").apply { parentFile?.mkdirs(); writeText("$digest  GlassTouchLightTest-idle\n") }
        }
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

    @Test fun calibrationShowsThreeTokenSetsAndProductionHoldFollowRelease() {
        val capture = InstrumentationRegistry.getArguments().getString("localica642Calibrate") == "true"
        compose.setContent { EngineTheme(clear = true) { Column(Modifier.background(Color(0xff303038)).padding(8.dp)) {
            Text("Touch light calibration · 100 ms in / 270 ms out", color = Color.White)
            Row {
                listOf(Triple(.8f, .10f, 1.5f), Triple(1f, .12f, 2f), Triple(1.2f, .14f, 2.5f)).forEachIndexed { i, (radius, peak, rim) ->
                    Column(Modifier.width(104.dp)) {
                        Text("${'A' + i}: r $radius\nα $peak · rim $rim", color = Color.White)
                        for (point in listOf(Offset(.5f, .5f), Offset(.25f, .7f))) Canvas(Modifier.size(96.dp, 80.dp)) {
                            drawRect(background)
                            val path = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(20.dp.toPx()))) }
                            clipPath(path) { drawContactLight({ Offset(point.x * size.width, point.y * size.height) }, { path }, peak,
                                GlassTone(), GlassRimCache(), size.center.getDistance() * radius, rim) }
                        }
                    }
                }
            }
            Text("Production B · direct follow · hold and move", color = Color.White)
            LiquidGlassSurface(Modifier.size(280.dp, 120.dp).testTag("production"), source = null,
                tint = background, cornerRadius = 24.dp, shadow = false) { Text("Finger-origin light", color = Color.White) }
        } } }
        compose.waitForIdle()
        if (capture) Thread.sleep(4000)
        val node = compose.onNodeWithTag("production")
        node.performTouchInput { down(Offset(width * .2f, height * .5f)) }
        compose.mainClock.advanceTimeBy(200)
        if (capture) Thread.sleep(1000)
        node.performTouchInput { moveTo(Offset(width * .8f, height * .5f), delayMillis = 500) }
        compose.mainClock.advanceTimeBy(200)
        if (capture) Thread.sleep(1500)
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        if (capture) Thread.sleep(1000)
    }
}

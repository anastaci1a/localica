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
    private fun render(point: Offset, alpha: Float, circle: Boolean, cache: GlassRimCache = GlassRimCache(), rimGain: Float? = null, density: Float = 1f,
        coverage: (DrawScope.(Path) -> Unit)? = null): IntArray {
        val image = ImageBitmap(width, height)
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), extent) {
                drawRect(if (coverage == null) background else Color.Black)
                val path = Path().apply { if (circle) addOval(Rect(Offset(8f, 0f), Size(80f, 80f)))
                    else addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(18f))) }
                clipPath(path) {
                    if (coverage != null) coverage(path)
                    else {
                        val position = { check(alpha > 0f) { "zero alpha read point" }; point }
                        val silhouette = { check(alpha > 0f) { "zero alpha read path" }; path }
                        if (rimGain == null) drawContactLight(position, silhouette, alpha, GlassTone(), cache)
                        else drawContactLight(position, silhouette, alpha, GlassTone(), cache, rimGain = rimGain)
                    }
                }
            }
            return pixels(image)
        } finally { image.asAndroidBitmap().recycle() }
    }
    private fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255
    private fun diff(a: IntArray, b: IntArray, x: Int, y: Int, shift: Int) = channel(a[y * width + x], shift) - channel(b[y * width + x], shift)

    @Test fun radialFillMatchesAnalyticSrcOverAndKeepsTheOutsideUntouched() {
        val radius = extent.center.getDistance()
        val dark = render(extent.center, 0f, true)
        val lit = render(extent.center, .12f, true)
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
        val fillOnly = render(extent.center, .12f, false, rimGain = 0f)
        val both = render(extent.center, .12f, false)
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
        var increase = Int.MIN_VALUE; var adjacent = ""
        fun ideal(px: Int) = 64 + (255 - 64) * .12f * (1 - (Offset(px + .5f, 40.5f) - extent.center).getDistance() / radius)
        for (x in 48..80) {
            val first = diff(lit, dark, x, 40, 16); val second = diff(lit, dark, x + 1, 40, 16)
            assertTrue("ideal fill decreases at $x", ideal(x) >= ideal(x + 1))
            assertTrue("rounded fill decreases at $x", ideal(x).roundToInt() >= ideal(x + 1).roundToInt())
            if (second - first > increase) {
                increase = second - first
                adjacent = "($x,40)/(${x + 1},40) first=$first second=$second"
            }
        }
        android.util.Log.i("Localica642", "adjacent increaseMax=$increase worstPair=$adjacent")
        assertTrue("SrcOver realMax=$realError roundedMax=$byteError worst=$worst", byteError <= 1)
        for (i in 0..2) assertTrue("native op=$i error=${maxima[i]} witness=${witnesses[i]}", maxima[i] <= if (i == 2) 2 else 1)
        val atCenter = diff(lit, dark, 48, 40, 16)
        val nearEdge = diff(lit, dark, 48, 4, 16)
        val atRim = diff(lit, dark, 48, 0, 16)
        assertTrue(atCenter > nearEdge)
        assertTrue("the rim adds light at the same radial distance", atRim > diff(lit, dark, 48, 3, 16))
        assertTrue("sampled adjacent increase=$increase worstPair=$adjacent", increase <= 2)
    }

    @Test fun centeredAndMirroredContactsKeepBothAxesSymmetricAndReusePreparation() {
        val radius = extent.center.getDistance()
        val maxima = IntArray(6); val witnesses = Array(6) { "" }
        val coverageMax = IntArray(6); val coveragePairs = Array(6) { "" }
        val residualMax = IntArray(6); val residualPairs = Array(6) { "" }
        val rimWitnessMax = IntArray(4); val rimWitnesses = Array(4) { "" }
        val pixelMax = IntArray(4); val pixelRealMax = FloatArray(4); val pixelWitnesses = Array(4) { "" }
        val secondMax = IntArray(2); val secondWitnesses = Array(2) { "" }
        for (circle in listOf(false, true)) {
            val cache = GlassRimCache()
            val dark = render(extent.center, 0f, circle)
            val lit = render(extent.center, .12f, circle, cache)
            val left = render(Offset(24f, 40f), .12f, circle, cache)
            val right = render(Offset(72f, 40f), .12f, circle, cache)
            val fill = render(extent.center, .12f, circle, rimGain = 0f)
            val fillLeft = render(Offset(24f, 40f), .12f, circle, rimGain = 0f)
            val fillRight = render(Offset(72f, 40f), .12f, circle, rimGain = 0f)
            val fillCoverage = render(extent.center, 0f, circle, coverage = { drawRect(Color.White) })
            val rimCoverage = render(extent.center, 0f, circle, coverage = { path ->
                clipPath(path) { drawPath(path, Color.White, style = Stroke(2.6f)) }
            })
            val wideCoverage = render(extent.center, 0f, circle, coverage = { path ->
                clipPath(path) { drawPath(path, Color.White, style = Stroke(5.2f)) }
            })
            val doubledRim = render(extent.center, .12f, circle, rimGain = 4f)
            val doubledWidth = render(extent.center, .12f, circle, density = 2f)
            fun reference(point: Offset, x: Int, y: Int, shift: Int, op: Int, rimMask: IntArray = rimCoverage): Float {
                val falloff = max(0f, 1 - (Offset(x + .5f, y + .5f) - point).getDistance() / radius)
                val fillAlpha = .12f * falloff * channel(fillCoverage[y * width + x], 0) / 255
                val rimAlpha = if (op == 0) 0f else .24f * falloff * channel(rimMask[y * width + x], 0) / 255
                val first = (255 - (255 - channel(background.toArgb(), shift)) * (1 - fillAlpha)).roundToInt()
                return 255 - (255 - first) * (1 - rimAlpha)
            }
            if (circle) {
                val tokenCoverage = render(extent.center, 0f, circle, coverage = { path ->
                    clipPath(path) { drawPath(path, Color.White, style = Stroke(io.github.anastaci1a.localica.ui.spec.TouchLightRimWidth.toPx())) }
                })
                for (side in 0..1) {
                    val point = Offset(if (side == 0) 24f else 72f, 40f)
                    val x = if (side == 0) 76 else 19; val y = 66; val index = y * width + x
                    val actual = if (side == 0) left else right
                    val first = if (side == 0) fillLeft else fillRight
                    val explicit = render(point, .12f, circle, rimGain = 2f)
                    val rimAlpha = .24f * max(0f, 1 - (Offset(x + .5f, y + .5f) - point).getDistance() / radius) * channel(rimCoverage[index], 0) / 255
                    for (shift in listOf(0, 8, 16)) {
                        val fillByte = channel(first[index], shift); val composed = channel(actual[index], shift)
                        val second = 255 - (255 - fillByte) * (1 - rimAlpha)
                        android.util.Log.i("Localica642", "pin ($x,$y) contact=$point channel=$shift nativeFill=$fillByte composed=$composed explicit=${channel(explicit[index], shift)} defaultExplicitEqual=${actual.contentEquals(explicit)} fillMask=${channel(fillCoverage[index], 0)} rimMask=${channel(rimCoverage[index], 0)} tokenMask=${channel(tokenCoverage[index], 0)} masksEqual=${rimCoverage.contentEquals(tokenCoverage)} idealFill=${reference(point, x, y, shift, 0)} stagedFinal=${reference(point, x, y, shift, 1)} secondFromActual=$second")
                    }
                }
            }
            if (!circle) for (x in 0 until width) for (shift in listOf(0, 8, 16)) {
                val expected = reference(extent.center, x, 40, shift, 1).roundToInt()
                val alternatives = intArrayOf(channel(fill[40 * width + x], shift), channel(doubledRim[40 * width + x], shift),
                    reference(extent.center, x, 40, shift, 1, wideCoverage).roundToInt(), channel(doubledWidth[40 * width + x], shift))
                for (i in 0..3) {
                    val error = abs(alternatives[i] - expected)
                    if (error > rimWitnessMax[i]) {
                        rimWitnessMax[i] = error
                        rimWitnesses[i] = "($x,40) channel=$shift expected=$expected alternative=${alternatives[i]} rimCoverage=${channel(rimCoverage[40 * width + x], 0)} wideCoverage=${channel(wideCoverage[40 * width + x], 0)}"
                    }
                }
            }
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
                    val pointA = if (axis == 2) Offset(24f, 40f) else extent.center
                    val pointB = if (axis == 2) Offset(72f, 40f) else extent.center
                    val expectedA = reference(pointA, x, y, shift, op)
                    val expectedB = reference(pointB, px, py, shift, op)
                    val prediction = expectedA.roundToInt() - expectedB.roundToInt()
                    val pair = "ops=${op + 1} circle=$circle channel=$shift ($x,$y)/($px,$py) actual=$first/$second expected=$expectedA/$expectedB rounded=${expectedA.roundToInt()}/${expectedB.roundToInt()} predictedDelta=$prediction signedResidual=${first - second - prediction}"
                    val residual = abs(first - second - prediction)
                    if (residual > residualMax[i]) { residualMax[i] = residual; residualPairs[i] = pair }
                    val mask = if (op == 0) fillCoverage else rimCoverage
                    val maskA = channel(mask[y * width + x], 0); val maskB = channel(mask[py * width + px], 0)
                    if (shift == 0 && abs(maskA - maskB) > coverageMax[i]) {
                        coverageMax[i] = abs(maskA - maskB); coveragePairs[i] = "$pair coverage=$maskA/$maskB"
                    }
                    if (axis != 1) for (endpoint in 0..1) {
                        val index = if (endpoint == 0) y * width + x else py * width + px
                        val expected = if (endpoint == 0) expectedA else expectedB
                        val full = channel(fillCoverage[index], 0) == 255 &&
                            (op == 0 || channel(rimCoverage[index], 0) in listOf(0, 255))
                        val group = op * 2 + if (full) 0 else 1
                        val actual = channel((if (endpoint == 0) a else b)[index], shift)
                        val pixelError = abs(actual - expected.roundToInt())
                        val point = if (endpoint == 0) pointA else pointB
                        val sx = if (endpoint == 0) x else px; val sy = if (endpoint == 0) y else py
                        if (op == 1) {
                            val first = if (axis == 2) (if (endpoint == 0) fillLeft else fillRight) else fill
                            val rimAlpha = .24f * max(0f, 1 - (Offset(sx + .5f, sy + .5f) - point).getDistance() / radius) * channel(rimCoverage[index], 0) / 255
                            val second = 255 - (255 - channel(first[index], shift)) * (1 - rimAlpha)
                            val error = abs(actual - second.roundToInt()); val partial = if (full) 0 else 1
                            if (error > secondMax[partial]) { secondMax[partial] = error; secondWitnesses[partial] = "$pair endpoint=$endpoint secondFromActual=$second" }
                        }
                        pixelRealMax[group] = max(pixelRealMax[group], abs(actual - expected))
                        if (pixelError > pixelMax[group]) { pixelMax[group] = pixelError; pixelWitnesses[group] = "$pair endpoint=$endpoint" }
                    }
                    if (error > maxima[i]) {
                        maxima[i] = error
                        witnesses[i] = "$pair error=$error residual=$residual fillCoverage=${channel(fillCoverage[y * width + x], 0)}/${channel(fillCoverage[py * width + px], 0)} rimCoverage=${channel(rimCoverage[y * width + x], 0)}/${channel(rimCoverage[py * width + px], 0)}"
                    }
                }
            }
            assertSame(cache.light(Offset.Zero, radius, GlassTone()), cache.light(extent.center, radius * 2, GlassTone()))
            assertSame(cache.lightStroke(2.6f), cache.lightStroke(2.6f))
            assertNotSame(cache.light(Offset.Zero, radius, GlassTone()), cache.light(Offset.Zero, radius, GlassTone(.25f)))
        }
        android.util.Log.i("Localica642", "isolated second full/partial=${secondMax.toList()} worst=${secondWitnesses.toList()}")
        android.util.Log.i("Localica642", "rim sensitivity omitted/native doubled gain/ideal doubled width/native doubled width roundedError=${rimWitnessMax.toList()} witnesses=${rimWitnesses.toList()}")
        android.util.Log.i("Localica642", "coverage masks fill/rim x/y/mirrorMax=${coverageMax.toList()} worstPairs=${coveragePairs.toList()}")
        android.util.Log.i("Localica642", "coverage reference fill full/partial, composed full/partial roundedMax=${pixelMax.toList()} realMax=${pixelRealMax.toList()} worst=${pixelWitnesses.toList()}")
        android.util.Log.i("Localica642", "coverage symmetry residualMax=${residualMax.toList()} worstPairs=${residualPairs.toList()}")
        android.util.Log.i("Localica642", "symmetry fill then composed x/y/mirrorMax=${maxima.toList()} worstPairs=${witnesses.toList()}")
        for (i in 0..3) assertTrue("covered pixel=$i error=${pixelMax[i]} witness=${pixelWitnesses[i]}", pixelMax[i] <= if (i < 2) 1 else 2)
        for (i in 0..5) assertTrue("absolute residual=$i max=${residualMax[i]} pair=${residualPairs[i]}", residualMax[i] <= if (i < 3) 2 else 4)
        for (i in 0..3) assertTrue("rim sensitivity=$i error=${rimWitnessMax[i]} witness=${rimWitnesses[i]}", rimWitnessMax[i] > if (i == 2) 4 else 2)
    }

    @Test fun actualSurfaceReturnsByteExactlyAfterReleaseAndStrengthZeroDrawsNothing() {
        var strength by mutableFloatStateOf(1f)
        var contact = GlassContact()
        lateinit var source: FrostedBackdrop
        compose.setContent { EngineTheme(clear = true) {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(contrast = 0f, touchLight = strength)) {
                source = rememberFrostedBackdrop(Unit)
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
        compose.awaitGlassBackingSnapshot(source)
        compose.waitForIdle()
        val idle = pixels(frame.captureToImage())
        assertArrayEquals(idle, pixels(frame.captureToImage()))
        compose.mainClock.autoAdvance = false
        node.performTouchInput { down(Offset(width * .25f, height * .5f)) }
        compose.mainClock.advanceTimeBy(150)
        val held = pixels(frame.captureToImage())
        assertFalse("a held surface lights", idle.contentEquals(held))
        compose.mainClock.advanceTimeBy(170)
        assertArrayEquals("rise settles by 150 ms", held, pixels(frame.captureToImage()))
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(150)
        assertFalse("fall remains lit after 150 ms", idle.contentEquals(pixels(frame.captureToImage())))
        compose.mainClock.advanceTimeBy(170)
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
        compose.setContent { EngineTheme(clear = true) { Column(Modifier.fillMaxSize().background(Color(0xff303038)).padding(start = 8.dp, top = 32.dp, end = 8.dp, bottom = 8.dp)) {
            Text("Touch light · 100 ms in / 270 ms out", color = Color.White, fontSize = 14.sp)
            Row {
                listOf(Triple(.8f, .10f, 1.5f), Triple(1f, .12f, 2f), Triple(1.2f, .14f, 2.5f)).forEachIndexed { i, (radius, peak, rim) ->
                    Column(Modifier.width(104.dp)) {
                        Text("${'A' + i} · r $radius\npeak $peak\nrim $rim", Modifier.height(56.dp), color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                        for (point in listOf(Offset(.5f, .5f), Offset(.25f, .7f))) Canvas(Modifier.size(96.dp, 80.dp)) {
                            drawRect(background)
                            val path = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(20.dp.toPx()))) }
                            clipPath(path) { drawContactLight({ Offset(point.x * size.width, point.y * size.height) }, { path }, peak,
                                GlassTone(), GlassRimCache(), size.center.getDistance() * radius, rim) }
                        }
                    }
                }
            }
            Text("Production B · follow 0 ms · hold / move / release", color = Color.White, fontSize = 12.sp)
            LiquidGlassSurface(Modifier.size(280.dp, 120.dp).testTag("production"), source = null,
                tint = background, cornerRadius = 24.dp, shadow = false) { Text("Finger-origin light", Modifier.padding(16.dp), color = Color.White, fontSize = 14.sp) }
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
        if (capture) Thread.sleep(8000)
    }
}

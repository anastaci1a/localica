package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clickable
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
import io.github.anastaci1a.localica.ui.spec.FlexGlow
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
                    if (mask) drawRect(Color.White) else drawTouchLightFixture(glow, shape, vertical, cache, sample = background) { drawRect(background) }
                }
            }
            return pixels(image)
        } finally { image.asAndroidBitmap().recycle() }
    }
    private fun referenceMask(radius: Float): Triple<IntArray, Int, Float> {
        var sigma = radius
        val limit = if (radius <= 4f) 2f else if (radius <= 8f) 4f else 8f
        while (sigma > limit) sigma /= 2f
        val n = ceil(2.5f * sigma).toInt()
        val weights = (-n..n).map { exp(-it.toDouble() * it / (2.0 * sigma * sigma)) }
        val coefficients = weights.map { (it * 16384 / weights.sum()).toInt() }
        val side = 2 * (ceil(sigma).toInt() + ceil(sigma * 2.8199565410614014f + .5f).toInt() + 1) + 1
        val disk = IntArray(side * side) { i ->
            val x = (i % side - side / 2).toDouble(); val y = (i / side - side / 2).toDouble()
            val r = sigma.toDouble(); val xx = max(0.0, abs(x) - .5); val yy = max(0.0, abs(y) - .5)
            val area = if (xx * xx + yy * yy >= r * r) 0.0
                else if ((abs(x) + .5).pow(2) + (abs(y) + .5).pow(2) <= r * r) 1.0
                else {
                    val left = max(x - .5, -r); val right = min(x + .5, r)
                    var sum = 0.0
                    // Independent vertical-strip integration of the circular source.
                    for (sample in 0 until 8192) {
                        val at = left + (sample + .5) * (right - left) / 8192
                        val cap = sqrt(max(0.0, r * r - at * at))
                        sum += max(0.0, min(y + .5, cap) - max(y - .5, -cap))
                    }
                    sum * (right - left) / 8192
                }
            (area.toFloat() * 255.99000549316406f).toInt()
        }
        fun convolve(input: IntArray, dx: Int, dy: Int) = IntArray(input.size) { i ->
            var sum = 8192
            for (j in -n..n) {
                val x = i % side + j * dx; val y = i / side + j * dy
                if (x in 0 until side && y in 0 until side) sum += coefficients[j + n] * input[y * side + x]
            }
            sum / 16384
        }
        return Triple(convolve(convolve(disk, 1, 0), 0, 1), side, radius / sigma)
    }

    @Test fun fluidSurfaceAndGlowShareOneTransformThroughHoldReleaseAndAnchor() {
        val image = ImageBitmap(1, 1)
        val raw = Offset(.8f, .2f); val tracked = Offset(.3f, .7f)
        val body = GlassMomentum()
        body.advance(0f, 0.0, contact = 1f, point = raw)
        val frames = mutableListOf<GlassShape>()
        for ((seconds, contact) in listOf(.05 to 1f, .3 to 1f, 0.0 to 0f, .05 to 0f, .2 to 0f, .5 to 0f)) {
            body.advance(0f, seconds, contact = contact, point = raw)
            frames += body.shape
        }
        assertTrue("held frames include nonzero explicit lift", frames.first().lift > 0f)
        assertTrue("release returns the lift", abs(frames.last().lift) < abs(frames[1].lift))
        try {
            for (shape in frames) for (vertical in listOf(false, true)) for (anchored in listOf(false, true)) {
                val extent = if (anchored) Size(300f, 240f) else Size(96f, 80f)
                val applied = shape.bodyTransform(raw, extent, vertical, anchored)
                if (anchored) {
                    assertEquals(0f, applied.drift.x, 0f); assertEquals(0f, applied.drift.y, 0f)
                    assertEquals(shape.scaleX(vertical), applied.scale.x, 0f)
                    assertEquals(shape.scaleY(vertical), applied.scale.y, 0f)
                } else if (shape.lift > 0f) assertTrue("off-centre lift drifts", applied.drift.getDistance() > 0f)
                var draws = 0
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), extent) {
                    val witness = object : DrawScope by this {
                        override fun drawCircle(brush: Brush, radius: Float, center: Offset, alpha: Float,
                            style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) {
                            val mapped = floatArrayOf(center.x, center.y)
                            @Suppress("DEPRECATION")
                            val matrix = drawContext.canvas.nativeCanvas.matrix
                            matrix.mapPoints(mapped)
                            assertEquals(tracked.x * extent.width, mapped[0], .0001f)
                            assertEquals(tracked.y * extent.height, mapped[1], .0001f)
                            draws++
                        }
                    }
                    witness.drawGlassBody(applied) {
                        drawContactGlow(GlassGlow(0f, 1f, tracked), { shape }, vertical, GlassTone(), GlassRimCache(), applied)
                    }
                }
                assertEquals("one nested local draw", 1, draws)
            }
        } finally { image.asAndroidBitmap().recycle() }
    }

    @Test fun broadAndLocalFillsMatchIndependentCoverageAndRejectWrongChannels() {
        val broadMatrix = arrayOf(doubleArrayOf(1.1575000286102295, -.14309999346733093, -.014399999752640724),
            doubleArrayOf(-.042500000447034836, 1.056999921798706, -.014499999582767487),
            doubleArrayOf(-.04259999841451645, -.14299999177455902, 1.18559992313385))
        val localMatrix = arrayOf(doubleArrayOf(1.3937000036239624, -.357699990272522, -.035999998450279236),
            doubleArrayOf(-.1062999963760376, 1.1424000263214111, -.03610000014305115),
            doubleArrayOf(-.10639999806880951, -.35749998688697815, 1.4638999700546265))
        val reference = referenceMask(60f)
        val (disk, side, pixels) = reference
        fun maskAt(point: Offset, x: Int, y: Int): Double {
            val xx = (x + .5 - width * point.x) / pixels + side / 2.0 - .5
            val yy = (y + .5 - height * point.y) / pixels + side / 2.0 - .5
            val ix = floor(xx).toInt(); val iy = floor(yy).toInt()
            fun byte(dx: Int, dy: Int) = if (ix + dx in 0 until side && iy + dy in 0 until side) disk[(iy + dy) * side + ix + dx].toDouble() else 0.0
            val fx = xx - ix; val fy = yy - iy
            return ((byte(0, 0) * (1 - fx) + byte(1, 0) * fx) * (1 - fy) + (byte(0, 1) * (1 - fx) + byte(1, 1) * fx) * fy) / 255
        }
        val maxima = IntArray(3)
        val idealMaxima = IntArray(3)
        val sensitivity = IntArray(2)
        for (backdrop in listOf(Color(0xff101418), Color(0xff405060), Color(0xffd0d8e0))) for (circle in listOf(false, true)) {
            background = backdrop
            val cache = GlassRimCache()
            val maskField = cache.javaClass.getDeclaredField("mask").apply { isAccessible = true }
            val idle = render(GlassGlow(), circle, cache)
            assertNull(maskField.get(cache))
            render(GlassGlow(1f, 0f), circle, cache)
            assertNull("broad-only paint has no disk preparation", maskField.get(cache))
            val coverage = render(GlassGlow(), circle, mask = true)
            val rgb = doubleArrayOf(backdrop.red.toDouble(), backdrop.green.toDouble(), backdrop.blue.toDouble())
            val bright = broadMatrix.map { row -> (row.indices.sumOf { row[it] * rgb[it] } + .04999999701976776).coerceIn(0.0, 1.0) }
            val p = backdrop.luminance().toDouble().pow(1.25)
            val white = Math.fma(1 - p, .9, p * .25).toFloat()
            val decoded = if (white <= .04045f) white / 12.92f else ((white + .055f) / 1.055f).pow(2.4f)
            val encoded = if (decoded <= .0031308f) decoded * 12.92f else 1.055f * decoded.pow(1f / 2.4f) - .055f
            val q = min(encoded, 255f / 256f)
            val gain = round((q / (1f - q) + 1f) / .0001f) * .0001f
            val cleaned = localMatrix.map { row -> row.map { v -> (sign(v * gain) * floor(abs((v.toFloat() * gain).toDouble()) / .0001 + .5) * .0001).toFloat().toDouble() } }
            for ((op, alphas) in listOf(1f to 0f, 0f to 1f, 1f to 1f).withIndex()) {
                for (point in listOf(Offset(.5f, .5f), Offset(.25f, .5f), Offset(.75f, .5f))) {
                    val actual = render(GlassGlow(alphas.first, alphas.second, point), circle, cache)
                    for (y in 0 until height) for (x in 0 until width) for ((c, shift) in listOf(16, 8, 0).withIndex()) {
                        val coverageByte = channel(coverage[y * width + x], 0)
                        if (coverageByte == 0) assertEquals("outside silhouette", idle[y * width + x], actual[y * width + x])
                        if (coverageByte != 255) continue
                        val weight = alphas.second * maskAt(point, x, y)
                        val idealIncoming = if (alphas.first > 0) bright else rgb.toList()
                        val idealDodge = cleaned[c].indices.sumOf { cleaned[c][it] * idealIncoming[it] }.coerceIn(0.0, 1.0)
                        val ideal = ((idealIncoming[c] * (1 - weight) + idealDodge * weight) * 255).roundToInt()
                        val incoming = idealIncoming.map { (it * 255).roundToInt() / 255.0 }
                        val dodge = (cleaned[c].indices.sumOf { cleaned[c][it] * incoming[it] }.coerceIn(0.0, 1.0) * 255).roundToInt() / 255.0
                        val expected = ((incoming[c] * (1 - weight) + dodge * weight) * 255).roundToInt()
                        maxima[op] = max(maxima[op], abs(channel(actual[y * width + x], shift) - expected))
                        idealMaxima[op] = max(idealMaxima[op], abs(channel(actual[y * width + x], shift) - ideal))
                    }
                }
            }
            val combined = render(GlassGlow(1f, 1f, Offset(.5f, .5f)), circle, cache)
            val prepared = maskField.get(cache)
            for ((i, glow) in listOf(GlassGlow(0f, 1f, Offset(.5f, .5f)), GlassGlow(1f, 0f, Offset(.5f, .5f))).withIndex()) {
                val wrong = render(glow, circle, cache)
                for (j in combined.indices) for (shift in listOf(0, 8, 16)) sensitivity[i] = max(sensitivity[i], abs(channel(combined[j], shift) - channel(wrong[j], shift)))
            }
            assertSame("movement reuses the current disk", prepared, maskField.get(cache))
            assertArrayEquals("negative optical energy draws nothing", idle, render(GlassGlow(-.22f, -.03f), circle))
        }
        println("disk/YCC storedY maxima=" + maxima.toList() + " ideal maxima=" + idealMaxima.toList() + " sensitivity=" + sensitivity.toList())
        for (op in maxima.indices) assertTrue("independent oracle op=" + op + " error=" + maxima[op], maxima[op] <= if (op == 2) 2 else 1)
        for (i in sensitivity.indices) assertTrue("wrong channel=" + i, sensitivity[i] > 4)
    }

    @Test fun nativeBackingPreservesExteriorAndRendersRetiringRecords() {
        var light by mutableStateOf(GlassGlow())
        compose.setContent { EngineTheme { MaterialTheme(colorScheme = darkColorScheme()) {
            val cache = remember { GlassRimCache() }
            Box(Modifier.size(128.dp, 96.dp).testTag("native-light")) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xffa46238))
                    drawRect(Color(0xff326fa1), size = Size(size.width / 2, size.height))
                }
                ContactGlowBacking(Modifier.fillMaxSize(), { light }, { GlassShape() }, false, cache,
                    sample = { Color(0xff405060) }, geometry = { Rect(size.width / 4, size.height / 6, size.width * .75f, size.height * 5 / 6) to 12.dp.toPx() }) {
                    Canvas(Modifier.fillMaxSize()) { drawRoundRect(Color(0xff405060),
                        Offset(size.width / 4, size.height / 6), Size(size.width / 2, size.height * 2 / 3), CornerRadius(12.dp.toPx())) }
                }
            }
        } } }
        val node = compose.onNodeWithTag("native-light")
        val idleImage = node.captureToImage(); val idle = pixels(idleImage)
        val local = GlassGlow(little = 1f, point = Offset(.5f, .5f))
        compose.runOnIdle { light = local }
        val held = pixels(node.captureToImage())
        assertFalse("native local is visible", idle.contentEquals(held))
        for (y in 0 until idleImage.height) for (x in 0 until idleImage.width) {
            if (x < idleImage.width / 5 || x >= idleImage.width * 4 / 5)
                assertEquals("outside bounded host x=$x y=$y", idle[y * idleImage.width + x], held[y * idleImage.width + x])
        }
        compose.runOnIdle { light = GlassGlow(retiring = listOf(local)) }
        assertArrayEquals("a retiring record uses the same native paint", held, pixels(node.captureToImage()))
        compose.runOnIdle { light = GlassGlow(big = .7f, little = .8f, point = Offset(.5f, .5f), retiring = listOf(local.copy(little = .6f))) }
        assertFalse("ordered broad and local passes contribute", held.contentEquals(pixels(node.captureToImage())))
        compose.runOnIdle { light = GlassGlow() }
        assertArrayEquals("idle removes every native pass", idle, pixels(node.captureToImage()))
    }

    @Test fun nonScalingSurfaceObservesLightWithoutMovingItsForegroundOrTakingTheClick() {
        var strength by mutableFloatStateOf(1f)
        var clicks = 0
        compose.setContent { EngineTheme(clear = true) { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(touchLight = strength)) {
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(Modifier.size(192.dp, 96.dp).testTag("light-only-frame")) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xff31516f)) }
                    LiquidGlassSurface(Modifier.size(160.dp, 80.dp).testTag("light-only")
                        .then(Modifier.clickable(interactionSource = interaction, indication = null) { clicks++ }),
                        source = null, pressFeedback = false, shadow = false, tint = Color(0xff405060)) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(Color.White, Offset(size.width * .4f, size.height * .4f), Size(size.width * .2f, size.height * .2f)) }
                    }
                }
            }
        } } }
        val node = compose.onNodeWithTag("light-only")
        val frame = compose.onNodeWithTag("light-only-frame")
        compose.mainClock.autoAdvance = false
        val idle = pixels(frame.captureToImage())
        node.performTouchInput { down(Offset(width * .25f, height * .75f)) }
        compose.mainClock.advanceTimeBy(200)
        val held = pixels(frame.captureToImage())
        assertFalse("shared light-only admission is visible", idle.contentEquals(held))
        fun white(image: IntArray) = image.indices.filter { image[it] == Color.White.toArgb() }.toIntArray()
        assertTrue("foreground witness exists", white(idle).isNotEmpty())
        assertArrayEquals("non-scaling foreground retains every white pixel", white(idle), white(held))
        assertEquals(0, clicks)
        compose.runOnIdle { strength = 0f }
        compose.mainClock.advanceTimeByFrame()
        assertArrayEquals("the optical setting removes light-only output", idle, pixels(frame.captureToImage()))
        node.performTouchInput { up() }
        compose.runOnIdle { assertEquals("the original clickable keeps activation", 1, clicks) }
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
            compose.mainClock.advanceTimeBy(10000)
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
            compose.mainClock.advanceTimeBy(10000)
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
                rememberGlassBody({ 0f }, contact = { if (held) 1f else 0f }, state = reference, active = { !modal }, point = { point })
                rememberGlassBody({ 0f }, contact = { if (held) 1f else 0f }, state = reference, active = { modal }, point = { point })
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
        val trackingStart = compose.runOnIdle { assertTrue(shared.momentum.settled); point = Offset(.6f, .4f); shared.glow.value.point }
        compose.mainClock.advanceTimeByFrame() // h=0 admission observes the point without advancing it.
        val trackingAdmission = compose.mainClock.currentTime
        compose.runOnIdle { assertEquals("tracking admission preserves the displayed point", trackingStart, shared.glow.value.point) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("point alone wakes the settled clock", shared.glow.value.point.x in .6f.. .9f && shared.glow.value.point.x != .9f)
            val h = (compose.mainClock.currentTime - trackingAdmission) / 1000.0 * ownerSpeed
            val w = 2 * PI / FlexGlow.TrackingResponse
            val residual = (1 + w * h) * exp(-w * h)
            val expected = Offset((.6f + (trackingStart.x.toDouble() - .6f) * residual).toFloat(),
                (.4f + (trackingStart.y.toDouble() - .4f) * residual).toFloat())
            assertEquals("mature tracking matches the independent critical step", expected, shared.glow.value.point)
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertEquals(Offset(.6f, .4f), shared.glow.value.point) }
        val model = compose.runOnIdle { GlassMomentum().also { it.blendFrom(shared.momentum) } }
        val before = compose.runOnIdle { shared.glow.value }
        compose.runOnIdle { modal = !modal; point = Offset(.1f, .9f) }
        compose.runOnIdle { assertEquals("handoff has no reset", before, shared.glow.value) }
        model.advance(0f, 0.0, contact = 1f, point = point)
        model.targetGlow(Offset(.1f, .9f), 1f, 1f)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("handoff admission preserves energy: expected x=${model.glow.point.x} y=${model.glow.point.y}; actual x=${shared.glow.value.point.x} y=${shared.glow.value.point.y}", model.glow, shared.glow.value)
            assertEquals(model.shape, shared.output.value)
        }
        val handoffAdmission = compose.mainClock.currentTime
        compose.mainClock.advanceTimeByFrame()
        model.advance(0f, (compose.mainClock.currentTime - handoffAdmission) / 1000.0 * ownerSpeed, contact = 1f, point = point)
        model.targetGlow(Offset(.1f, .9f), 1f, 1f)
        model.finishSettlement()
        compose.runOnIdle {
            assertEquals("the first positive handoff interval advances exactly once", model.glow, shared.glow.value)
            assertEquals(model.shape, shared.output.value)
            held = false
        }
        compose.mainClock.advanceTimeBy(32)
        val repressed = compose.runOnIdle { GlassMomentum().also { it.blendFrom(shared.momentum) } }
        compose.runOnIdle { point = Offset(.7f, .4f); held = true }
        repressed.advance(0f, .016 * ownerSpeed, contact = 1f, point = point)
        repressed.targetGlow(Offset(.7f, .4f), 1f, 1f)
        repressed.finishSettlement()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("re-press preserves retired energy and starts a fresh current record", repressed.glow, shared.glow.value)
            held = false
        }
        compose.mainClock.advanceTimeBy(10000)
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
        compose.runOnIdle {
            assertEquals(0f, shared.glow.value.big, 0f)
            assertEquals(0f, shared.glow.value.little, 0f)
        }
    }
}

package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.colorspace.ColorSpaces
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
    private val profile = floatArrayOf(.3935f, .3573f, .2671f, .1638f, .0819f, .0332f, .0108f, 0f)
    private fun referenceProfile(radial: Float): Float {
        val segment = min(6, (radial * 2).toInt())
        return if (radial >= 3.5f) 0f else profile[segment] + (profile[segment + 1] - profile[segment]) * (radial * 2 - segment)
    }
    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also {
        image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }
    private fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255
    private fun render(glow: GlassGlow, circle: Boolean, cache: GlassRimCache = GlassRimCache(), mask: Boolean = false,
        tone: GlassTone = GlassTone(), shape: GlassShape = GlassShape(), vertical: Boolean = false, density: Float = 1f, inset: Float = 0f, painter: (DrawScope.() -> Unit)? = null): IntArray {
        val image = ImageBitmap(width, height, ImageBitmapConfig.Argb8888, colorSpace = ColorSpaces.Srgb)
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), extent) {
                drawRect(if (mask) Color.Black else background)
                val path = Path().apply {
                    if (circle) addOval(Rect(Offset(8f + inset, inset), Size(80f - 2 * inset, 80f - 2 * inset)))
                    else addRoundRect(RoundRect(Rect(Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset)), CornerRadius(18f)))
                }
                clipPath(path) {
                    if (mask) drawRect(Color.White) else if (painter != null) painter() else drawContactGlow(glow, { shape }, vertical, tone, cache)
                }
            }
            return pixels(image)
        } finally { image.asAndroidBitmap().recycle() }
    }
    private fun expectedBroad(mask: IntArray, x: Int, y: Int, shift: Int, big: Float): Int {
        val coverage = channel(mask[y * width + x], 0) / 255f
        val lift = if (android.os.Build.VERSION.SDK_INT >= 29) Math.rint((.05f * big * 255f).toDouble()).toFloat() else 255f * .05f * big
        return min(255f, channel(background.toArgb(), shift) + lift * coverage).roundToInt()
    }
    private fun referenceBrush(lightInk: Boolean, linear: Boolean = false): Brush {
        val grey = if (lightInk) .85f else .45f
        return Brush.radialGradient(*profile.mapIndexed { i, alpha ->
            i / 7f to Color(grey, grey, grey, if (linear) .3935f * (1 - i / 7f) else alpha)
        }.toTypedArray(), center = Offset.Zero, radius = 1f)
    }
    private fun reference(mask: IntArray, glow: GlassGlow, circle: Boolean, lightInk: Boolean = false,
        shape: GlassShape = GlassShape(), vertical: Boolean = false, density: Float = 1f): IntArray {
        if (android.os.Build.VERSION.SDK_INT < 29 || glow.little <= 0f) return IntArray(width * height) { i ->
            (255 shl 24) or (expectedBroad(mask, i % width, i / width, 16, glow.big.coerceAtLeast(0f)) shl 16) or
                (expectedBroad(mask, i % width, i / width, 8, glow.big.coerceAtLeast(0f)) shl 8) or
                expectedBroad(mask, i % width, i / width, 0, glow.big.coerceAtLeast(0f))
        }
        return render(glow, circle, density = density, painter = {
            if (glow.big > 0f) drawRect(Color.White, alpha = .05f * glow.big, blendMode = BlendMode.Plus)
            val sx = exp((if (vertical) -shape.strain else shape.strain) - shape.depth / 2)
            val sy = exp((if (vertical) shape.strain else -shape.strain) - shape.depth / 2)
            val cx = width * (.5f + (glow.point.x - .5f) / sx)
            val cy = height * (.5f + (glow.point.y - .5f) / sy)
            val support = min(.75f * min(width, height), 80f * density) * 3.5f * glow.scale
            val shader = referenceBrush(lightInk)
            translate(cx, cy) { scale(support, support, pivot = Offset.Zero) {
                drawCircle(shader, 1f, Offset.Zero, alpha = min(1f, glow.little), blendMode = BlendMode.ColorDodge)
            } }
        })
    }
    private fun inputs(probe: ImageBitmap, bounds: Size, glow: GlassGlow, shape: GlassShape = GlassShape(),
        vertical: Boolean = false, lightInk: Boolean = false, density: Float = 1f, mutant: String = ""): Brush? {
        val order = mutableListOf<String>()
        var observed: Brush? = null
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(probe), bounds) {
            val witness = object : DrawScope by this {
                override fun drawRect(color: Color, topLeft: Offset, size: Size, alpha: Float,
                    style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) {
                    order += "broad"
                    assertEquals("broad source", Color.White, color)
                    assertEquals("broad origin", Offset.Zero, topLeft)
                    assertEquals("broad bounds", bounds, size)
                    assertEquals("broad blend", BlendMode.Plus, blendMode)
                    assertEquals("broad primitive", Fill, style)
                    assertNull("broad filter", colorFilter)
                    assertEquals("broad alpha", .05f * glow.big, alpha, 0f)
                    val paint = Paint().apply { this.color = color; this.alpha = alpha }
                    assertEquals("native broad alpha", Math.rint((.05f * glow.big * 255f).toDouble()).toInt(), paint.asFrameworkPaint().alpha)
                }
                override fun drawCircle(brush: Brush, radius: Float, center: Offset, alpha: Float,
                    style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode) {
                    if (mutant == "order") order.add(0, "local") else order += "local"
                    assertEquals("local blend", BlendMode.ColorDodge, if (mutant == "blend") BlendMode.SrcOver else blendMode)
                    assertEquals("unit primitive", 1f, if (mutant == "support") radius * 2 else radius, 0f)
                    assertEquals("unit origin", Offset.Zero, center)
                    assertEquals("local primitive", Fill, style)
                    assertNull("local filter", colorFilter)
                    assertTrue("platform alpha stays within its range", alpha in 0f..1f)
                    assertEquals("local alpha", min(1f, glow.little), alpha, 0f)
                    val paint = Paint()
                    brush.applyTo(bounds, paint, alpha)
                    assertEquals("native local alpha", Math.rint((min(1f, glow.little) * 255f).toDouble()).toInt(), paint.asFrameworkPaint().alpha)
                    @Suppress("DEPRECATION")
                    val matrix = drawContext.canvas.nativeCanvas.matrix
                    val mapped = floatArrayOf(center.x, center.y)
                    matrix.mapPoints(mapped)
                    assertEquals(glow.point.x * bounds.width, bounds.width / 2 + (mapped[0] - bounds.width / 2) * shape.scaleX(vertical), .0001f)
                    assertEquals(glow.point.y * bounds.height, bounds.height / 2 + (mapped[1] - bounds.height / 2) * shape.scaleY(vertical), .0001f)
                    val values = FloatArray(9).also { matrix.getValues(it) }
                    if (mutant == "center") values[2] += 1f
                    if (mutant == "scale") values[0] *= 2f
                    val sx = exp((if (vertical) -shape.strain else shape.strain) - shape.depth / 2)
                    val sy = exp((if (vertical) shape.strain else -shape.strain) - shape.depth / 2)
                    val support = min(.75f * min(bounds.width, bounds.height), 80f * density) * 3.5f * glow.scale
                    val expected = floatArrayOf(support, 0f, bounds.width * (.5f + (glow.point.x - .5f) / sx),
                        0f, support, bounds.height * (.5f + (glow.point.y - .5f) / sy), 0f, 0f, 1f)
                    assertArrayEquals("local matrix", expected, values, .0001f)
                    observed = when (mutant) {
                        "polarity" -> referenceBrush(!lightInk)
                        "profile" -> referenceBrush(lightInk, linear = true)
                        else -> brush
                    }
                }
            }
            witness.drawContactGlow(glow, { shape }, vertical, GlassTone(.25f, lightInk = lightInk), GlassRimCache())
        }
        val expectedOrder = buildList {
            if (glow.big > 0f) add("broad")
            if (glow.little > 0f && android.os.Build.VERSION.SDK_INT >= 29) add("local")
        }
        assertEquals("draw order/admission", expectedOrder, order)
        return observed
    }
    private fun shader(brush: Brush, lightInk: Boolean, alpha: Float) {
        for (backdrop in listOf(Color.Black, Color.White)) {
            background = backdrop
            fun image(shader: Brush) = render(GlassGlow(), false, painter = {
                translate(48f, 40f) { scale(36f, 36f, pivot = Offset.Zero) {
                    drawCircle(shader, 1f, Offset.Zero, alpha = alpha, blendMode = BlendMode.SrcOver)
                } }
            })
            val actual = image(brush)
            val expected = image(referenceBrush(lightInk))
            var maximum = 0
            for (i in actual.indices) for (shift in listOf(0, 8, 16))
                maximum = max(maximum, abs(channel(actual[i], shift) - channel(expected[i], shift)))
            println("neutral shader lightInk=$lightInk alpha=$alpha backdrop=$backdrop maximum=$maximum")
            assertTrue("neutral shader maximum=$maximum", maximum <= 1)
        }
    }
    private fun coverageReference(circle: Boolean): IntArray {
        val bitmap = android.graphics.Bitmap.createBitmap(96, 80, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            assertFalse("software coverage reference", canvas.isHardwareAccelerated)
            canvas.drawColor(android.graphics.Color.BLACK)
            val path = android.graphics.Path().apply {
                if (circle) addOval(8f, 0f, 88f, 80f, android.graphics.Path.Direction.CW)
                else addRoundRect(0f, 0f, 96f, 80f, 18f, 18f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(path)
            canvas.drawColor(android.graphics.Color.WHITE)
            return IntArray(96 * 80).also { bitmap.getPixels(it, 0, 96, 0, 0, 96, 80) }
        } finally { bitmap.recycle() }
    }
    private fun coverage(actual: IntArray, expected: IntArray, circle: Boolean) {
        val counts = IntArray(3)
        val maxima = IntArray(3)
        var actualArea = 0L
        var expectedArea = 0L
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val a = channel(actual[i], 0)
            val e = channel(expected[i], 0)
            val category = if (e == 0) 0 else if (e == 255) 2 else 1
            counts[category]++
            actualArea += a
            expectedArea += e
            for (shift in listOf(0, 8, 16)) maxima[category] = max(maxima[category], abs(channel(actual[i], shift) - channel(expected[i], shift)))
            val qx = abs(x + .5f - 48f) - 30f
            val qy = abs(y + .5f - 40f) - 22f
            val distance = if (circle) hypot(x + .5f - 48f, y + .5f - 40f) - 40f
                else hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - 18f
            if (distance < -1f) assertEquals("coverage full footprint", 255, a)
            if (distance > 1f) assertEquals("coverage empty footprint", 0, a)
            if (e == 0) assertEquals("coverage outside identity", Color.Black.toArgb(), actual[i])
        }
        assertTrue("coverage categories", counts.all { it > 0 })
        println("coverage circle=$circle counts=${counts.toList()} sums=$actualArea/$expectedArea areaDifference=${abs(actualArea - expectedArea) / 255.0} maxima=${maxima.toList()}")
        assertTrue("coverage byte comparison", maxima.all { it <= 1 })
    }
    private fun rejects(label: String, assertion: String, block: () -> Unit) {
        val failure = try { block(); null } catch (error: AssertionError) { error }
        assertNotNull("mutant $label must fail", failure)
        assertTrue("mutant $label failed its named assertion: $failure", failure!!.message.orEmpty().contains(assertion))
        println("mutant=$label rejectedBy=$assertion")
    }

    @Test fun nativeInputsShaderCoverageAndRoundingMatchIndependentFixtures() {
        val probe = ImageBitmap(1, 1, ImageBitmapConfig.Argb8888, colorSpace = ColorSpaces.Srgb)
        try {
            assertEquals(android.graphics.Bitmap.Config.ARGB_8888, probe.asAndroidBitmap().config)
            assertTrue(probe.asAndroidBitmap().colorSpace!!.isSrgb)
            assertFalse(Canvas(probe).nativeCanvas.isHardwareAccelerated)
            println("glow bitmap software=true config=ARGB_8888 colorSpace=sRGB api=${android.os.Build.VERSION.SDK_INT}")
            val alphas = mutableListOf(-.1f, 0f, .5f, 1f, 1.25f)
            for (lower in listOf(0, 1, 126, 127, 128, 129)) {
                val tie = (lower + .5f) / 255f
                val below = Float.fromBits(tie.toRawBits() - 1)
                val above = Float.fromBits(tie.toRawBits() + 1)
                assertEquals("exact half-byte tie", lower + .5f, tie * 255f, 0f)
                assertTrue(below * 255f < lower + .5f && above * 255f > lower + .5f)
                alphas += listOf(below, tie, above)
                if (lower % 2 == 0) rejects("half-up lower=$lower", "native tie alpha") {
                    val paint = Paint().apply { alpha = tie }
                    paint.asFrameworkPaint().alpha = (tie * 255f).roundToInt()
                    assertEquals("native tie alpha", Math.rint((tie * 255f).toDouble()).toInt(), paint.asFrameworkPaint().alpha)
                }
            }
            for (alpha in alphas) {
                val paint = Paint().apply { this.alpha = alpha.coerceAtLeast(0f).coerceAtMost(1f) }
                assertEquals("native paint alpha", Math.rint((alpha.coerceIn(0f, 1f) * 255f).toDouble()).toInt(), paint.asFrameworkPaint().alpha)
                for (lightInk in listOf(false, true)) {
                    val brush = inputs(probe, extent, GlassGlow(.5f, alpha), lightInk = lightInk)
                    if (brush != null) shader(brush, lightInk, min(1f, alpha))
                }
            }
            for (scale in listOf(1f, 2f, 4f)) for (density in listOf(.25f, 1f, 2.5f))
                inputs(probe, extent, GlassGlow(.5f, .5f, Offset(.25f, .75f), scale), GlassShape(.2f, -.2f), density = density)
            for (big in listOf(-.1f, 0f, .5f, 1f)) inputs(probe, extent, GlassGlow(big, 0f))
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                for ((mutant, assertion) in listOf("blend" to "local blend", "order" to "draw order/admission",
                    "support" to "unit primitive", "center" to "local matrix", "scale" to "local matrix"))
                    rejects(mutant, assertion) { inputs(probe, extent, GlassGlow(.5f, .5f), mutant = mutant) }
                for (mutant in listOf("polarity", "profile")) rejects(mutant, "neutral shader") {
                    shader(inputs(probe, extent, GlassGlow(.5f, .5f), mutant = mutant)!!, false, .5f)
                }
            }
            for (circle in listOf(false, true)) {
                val expected = coverageReference(circle)
                coverage(render(GlassGlow(), circle, mask = true), expected, circle)
                val changed = render(GlassGlow(), circle, mask = true, inset = 1f)
                assertTrue("clipping mutant preserves categories", listOf(0, 255).all { value -> changed.any { channel(it, 0) == value } } && changed.any { channel(it, 0) in 1..254 })
                rejects("clipping", "coverage") { coverage(changed, expected, circle) }
                background = Color(0xff405060)
                val mask = render(GlassGlow(), circle, mask = true)
                for (alpha in alphas) for (big in listOf(0f, .5f)) {
                    val glow = GlassGlow(big, alpha)
                    val actual = render(glow, circle)
                    val reference = reference(mask, glow, circle)
                    var maximum = 0
                    for (i in actual.indices) for (shift in listOf(0, 8, 16))
                        maximum = max(maximum, abs(channel(actual[i], shift) - channel(reference[i], shift)))
                    assertTrue("alpha boundary raster big=$big little=$alpha max=$maximum", maximum <= if (big == 0f) 1 else 2)
                }
            }
            println("native alpha fixtures=${alphas.size} ties=6 adjacent=12 rounding mutants=3")
        } finally { probe.asAndroidBitmap().recycle() }
    }

    @Test fun broadAndLocalFillsMatchIndependentCoverageAndRejectWrongChannels() {
        val probe = ImageBitmap(1, 1)
        try {
            for (size in listOf(Size(96f, 96f), Size(240f, 48f), Size(48f, 240f)))
                for (vertical in listOf(false, true)) for (shape in listOf(GlassShape(), GlassShape(.2f, -.3f), GlassShape(-.3f, .2f)))
                    for (x in listOf(-.25f, 0f, .2f, .5f, 1f, 1.25f)) for (y in listOf(-.25f, 0f, .5f, 1f, 1.25f)) {
                        inputs(probe, size, GlassGlow(0f, 1f, Offset(x, y)), shape, vertical)
                    }
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(probe), Size(1f, 1f)) {
                for (glow in listOf(GlassGlow(), GlassGlow(1f, 0f), GlassGlow(-.22f, -.03f)))
                    drawContactGlow(glow, { error("zero/negative local light must not read shape") }, false, GlassTone(), GlassRimCache())
            }
        } finally { probe.asAndroidBitmap().recycle() }
        assertArrayEquals("negative optical energy draws nothing", render(GlassGlow(), false), render(GlassGlow(-.22f, -.03f), false))
        val maxima = IntArray(3)
        val coverageMaxima = Array(3) { IntArray(3) }
        val comparedChannels = LongArray(3)
        var fixturePairs = 0
        val worst = Array(3) { "" }
        var toneMaximum = 0
        val residuals = IntArray(6)
        val sensitivity = IntArray(5)
        val supported = android.os.Build.VERSION.SDK_INT >= 29
        for (backdrop in listOf(Color.Black, Color(0xff101418), Color(0xff405060), Color(0xffd0d8e0), Color.White)) for (circle in listOf(false, true)) {
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
                val references = points.map { reference(mask, GlassGlow(alphas.first, alphas.second, it), circle) }
                fixturePairs += points.size
                for ((p, point) in points.withIndex()) for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16)) {
                    val value = channel(actual[p][y * width + x], shift)
                    val oracle = channel(references[p][y * width + x], shift)
                    val error = abs(value - oracle)
                    val coverage = channel(mask[y * width + x], 0)
                    val category = if (coverage == 0) 0 else if (coverage == 255) 2 else 1
                    coverageMaxima[op][category] = max(coverageMaxima[op][category], error)
                    comparedChannels[category]++
                    if (error > maxima[op]) {
                        maxima[op] = error
                        worst[op] = "backdrop=$backdrop circle=$circle point=$point x=$x y=$y shift=$shift coverage=${channel(mask[y * width + x], 0)} actual=$value expected=$oracle"
                    }
                    if (channel(mask[y * width + x], 0) == 0)
                        assertEquals("outside op=$op point=$point ($x,$y)", background.toArgb(), actual[p][y * width + x])
                    if (p == 0) for (axis in 0..2) {
                        val px = if (axis == 1) x else width - 1 - x
                        val py = if (axis == 1) height - 1 - y else y
                        val a = if (axis == 2) actual[1] else actual[0]
                        val b = if (axis == 2) actual[2] else actual[0]
                        val observed = channel(a[y * width + x], shift) - channel(b[py * width + px], shift)
                        val ra = if (axis == 2) references[1] else references[0]
                        val rb = if (axis == 2) references[2] else references[0]
                        val predicted = channel(ra[y * width + x], shift) - channel(rb[py * width + px], shift)
                        assertTrue("independent actual symmetry", abs(observed) <= if (op == 2) 4 else 2)
                        assertTrue("independent reference symmetry", abs(predicted) <= if (op == 2) 4 else 2)
                        val i = (if (op == 2) 3 else 0) + axis
                        residuals[i] = max(residuals[i], abs(observed - predicted))
                    }
                }
            }
            // Unsaturated alphas keep doubled-channel mutants observable after valid paint admission.
            val combined = render(GlassGlow(.5f, .5f, Offset(.5f, .5f)), circle, cache)
            val alternatives = listOf(GlassGlow(0f, .5f, Offset(.5f, .5f)), GlassGlow(.5f, 0f, Offset(.5f, .5f)),
                GlassGlow(1f, .5f, Offset(.5f, .5f)), GlassGlow(.5f, 1f, Offset(.5f, .5f)))
                .map { render(it, circle, cache) } + listOf(render(GlassGlow(.5f, .5f, Offset(.5f, .5f)), circle, cache, density = .25f))
            alternatives.forEachIndexed { index, alternative ->
                for (i in combined.indices) for (shift in listOf(0, 8, 16))
                    sensitivity[index] = max(sensitivity[index], abs(channel(alternative[i], shift) - channel(combined[i], shift)))
            }
            val local = render(GlassGlow(0f, 1f, Offset(.5f, .5f)), circle, cache)
            if (supported && backdrop != Color.Black && backdrop != Color.White)
                assertTrue(channel(local[40 * width + 48], 16) > channel(local[40 * width + 80], 16))
            if (!supported) assertArrayEquals("unsupported local fill is an exact no-op", render(GlassGlow(), circle), local)
            for (x in 48..80) {
                val red = channel(background.toArgb(), 16)
                val delta = if (supported) min(255f, red / .55f) - red else 0f
                val idealA = red + delta * referenceProfile(abs(x + .5f - 48) / 60)
                val idealB = red + delta * referenceProfile(abs(x + 1.5f - 48) / 60)
                assertTrue(idealA >= idealB)
                assertTrue(idealA.roundToInt() >= idealB.roundToInt())
                assertTrue(channel(local[40 * width + x + 1], 16) - channel(local[40 * width + x], 16) <= 2)
            }
            val brush = prepared.light.value
            render(GlassGlow(0f, 1f, Offset(1.25f, -.25f)), circle, cache, density = .5f)
            assertSame("move/support changes reuse the unit brush", brush, cache.brushes(extent.center, GlassTone()).light.value)
            val changed = cache.brushes(extent.center, GlassTone(.25f, lightInk = true)).light.value
            assertNotSame("tone change replaces preparation", brush, changed)
            val toned = render(GlassGlow(1f, 1f, Offset(.5f, .5f)), circle, tone = GlassTone(.25f, lightInk = true))
            val toneReference = reference(mask, GlassGlow(1f, 1f, Offset(.5f, .5f)), circle, lightInk = true)
            fixturePairs++
            if (backdrop == Color.Black && !circle) for (point in listOf(Offset(17f, 0f), Offset(11f, 1f))) {
                val i = point.y.toInt() * width + point.x.toInt()
                println("historical coordinate=$point actual=${toned[i]} expected=${toneReference[i]} coverage=${channel(mask[i], 0)}")
            }
            for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16)) {
                val actual = channel(toned[y * width + x], shift)
                val oracle = channel(toneReference[y * width + x], shift)
                toneMaximum = max(toneMaximum, abs(actual - oracle))
                assertTrue("tone-mapped two-fill oracle ($x,$y) shift=$shift actual=$actual expected=$oracle backdrop=$backdrop circle=$circle coverage=${channel(mask[y * width + x], 0)}", abs(actual - oracle) <= 2)
            }
            for (scale in listOf(1f, 2f, 4f)) {
                val expanded = render(GlassGlow(.5f, .5f, Offset(.25f, .75f), scale), circle)
                val expandedReference = reference(mask, GlassGlow(.5f, .5f, Offset(.25f, .75f), scale), circle)
                fixturePairs++
                for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16))
                    assertTrue("expanded clipped profile scale=$scale ($x,$y)", abs(channel(expanded[y * width + x], shift) -
                        channel(expandedReference[y * width + x], shift)) <= 2)
            }
            for (vertical in listOf(false, true)) for (point in listOf(Offset(.2f, .7f), Offset(1.25f, -.25f))) {
                val shape = GlassShape(.2f, -.2f)
                val transformed = render(GlassGlow(1f, 1f, point), circle, shape = shape, vertical = vertical)
                val transformedReference = reference(mask, GlassGlow(1f, 1f, point), circle, shape = shape, vertical = vertical)
                fixturePairs++
                for (y in 0 until height) for (x in 0 until width) for (shift in listOf(0, 8, 16))
                    assertTrue("deformed mapping ($x,$y)", abs(channel(transformed[y * width + x], shift) -
                        channel(transformedReference[y * width + x], shift)) <= 2)
            }
        }
        println("raster fixturePairs=$fixturePairs comparedChannelsOutsidePartialFull=${comparedChannels.toList()} maximaOutsidePartialFull=${coverageMaxima.map { it.toList() }}")
        println("two-fill maxima=${maxima.toList()} worst=${worst.toList()} symmetry=${residuals.toList()} sensitivity=${sensitivity.toList()} toneMaximum=$toneMaximum")
        for (op in maxima.indices) assertTrue("oracle op=$op max=${maxima[op]} ${worst[op]}", maxima[op] <= if (op == 2) 2 else 1)
        for (i in residuals.indices) assertTrue("symmetry $i max=${residuals[i]}", residuals[i] <= if (i < 3) 2 else 4)
        for (i in sensitivity.indices) {
            if (supported || i == 0 || i == 2) assertTrue("wrong-channel/support $i witness=${sensitivity[i]}", sensitivity[i] > 4)
            else assertEquals("unsupported local mutants cannot change pixels", 0, sensitivity[i])
        }
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
            assertFalse(contact.far)
        }
        node.performTouchInput { moveTo(Offset(width * 1.25f, height * -.25f)) }
        compose.runOnIdle {
            assertTrue(contact.pressed)
            assertEquals(1f, contact.point.x, 0f)
            assertEquals(0f, contact.point.y, 0f)
            assertTrue(contact.far)
        }
        node.performTouchInput { up() }
        compose.runOnIdle { assertFalse(contact.pressed) }
        node.performTouchInput { down(center); moveBy(Offset(width * 50f / 160f, 0f)) }
        compose.runOnIdle { assertFalse("exactly 50 dp does not dissipate", contact.far) }
        node.performTouchInput { moveBy(Offset(1f, 0f)) }
        compose.runOnIdle { assertTrue("strictly over raw travel threshold", contact.far) }
        node.performTouchInput { moveTo(center) }
        compose.runOnIdle { assertTrue("return cannot restore a latched gesture", contact.far) }
        node.performTouchInput { cancel() }
        compose.runOnIdle { assertFalse("cancellation releases contact", contact.pressed) }
        node.performTouchInput { down(center) }
        compose.runOnIdle { assertFalse("next admitted DOWN clears latch", contact.far) }
        node.performTouchInput { up() }
    }

    @Test fun oneOwnerSurvivesRepressAndHandoffWhileDisabledGlowSchedulesNothing() {
        val shared = GlassBodyState()
        val reference = GlassBodyState()
        val reads = AtomicInteger()
        var held by mutableStateOf(false)
        var modal by mutableStateOf(false)
        var point by mutableStateOf(Offset(.2f, .7f))
        var far by mutableStateOf(false)
        var strength by mutableFloatStateOf(0f)
        var reduced by mutableStateOf(false)
        var ownerSpeed = 1f
        compose.setContent { EngineTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(touchLight = strength), LocalReducedMotion provides reduced) {
                val speed = LocalMotionSpeed.current
                SideEffect { ownerSpeed = speed }
                rememberGlassBody({ reads.incrementAndGet(); 0f }, contact = { if (held) 1f else 0f }, state = shared,
                    active = { !modal }, point = { point }, far = { far })
                rememberGlassBody({ reads.incrementAndGet(); 0f }, contact = { if (held) 1f else 0f }, state = shared,
                    active = { modal }, point = { point }, far = { far })
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
                far = frame % 2 == 0
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
        compose.runOnIdle { strength = 1f; held = true; far = false }
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
        compose.runOnIdle { modal = !modal; point = Offset(.1f, .9f); far = true }
        compose.runOnIdle { assertEquals("handoff has no reset", before, shared.glow.value) }
        model.targetGlow(Offset(.1f, .9f), 1f, 1f, far = true)
        model.advance(0f, (1f / 60f) * ownerSpeed, contact = 1f)
        model.finishSettlement()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("handoff integrates exactly once: expected x=${model.glow.point.x} y=${model.glow.point.y}; actual x=${shared.glow.value.point.x} y=${shared.glow.value.point.y}", model.glow, shared.glow.value)
            assertEquals(model.shape, shared.output.value)
            held = false
        }
        compose.mainClock.advanceTimeBy(32)
        val repressed = compose.runOnIdle { GlassMomentum().also { it.blendFrom(shared.momentum) } }
        compose.runOnIdle { point = Offset(.7f, .4f); held = true; far = false }
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
        compose.runOnIdle {
            assertEquals(0f, shared.glow.value.big, 0f)
            assertEquals(0f, shared.glow.value.little, 0f)
        }
    }
}

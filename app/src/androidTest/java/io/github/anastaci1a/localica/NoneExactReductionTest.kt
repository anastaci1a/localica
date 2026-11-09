package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** #691 exact pass: the None material's fill is the coverage-weighted mean of the PRERASTERIZED backdrop
 * texture over the material's full bounds. Two budgets (lead judgment, 2026-11-09):
 *  (a) ideal-mean error within .015 per channel on Android 13+ (legacy area bound below), on fixtures
 *      point sampling fails (1 px stripes at several widths and phases, 2 px checkerboards, text bands,
 *      list rows, glyph runs, artwork split, alpha edges) and fixtures that expose a wrong block mapping
 *      (half splits, quadrants, a translated impulse, a gradient), at footprints just above, in the middle
 *      of and just below a power-of-two interval;
 *  (b) drift between the pre-#691 bilinear 2:1 chain and the block-mean chain on the same device: max
 *      4/255 and mean 1/255 per channel, including low alpha and the styled (tinted, contrasted) output.
 * Expected means are integrated from the immutable source bitmap, never from an analytic guess of what
 * the rasterizer drew. Measured drift is written to additionalTestOutputDir as a receipt. */
class NoneExactReductionTest {
    @get:Rule val compose = localicaComposeRule()
    private val plain = GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
    private val styled = GlassAppearance(whiteTint = .35f, contrast = .3f, shadowStrength = 0f)
    private val sourceWidth = 640
    private val sourceHeight = 320
    private val origin = Offset(20f, 20f)
    private val tolerance = .015f
    private val driftMaxCodes = 4f
    private val driftMeanCodes = 1f

    @After fun restoreChain() { MaterialAreaReduction.legacyHalvings = false }

    /** A pattern rasterized once into an immutable ARGB bitmap of the source size. */
    private class Fixture(val name: String, val paint: android.graphics.Canvas.(Float) -> Unit)

    private fun fixtures(): List<Fixture> {
        fun stripes(width: Int) = Fixture("vertical stripes $width of 32") { phase ->
            drawColor(android.graphics.Color.BLACK)
            val paint = Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true }
            for (x in -40 until sourceWidth + 40) if (Math.floorMod(x, 32) < width)
                drawRect(x + phase, 0f, x + phase + 1f, sourceHeight.toFloat(), paint)
        }
        val white = Paint().apply { color = android.graphics.Color.WHITE }
        return listOf(
            Fixture("left black right white split") { phase ->
                drawColor(android.graphics.Color.BLACK); drawRect(150f + phase, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(), white)
            },
            Fixture("top black bottom white split") { phase ->
                drawColor(android.graphics.Color.BLACK); drawRect(0f, 84f + phase, sourceWidth.toFloat(), sourceHeight.toFloat(), white)
            },
            Fixture("quadrants") { phase ->
                drawColor(android.graphics.Color.WHITE)
                drawRect(0f, 0f, 150f + phase, 84f, Paint().apply { color = android.graphics.Color.RED })
                drawRect(150f + phase, 0f, sourceWidth.toFloat(), 84f, Paint().apply { color = android.graphics.Color.GREEN })
                drawRect(0f, 84f, 150f + phase, sourceHeight.toFloat(), Paint().apply { color = android.graphics.Color.BLUE })
            },
            Fixture("translated impulse") { phase ->
                drawColor(android.graphics.Color.BLACK); drawRect(200f + phase, 60f + phase, 232f + phase, 92f + phase, white)
            },
            Fixture("horizontal gradient") { phase ->
                drawRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(), Paint().apply {
                    shader = LinearGradient(phase, 0f, sourceWidth + phase, 0f, android.graphics.Color.BLACK, android.graphics.Color.WHITE, Shader.TileMode.CLAMP)
                })
            },
            stripes(8), stripes(16), stripes(24),
            Fixture("1 px horizontal lines, period 2") { phase ->
                drawColor(android.graphics.Color.WHITE)
                val paint = Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = true }
                for (y in 0 until sourceHeight step 2) drawRect(0f, y + phase, sourceWidth.toFloat(), y + phase + 1f, paint)
            },
            Fixture("2 px checkerboard") { phase ->
                drawColor(android.graphics.Color.BLACK)
                val paint = Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true }
                for (y in 0 until sourceHeight step 2) for (x in -2 until sourceWidth step 2)
                    if ((x / 2 + y / 2) % 2 == 0) drawRect(x + phase, y.toFloat(), x + phase + 2f, y + 2f, paint)
            },
            Fixture("text bands") { phase ->
                drawColor(android.graphics.Color.WHITE)
                val paint = Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = true }
                for (y in listOf(20f, 100f, 152f, 230f)) drawRect(0f, y + phase, sourceWidth.toFloat(), y + phase + 16f, paint)
            },
            Fixture("list rows 8 of 16") { phase ->
                drawColor(android.graphics.Color.WHITE)
                val paint = Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = true }
                for (y in -16 until sourceHeight step 16) drawRect(0f, y + phase, sourceWidth.toFloat(), y + phase + 8f, paint)
            },
            Fixture("glyph runs") { phase ->
                drawColor(android.graphics.Color.WHITE)
                val paint = Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = true; textSize = 14f }
                var y = 16f + phase
                var line = 0
                while (y < sourceHeight) {
                    paint.textSize = 11f + (line % 4) * 3f
                    drawText("A35 Runner ${line * 7 % 100} - the quick brown fox jumps over the lazy dog 0123456789", 4f + phase, y, paint)
                    y += paint.textSize + 6f
                    line++
                }
            },
            Fixture("artwork split and checker") { phase ->
                drawColor(android.graphics.Color.WHITE)
                drawRect(60f + phase, 60f, 190f + phase, 260f, Paint().apply { color = android.graphics.Color.RED })
                drawRect(190f + phase, 60f, 320f + phase, 260f, Paint().apply { color = android.graphics.Color.BLUE })
                val paint = Paint().apply { color = android.graphics.Color.rgb(32, 160, 96) }
                for (y in 60 until 260 step 2) for (x in 330 until 600 step 2)
                    if ((x / 2 + y / 2) % 2 == 0) drawRect(x + phase, y.toFloat(), x + phase + 2f, y + 2f, paint)
            },
            Fixture("alpha edges") { phase ->
                drawColor(android.graphics.Color.argb(64, 255, 0, 0))
                drawRect(phase + 150f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(),
                    Paint().apply { color = android.graphics.Color.argb(191, 0, 255, 0); isAntiAlias = true })
            },
            Fixture("low alpha") { phase ->
                drawColor(android.graphics.Color.argb(4, 255, 0, 0))
                drawRect(phase + 150f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(),
                    Paint().apply { color = android.graphics.Color.argb(12, 0, 0, 255); isAntiAlias = true })
            })
    }

    private fun rasterize(fixture: Fixture, phase: Float): Bitmap =
        Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).also { fixture.paint(android.graphics.Canvas(it), phase) }

    /** Premultiplied coverage-weighted mean of [bitmap] over [region] (source pixels, fractional edges). */
    private fun expectedMean(bitmap: Bitmap, region: Rect): FloatArray {
        var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
        val x0 = kotlin.math.floor(region.left).toInt().coerceAtLeast(0)
        val x1 = kotlin.math.ceil(region.right).toInt().coerceAtMost(bitmap.width)
        val y0 = kotlin.math.floor(region.top).toInt().coerceAtLeast(0)
        val y1 = kotlin.math.ceil(region.bottom).toInt().coerceAtMost(bitmap.height)
        val row = IntArray(x1 - x0)
        for (y in y0 until y1) {
            val coverY = (minOf(region.bottom, y + 1f) - maxOf(region.top, y.toFloat())).coerceAtLeast(0f)
            if (coverY <= 0f) continue
            bitmap.getPixels(row, 0, row.size, x0, y, row.size, 1)
            for (x in x0 until x1) {
                val coverX = (minOf(region.right, x + 1f) - maxOf(region.left, x.toFloat())).coerceAtLeast(0f)
                val weight = (coverX * coverY).toDouble()
                if (weight <= 0.0) continue
                val c = row[x - x0]
                val alpha = (c ushr 24 and 255) / 255.0
                r += (c ushr 16 and 255) / 255.0 * alpha * weight
                g += (c ushr 8 and 255) / 255.0 * alpha * weight
                b += (c and 255) / 255.0 * alpha * weight
                a += alpha * weight
            }
        }
        val area = (region.width * region.height).toDouble()
        return floatArrayOf((r / area).toFloat(), (g / area).toFloat(), (b / area).toFloat(), (a / area).toFloat())
    }

    /** The fill drawn over [behind] (the displayed scene pixel under the sampled point): alpha follows coverage. */
    private fun expectedPixel(mean: FloatArray, behind: Color): Color {
        val alpha = mean[3].coerceIn(0f, 1f)
        if (alpha <= 0f) return behind
        val c = floatArrayOf(mean[0] / alpha, mean[1] / alpha, mean[2] / alpha)
        return Color(c[0] * alpha + behind.red * (1f - alpha), c[1] * alpha + behind.green * (1f - alpha), c[2] * alpha + behind.blue * (1f - alpha))
    }

    private fun assertFill(label: String, expected: Color, actual: Color, region: Rect) {
        for ((channel, e, a) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green),
                Triple("blue", expected.blue, actual.blue))) {
            if (Build.VERSION.SDK_INT >= 33) assertEquals("$label $channel over $region", e, a, tolerance)
            else assertLegacyAreaBound(a, e, region, materialReductionExtent(IntSize(sourceWidth, sourceHeight)).width,
                materialReductionExtent(IntSize(sourceWidth, sourceHeight)).height)
        }
    }

    private fun <T> eventually(block: () -> T): T {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            try { return block() } catch (failure: AssertionError) {
                if (android.os.SystemClock.uptimeMillis() > deadline) throw failure
                Thread.sleep(100)
            }
        }
    }

    /** Drift statistics between the legacy and block-mean chains, in 8-bit codes. */
    private class Drift {
        var samples = 0; var sum = 0.0; var max = 0f; var worst = ""
        fun add(label: String, a: Color, b: Color) {
            for ((x, y) in listOf(a.red to b.red, a.green to b.green, a.blue to b.blue)) {
                val d = kotlin.math.abs(x - y) * 255f
                samples++; sum += d
                if (d > max) { max = d; worst = label }
            }
        }
        val mean get() = if (samples == 0) 0.0 else sum / samples
    }

    private fun receipt(name: String, drift: Drift, extra: Map<String, Any>) {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val output = File(root, "none-exact-reduction-api" + Build.VERSION.SDK_INT).also { it.mkdirs() }
        File(output, "$name.json").writeText(JSONObject(mapOf("samples" to drift.samples, "maxCodes" to drift.max,
            "meanCodes" to drift.mean, "worst" to drift.worst, "budgetMaxCodes" to driftMaxCodes, "budgetMeanCodes" to driftMeanCodes) + extra).toString(2))
    }

    @Composable private fun Scene(bitmap: Bitmap, appearance: GlassAppearance, source: FrostedBackdrop, surface: @Composable () -> Unit) =
        CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassAppearance provides appearance) { LocalicaTheme {
            // Black behind the source: what shows through translucent fixtures is known exactly.
            Box(Modifier.size(sourceWidth.dp, sourceHeight.dp).background(Color.Black)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawImage(bitmap.asImageBitmap(), filterQuality = FilterQuality.None)
                }
                surface()
            }
        } }

    private fun centre(tag: String): Color = compose.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }

    /** One surface of [footprint] px at [origin], remounted per chain; every fixture and phase passes behind it. */
    private fun footprint(footprint: IntSize) {
        lateinit var source: FrostedBackdrop
        var bitmap by mutableStateOf(rasterize(fixtures().first(), 0f))
        var appearance by mutableStateOf(plain)
        var legacy by mutableStateOf(false)
        compose.setContent {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Scene(bitmap, appearance, source) {
                key(legacy) {
                    LiquidGlassSurface(Modifier.offset(origin.x.dp, origin.y.dp).size(footprint.width.dp, footprint.height.dp).testTag("glass"),
                        source, cornerRadius = 0.dp, pressFeedback = false, shadow = false)
                }
            }
        }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val region = Rect(origin, Size(footprint.width.toFloat(), footprint.height.toFloat()))
        val centre = IntSize(footprint.width / 2, footprint.height / 2)
        val drift = Drift()
        val modern = Build.VERSION.SDK_INT >= 33
        for (fixture in fixtures()) for ((look, phases) in listOf(plain to listOf(0f, .375f), styled to listOf(0f))) for (phase in phases) {
            val raster = rasterize(fixture, phase)
            val label = "${fixture.name} phase $phase ${if (look === plain) "plain" else "styled"}, footprint $footprint"
            compose.runOnIdle { MaterialAreaReduction.legacyHalvings = false; legacy = false; appearance = look; bitmap = raster }
            val behind = Color(raster.getPixel(origin.x.toInt() + centre.width, origin.y.toInt() + centre.height)).let {
                Color(it.red * it.alpha, it.green * it.alpha, it.blue * it.alpha)
            }
            val expected = expectedPixel(expectedMean(raster, region), behind)
            val fresh = eventually {
                val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
                // Uniform fill: three points agree with the integrated mean (the plain look is the ideal-mean budget).
                for ((x, y) in listOf(centre.width to centre.height, footprint.width / 5 to footprint.height / 3, footprint.width * 4 / 5 to footprint.height * 2 / 3)) {
                    val point = pixels[x, y]
                    if (fixture.name.contains("alpha") && (x != centre.width || y != centre.height)) continue
                    if (look === plain) assertFill("$label at $x,$y", expected, point, region)
                    else assertEquals("$label uniform at $x,$y", pixels[centre.width, centre.height].red, point.red, 2f / 255f)
                }
                pixels[centre.width, centre.height]
            }
            if (!modern) continue
            // The same scene through the pre-#691 bilinear chain: the drift budget.
            compose.runOnIdle { MaterialAreaReduction.legacyHalvings = true; legacy = true }
            val old = eventually {
                val point = centre("glass")
                if (look === plain) assertFill("$label legacy", expected, point, region)
                point
            }
            drift.add(label, fresh, old)
        }
        compose.runOnIdle { MaterialAreaReduction.legacyHalvings = false }
        if (!modern) return
        receipt("footprint-${footprint.width}x${footprint.height}", drift, mapOf("footprint" to "$footprint", "fixtures" to fixtures().size))
        assertTrue("Legacy vs block-mean drift max ${drift.max} codes at ${drift.worst} exceeds $driftMaxCodes", drift.max <= driftMaxCodes)
        assertTrue("Legacy vs block-mean drift mean ${drift.mean} codes exceeds $driftMeanCodes", drift.mean <= driftMeanCodes)
    }

    @Test fun footprintJustAboveAPowerOfTwo() = footprint(IntSize(257, 129))

    @Test fun footprintInTheMiddleOfAPowerOfTwoInterval() = footprint(IntSize(383, 191))

    @Test fun footprintJustBelowAPowerOfTwo() = footprint(IntSize(511, 255))

    /** A block mapping that repeats or crops blocks reduces a half split to one side; the mean is one half. */
    @Test fun halfSplitReducesToItsMeanNotToOneSide() {
        lateinit var source: FrostedBackdrop
        var vertical by mutableStateOf(true)
        val raster = { v: Boolean -> Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).also {
            val canvas = android.graphics.Canvas(it)
            canvas.drawColor(android.graphics.Color.BLACK)
            val white = Paint().apply { color = android.graphics.Color.WHITE }
            if (v) canvas.drawRect(256f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(), white)
            else canvas.drawRect(0f, 64f, sourceWidth.toFloat(), sourceHeight.toFloat(), white)
        } }
        compose.setContent {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Scene(raster(vertical), plain, source) {
                // Footprint exactly 512x128 at the origin: a 512-column level 0 with no stretch.
                LiquidGlassSurface(Modifier.size(512.dp, 128.dp).testTag("glass"), source, cornerRadius = 0.dp, pressFeedback = false, shadow = false)
            }
        }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val region = Rect(0f, 0f, 512f, 128f)
        for (v in listOf(true, false)) {
            compose.runOnIdle { vertical = v }
            eventually {
                val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
                for (x in listOf(8, 256, 500)) {
                    val point = pixels[x, 64]
                    assertFill("${if (v) "left/right" else "top/bottom"} half split at $x", Color(.5f, .5f, .5f), point, region)
                }
            }
        }
    }

    /** A scaled consumer maps to a fractional source region (321.25×161.25); the fill is read from the screen. */
    @Test fun scaledSurfaceAveragesItsFractionalSourceRegion() {
        lateinit var source: FrostedBackdrop
        val fixture = fixtures()[4]
        var bitmap by mutableStateOf(rasterize(fixture, 0f))
        compose.setContent {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Scene(bitmap, plain, source) {
                Box(Modifier.offset(origin.x.dp, origin.y.dp).graphicsLayer { scaleX = 1.25f; scaleY = 1.25f; transformOrigin = TransformOrigin(0f, 0f) }) {
                    LiquidGlassSurface(Modifier.size(257.dp, 129.dp).testTag("glass"), source,
                        cornerRadius = 0.dp, pressFeedback = false, shadow = false)
                }
            }
        }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val region = Rect(origin, Size(257f * 1.25f, 129f * 1.25f))
        for (phase in listOf(0f, .375f)) {
            val raster = rasterize(fixture, phase)
            compose.runOnIdle { bitmap = raster }
            val expected = expectedPixel(expectedMean(raster, region), Color.Black)
            eventually {
                val node = compose.onNodeWithTag("glass").fetchSemanticsNode()
                val location = IntArray(2).also { (node.root as androidx.compose.ui.platform.ViewRootForTest).view.getLocationOnScreen(it) }
                val centre = node.boundsInRoot.center
                val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val actual = try { Color(screen.getPixel((centre.x + location[0]).toInt(), (centre.y + location[1]).toInt())) } finally { screen.recycle() }
                assertFill("${fixture.name} phase $phase scaled 1.25", expected, actual, region)
            }
        }
    }
}

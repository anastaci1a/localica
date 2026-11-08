package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.runtime.SideEffect
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic padded pack: its viewport is deliberately wider than its visible ink. */
class SkipOverflowTaperTest {
    @get:Rule val compose = localicaComposeRule()
    private val pack = object : Painter() {
        override val intrinsicSize = Size(100f, 100f)
        override fun DrawScope.onDraw() {
            drawRect(Color.Black, androidx.compose.ui.geometry.Offset(.2f * size.width, .25f * size.height),
                Size(.6f * size.width, .5f * size.height))
        }
    }

    private fun render(packGlyph: Boolean, phase: Float, glyph: TransportGlyph = TransportGlyph.Next, painter: Painter = pack): ImageBitmap {
        val image = ImageBitmap(384, 192)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(128f, 128f)) {
            translate(128f, 32f) {
                if (packGlyph) drawTransportPainters(glyph, 0f, phase, Color.Black, painter, painter)
                else drawTransportGlyph(glyph, 0f, phase, Color.Black, TransportShapes())
            }
        }
        return image
    }

    @Test fun stockAndPackTravelBeyondInkWithoutTouchingAnyBoundary() {
        for (isPack in listOf(false, true)) for (glyph in listOf(TransportGlyph.Next, TransportGlyph.Previous)) {
            val rest = render(isPack, 0f, glyph).toPixelMap()
            val left = (0 until rest.width).first { x -> (0 until rest.height).any { y -> rest[x,y].alpha > 0f } }
            val right = (0 until rest.width).last { x -> (0 until rest.height).any { y -> rest[x,y].alpha > 0f } }
            val shapes = TransportShapes()
            val ink = if (isPack) SkipInk.measure(pack) { with(pack) { draw(size) } }
                else SkipInk.measure(shapes) { drawTransportGlyph(TransportGlyph.Next, 0f, 0f, Color.Black, shapes) }
            val inkLeft = if (!isPack && glyph == TransportGlyph.Previous) 1f - ink.right else ink.left
            val inkRight = if (!isPack && glyph == TransportGlyph.Previous) 1f - ink.left else ink.right
            val pitch = if (isPack) ink.width / 2f else SkipTrianglePitch
            val zeroLeft = 128f + (inkLeft - pitch) * 128f
            val zeroRight = 128f + (inkRight + pitch) * 128f
            var outside = 0f
            for (step in 1..19) {
                val frame = render(isPack, step / 20f, glyph).toPixelMap()
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    val alpha = frame[x,y].alpha
                    if (x < left || x > right) outside = maxOf(outside, alpha)
                    if (x + 1f <= zeroLeft || x >= zeroRight)
                        assertEquals("No residual alpha beyond taper pack=$isPack $glyph at $step ($x,$y)", 0f, alpha, 0f)
                    if (x == 0 || x == frame.width - 1 || y == 0 || y == frame.height - 1)
                        assertEquals("Transparent boundary pack=$isPack $glyph at $step ($x,$y)", 0f, alpha, 0f)
                }
            }
            assertTrue("Travels visibly outside static ink pack=$isPack $glyph: $outside", outside > .05f)
        }
    }

    @Test fun noOutsideInkPopsAcrossCycleStartAndEndAndRestIsStatic() {
        for (isPack in listOf(false, true)) for (glyph in listOf(TransportGlyph.Next, TransportGlyph.Previous)) {
            val rest = render(isPack, 0f, glyph)
            assertTrue("Exact rest endpoints", rest.asAndroidBitmap().sameAs(render(isPack, 1f, glyph).asAndroidBitmap()))
            val expected = rest.toPixelMap()
            val edge = BooleanArray(expected.width * expected.height)
            for (y in 1 until expected.height - 1) for (x in 1 until expected.width - 1) {
                val alpha = expected[x,y].alpha
                edge[y * expected.width + x] = alpha > 0f && (alpha < 1f ||
                    (-1..1).any { dy -> (-1..1).any { dx -> expected[x + dx,y + dy].alpha == 0f } })
            }
            val contourLength = edge.count { it }
            for (phase in listOf(.00001f, .99999f, 1.00001f, -.00001f)) {
                val actual = render(isPack, phase, glyph).toPixelMap()
                var changed = 0
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    if (expected[x,y].alpha == 0f)
                        assertEquals("Exactly zero outside rest ink near endpoint", 0f, actual[x,y].alpha, 0f)
                    if (expected[x,y].alpha != actual[x,y].alpha) {
                        changed++
                        val nearContour = (-1..1).any { dy -> (-1..1).any { dx ->
                            val px = x + dx
                            val py = y + dy
                            px in 0 until expected.width && py in 0 until expected.height && edge[py * expected.width + px]
                        } }
                        assertTrue("Only contour AA may change pack=$isPack $glyph at $phase ($x,$y)", nearContour)
                    }
                }
                assertTrue("Changed pixels bounded by static contour length: $changed <= $contourLength", changed <= contourLength)
            }
        }
    }

    @Test fun stockAndPackMaskProfilesAreSymmetricWithinOneAlphaLevel() {
        val shapes = TransportShapes()
        val stockInk = SkipInk.measure(shapes) { drawTransportGlyph(TransportGlyph.Next, 0f, 0f, Color.Black, shapes) }
        val packInk = SkipInk.measure(pack) { with(pack) { draw(size) } }
        assertEquals(.2f, packInk.left, 1f / 512f)
        assertEquals(.8f, packInk.right, 1f / 512f)
        for ((ink, pitch) in listOf(stockInk to SkipTrianglePitch, packInk to packInk.width / 2f)) {
            val image = ImageBitmap(384, 192)
            val origin = 192f - ink.center.x * 128f
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(128f, 128f)) {
                translate(origin, 32f) {
                    drawWithSkipTaper(.5f, ink, pitch) {
                        drawRect(Color.Black, androidx.compose.ui.geometry.Offset(-128f, 0f), Size(384f, 128f))
                    }
                }
            }
            val pixels = image.toPixelMap()
            val y = 96
            var levels = 0
            for (x in 0 until pixels.width / 2) {
                assertEquals("Left versus mirrored right", pixels[x,y].alpha, pixels[pixels.width - 1 - x,y].alpha, 1f / 255f)
                val position = (x + .5f - origin) / 128f
                assertEquals("Rendered mask follows ink-to-outer-bound ramp", skipTaperAlpha(position, ink.left, ink.right, pitch), pixels[x,y].alpha, 1f / 255f)
                if (x > 0 && pixels[x,y].alpha > pixels[x - 1,y].alpha) levels++
            }
            assertTrue("Many intermediate alpha levels", levels >= 30)
            val insideLeft = kotlin.math.ceil(origin + ink.left * 128f).toInt()
            val outsideLeft = kotlin.math.floor(origin + (ink.left - pitch) * 128f).toInt() - 1
            assertEquals("Opaque at static ink edge", 1f, pixels[insideLeft,y].alpha, 0f)
            assertEquals("Zero at computed outer bound", 0f, pixels[outsideLeft,y].alpha, 0f)
        }
    }

    @Test fun packMovesByOneTriangleAndKeepsTheMiddleTriangleOpaque() {
        val ink = SkipInk.measure(pack) { with(pack) { draw(size) } }
        fun center(phase: Float): Double {
            val pixels = render(true, phase).toPixelMap()
            val opaque = (0 until pixels.width).filter { pixels[it,96].alpha >= .99f }
            assertTrue("Opaque middle triangle at $phase", opaque.isNotEmpty())
            return opaque.average()
        }
        assertEquals("Half a cycle moves half of one triangle", ink.width / 4f * 128f.toDouble(), center(.75f) - center(.25f), 1.0)
        for (phase in listOf(.25f, .5f, .75f)) {
            val pieces = packSkipElements(phase, ink.width)
            assertEquals("One triangle is half the ink width", ink.width / 2f, pieces[1].offset - pieces[0].offset, 0f)
            assertEquals(skipElements(phase)[0].alpha, pieces[0].alpha, 0f)
            assertEquals(skipElements(phase)[2].alpha, pieces[3].alpha, 0f)
        }
    }

    /** The optional file is device-local and is never part of a workflow or repository asset. */
    @Test fun exportStockAndPackFrameStrips() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val privateFile = args.getString("skipStripPack")?.let(::File)
        val fixture = """<svg viewBox="0 0 100 100"><path d="M20 25L48 50L20 75ZM50 25L78 50L50 75Z"/></svg>"""
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("pack.json", """{"name":"Synthetic skip fixture","version":"1"}""")
            for (id in listOf("transport.next", "transport.previous")) entry("icons/$id.svg", fixture)
        } }.toByteArray()
        try {
            IconPacks.import(context, privateFile?.inputStream() ?: bytes.inputStream())
            lateinit var next: Painter
            lateinit var previous: Painter
            compose.setContent {
                val n = appIconPainter(AppIcon.TransportNext)
                val p = appIconPainter(AppIcon.TransportPrevious)
                SideEffect { next = n; previous = p }
            }
            compose.waitForIdle()
            val root = File(args.getString("additionalTestOutputDir")?.let(::File)
                ?: requireNotNull(context.getExternalFilesDir(null)), "skip-654").apply { mkdirs() }
            for (glyph in listOf(TransportGlyph.Next, TransportGlyph.Previous)) for (isPack in listOf(false, true)) {
                val painter = if (glyph == TransportGlyph.Next) next else previous
                val strip = android.graphics.Bitmap.createBitmap(384 * 6, 192 * 3, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(strip)
                canvas.drawColor(android.graphics.Color.WHITE)
                val label = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 14f }
                // Equal wall-clock steps retain the real easing and duration, including endpoint samples.
                for (step in 0..17) {
                    val time = step / 17f
                    val phase = SkipEasing.transform(time)
                    val frame = render(isPack, phase, glyph, painter).asAndroidBitmap()
                    val x = step % 6 * 384f
                    val y = step / 6 * 192f
                    canvas.drawBitmap(frame, x, y, null)
                    canvas.drawText("${(time * skipDuration(4f / 3f)).toInt()} ms  phase=${"%.3f".format(phase)}", x + 10f, y + 18f, label)
                    frame.recycle()
                }
                File(root, "${if (isPack) "pack" else "stock"}-${glyph.name.lowercase()}-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                    strip.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                strip.recycle()
            }
        } finally { IconPacks.reset(context) }
    }

}

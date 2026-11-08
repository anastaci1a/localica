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
            var outside = 0f
            for (step in 1..19) {
                val frame = render(isPack, step / 20f, glyph).toPixelMap()
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    val alpha = frame[x,y].alpha
                    if (x < left || x > right) outside = maxOf(outside, alpha)
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
            assertTrue(rest.asAndroidBitmap().sameAs(render(isPack, 1f, glyph).asAndroidBitmap()))
            val expected = rest.toPixelMap()
            for (phase in listOf(.00001f, .99999f, 1.00001f, -.00001f)) {
                val actual = render(isPack, phase, glyph).toPixelMap()
                for (y in 0 until actual.height) for (x in 0 until actual.width)
                    assertEquals("Continuous pack=$isPack $glyph at $phase ($x,$y)", expected[x,y].alpha, actual[x,y].alpha, .01f)
            }
        }
    }
    @Test fun syntheticPackInkAndRenderedAlphaBandsMatchTheStockGeometry() {
        val ink = SkipInk.measure(pack) { with(pack) { draw(size) } }
        assertEquals(.2f, ink.left, 1f / 512f)
        assertEquals(.8f, ink.right, 1f / 512f)
        val image = render(true, .5f).toPixelMap()
        val y = 96
        var levels = 0
        var previous = 0f
        // Incoming rectangle has constant .5 opacity; its edge profile is the mask itself.
        for (x in 115..134) {
            val expected = .5f * skipTaperAlpha((x + .5f - 128f) / 128f, ink.left, ink.right)
            val actual = image[x,y].alpha
            assertEquals("Rendered pack taper follows shared ink-relative mask at $x", expected, actual, .025f)
            assertTrue("No alpha jump in rendered band", actual - previous < .06f)
            if (actual > previous) levels++
            previous = actual
            assertEquals("Symmetric rendered bands", actual, image[383 - x,y].alpha, .025f)
        }
        assertTrue("Multiple alpha levels", levels >= 10)
    }

    @Test fun packCopiesUseTheSameIncomingAndDepartingOpacityTimingAsStock() {
        val ink = SkipInk.measure(pack) { with(pack) { draw(size) } }
        for (step in 1..99) {
            val phase = step / 100f
            val image = render(true, phase).toPixelMap()
            for ((copy, alpha) in listOf(-1f to skipElements(phase)[0].alpha, 0f to skipElements(phase)[2].alpha)) {
                val x = (128f + (.5f + (copy + phase) * ink.width) * 128f).toInt()
                val expected = alpha * skipTaperAlpha((x + .5f - 128f) / 128f, ink.left, ink.right)
                assertEquals("Shared copy timing at $phase", expected, image[x,96].alpha, .025f)
            }
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

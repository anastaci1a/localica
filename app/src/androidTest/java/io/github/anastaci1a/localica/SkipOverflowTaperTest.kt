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

/** Synthetic padded pack: its viewport is deliberately wider than its visible ink. */
class SkipOverflowTaperTest {
    private val pack = object : Painter() {
        override val intrinsicSize = Size(100f, 100f)
        override fun DrawScope.onDraw() {
            drawRect(Color.Black, androidx.compose.ui.geometry.Offset(.2f * size.width, .25f * size.height),
                Size(.6f * size.width, .5f * size.height))
        }
    }

    private fun render(packGlyph: Boolean, phase: Float, glyph: TransportGlyph = TransportGlyph.Next): ImageBitmap {
        val image = ImageBitmap(384, 192)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(128f, 128f)) {
            translate(128f, 32f) {
                if (packGlyph) drawTransportPainters(glyph, 0f, phase, Color.Black, pack, pack)
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
}

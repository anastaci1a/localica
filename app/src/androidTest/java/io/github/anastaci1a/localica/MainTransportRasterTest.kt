package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.reference.TransportShapes as MainTransportShapes
import io.github.anastaci1a.localica.reference.drawTransportGlyph as drawMainTransportGlyph
import io.github.anastaci1a.localica.reference.drawTransportPainters as drawMainTransportPainters
import io.github.anastaci1a.localica.ui.TransportGlyph
import io.github.anastaci1a.localica.ui.TransportShapes
import io.github.anastaci1a.localica.ui.drawTransportGlyph
import io.github.anastaci1a.localica.ui.drawTransportPainters
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compare the architecture adoption against the exact main3ca9b635 stock and pack drawing code. */
class MainTransportRasterTest {
    private fun render(width: Int, height: Int, draw: DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
            Size(width.toFloat(), height.toFloat()), block = draw)
        return bitmap
    }

    private fun compare(stock: Boolean) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val shapes = TransportShapes()
        val mainShapes = MainTransportShapes()
        val packPlay = object : Painter() {
            override val intrinsicSize = Size.Unspecified
            override fun DrawScope.onDraw() {
                drawRect(Color.Black, Offset(size.width * .13f, size.height * .19f), size * .57f)
            }
        }
        val packPause = object : Painter() {
            override val intrinsicSize = Size.Unspecified
            override fun DrawScope.onDraw() {
                drawRect(Color.Black, Offset(size.width * .1f, size.height * .1f), Size(size.width * .2f, size.height * .8f))
                drawRect(Color.Black, Offset(size.width * .6f, size.height * .2f), Size(size.width * .28f, size.height * .6f))
            }
        }
        val color = Color(.12f, .37f, .61f, .73f)
        for ((width, height) in listOf(24 to 24, 47 to 33, 64 to 72)) {
            for (glyph in TransportGlyph.entries) {
                for (phase in listOf(0f, .125f, .37f, .5f, .875f, 1f, 1.25f)) {
                    val pause = phase.coerceAtMost(1f)
                    val expected = render(width, height) {
                        if (stock) drawMainTransportGlyph(glyph, pause, phase, color, mainShapes)
                        else drawMainTransportPainters(glyph, pause, phase, color, packPlay, packPause)
                    }
                    val actual = render(width, height) {
                        if (stock) drawTransportGlyph(glyph, pause, phase, color, shapes)
                        else drawTransportPainters(glyph, pause, phase, color, packPlay, packPause)
                    }
                    try {
                        assertTrue("main raster changed stock=$stock glyph=$glyph phase=$phase size=${width}x$height",
                            expected.sameAs(actual))
                    } finally { expected.recycle(); actual.recycle() }
                }
            }
        }
    }

    @Test fun stockPlayPauseAndSkipRemainPixelIdenticalToMain() = compare(stock = true)
    @Test fun packPlayPauseAndSkipRemainPixelIdenticalToMain() = compare(stock = false)
}

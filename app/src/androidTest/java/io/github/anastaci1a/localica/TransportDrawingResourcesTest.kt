package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.MorphPiece
import io.github.anastaci1a.localica.ui.PackPlayPauseMorph
import io.github.anastaci1a.localica.ui.drawPackPlayPause
import io.github.anastaci1a.localica.ui.components.TransportContour
import io.github.anastaci1a.localica.ui.components.withTransportLayer
import org.junit.Assert.*
import org.junit.Test

/** The architecture adoption must retain the old raster and release every borrowed canvas layer. */
class TransportDrawingResourcesTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun render(width: Int, height: Int, block: DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
            Size(width.toFloat(), height.toFloat()), block = block)
        return bitmap
    }

    @Test fun bothMorphPiecesMatchTheFreshPathRasterAcrossFractionsAndDrawSizes() = onMain {
        val left = MorphPiece(listOf(Offset(.1f, .1f), Offset(.5f, .3f), Offset(.5f, .7f), Offset(.1f, .9f)),
            listOf(Offset(.2f, .1f), Offset(.4f, .1f), Offset(.4f, .9f), Offset(.2f, .9f)))
        val right = MorphPiece(listOf(Offset(.5f, .3f), Offset(.9f, .5f), Offset(.9f, .5f), Offset(.5f, .7f)),
            listOf(Offset(.6f, .1f), Offset(.8f, .1f), Offset(.8f, .9f), Offset(.6f, .9f)))
        val morph = PackPlayPauseMorph(left, right, .5f, "fixture")
        val endpoint = ColorPainter(Color.Red)
        for ((width, height) in listOf(24 to 31, 64 to 48, 37 to 29, 16 to 18)) {
            for (fraction in listOf(.01f, .2f, .5f, .99f, .3f)) {
                val expected = render(width, height) {
                    // The pre-adoption fresh-path raster, using the existing independent point-list API.
                    val path = Path()
                    for (piece in listOf(left, right)) {
                        piece.at(fraction).forEachIndexed { index, point ->
                            if (index == 0) path.moveTo(point.x * size.width, point.y * size.height)
                            else path.lineTo(point.x * size.width, point.y * size.height)
                        }
                        path.close()
                    }
                    drawPath(path, Color.Black)
                }
                val actual = render(width, height) {
                    drawPackPlayPause(fraction, Color.Black, morph, endpoint, endpoint)
                }
                try {
                    assertTrue("Both pieces differ at fraction=$fraction size=${width}x$height", expected.sameAs(actual))
                } finally { expected.recycle(); actual.recycle() }
            }
        }
    }

    @Test fun nextFrameRewindsThePersistentContourInsteadOfRetainingOldInk() = onMain {
        val contour = TransportContour()
        val first = render(32, 24) { contour.draw(this, Color.Red) { addRect(Rect(0f, 0f, 10f, 10f)) } }
        val second = render(32, 24) { contour.draw(this, Color.Blue) { addRect(Rect(20f, 12f, 30f, 22f)) } }
        try {
            assertEquals(android.graphics.Color.RED, first.getPixel(4, 4))
            assertEquals(android.graphics.Color.TRANSPARENT, second.getPixel(4, 4))
            assertEquals(android.graphics.Color.BLUE, second.getPixel(24, 16))
        } finally { first.recycle(); second.recycle() }
    }

    @Test fun nestedSrcOverAndPlusLayersRestoreTheCanvasWhenTheDrawBlockThrows() = onMain {
        val expected = IllegalStateException("draw fixture")
        val image = render(32, 24) {
            val canvas = drawContext.canvas.nativeCanvas
            val before = canvas.saveCount
            try {
                withTransportLayer(Rect(-2f, -2f, 34f, 26f), BlendMode.SrcOver) {
                    withTransportLayer(Rect(-1f, -1f, 33f, 25f), BlendMode.Plus) {
                        drawRect(Color.Red)
                        throw expected
                    }
                }
                fail("The drawing failure must propagate")
            } catch (actual: IllegalStateException) { assertSame(expected, actual) }
            assertEquals("Both isolation layers must release on exception", before, canvas.saveCount)
        }
        image.recycle()
    }
}

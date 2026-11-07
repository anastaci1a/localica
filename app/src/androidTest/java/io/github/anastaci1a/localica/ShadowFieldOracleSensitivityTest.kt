package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.GlassShadowGeometry
import io.github.anastaci1a.localica.ui.glassShadows
import org.junit.Assert.*
import org.junit.Test

/** Offscreen capture at density 1 with the given composition identities. */
internal fun shadowCapture(width: Int = 200, height: Int = 140, generation: Long = 1, draw: DrawScope.() -> Unit): PresentedCapture {
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), draw)
    return PresentedCapture(image, listOf("offscreen"), 1, generation, generation, null, System.nanoTime())
}
internal val probeBody = RoundRect(40f, 40f, 160f, 88f, CornerRadius(24f))
/** The whole ring field of [probeBody] at spread .5 (reach 18 px, shift 2 px) plus margin. */
internal val probeRegion = Rect(16f, 16f, 184f, 116f)

/** U12.01c red witness: every mutation below passes a mean-only probe or a max-only probe, and the full-field oracle rejects it. */
class ShadowFieldOracleSensitivityTest {
    private val probe = ShadowFieldProbe(listOf(probeRegion), 1f / 255)
    private fun field(white: Boolean = false) = shadowCapture { if (white) drawRect(Color.White); glassShadows(listOf(probeBody to 1f), 1f, .5f, GlassShadowGeometry()) }
    private fun edit(capture: PresentedCapture, change: (Bitmap) -> Unit): PresentedCapture {
        val bitmap = capture.image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true); change(bitmap)
        return capture.copy(image = bitmap.asImageBitmap())
    }
    private fun meanLuminance(capture: PresentedCapture) = ShadowFrameProbe(probeRegion).sample(capture.image)
    private fun rejects(actual: PresentedCapture, reference: PresentedCapture, background: PresentedCapture? = null) =
        assertThrows(AssertionError::class.java) { probe.assertWithin(probe.compare(actual, reference, background), .008f, "mutation") }

    @Test fun identicalFieldPasses() {
        val reference = field()
        val result = probe.compare(field(), reference)
        assertEquals(0f, result.maximumRgbaError); assertEquals(0, result.missingCoveragePixels)
        probe.assertWithin(result, .008f, "identical")
    }

    @Test fun blankFieldFailsThoughItsMeanLuminanceMatches() {
        val reference = field(); val blank = shadowCapture { }
        // A black field on a transparent capture has zero luminance with or without the shadow.
        assertEquals(meanLuminance(reference), meanLuminance(blank), 0f)
        val result = probe.compare(blank, reference)
        assertTrue(result.missingCoveragePixels > 0); rejects(blank, reference)
        // An all-zero reference region is a harness failure, never a pass.
        assertThrows(IllegalStateException::class.java) { probe.compare(blank, blank) }
    }

    @Test fun oppositePixelErrorsWithZeroMeanFail() {
        val white = shadowCapture { drawRect(Color.White) }; val reference = field(white = true)
        // Symmetric pixels under the body share one shadow value; move them by ±8/255 so the region mean is unchanged.
        val mutated = edit(reference) { b ->
            val a = b.getPixel(80, 98); val c = b.getPixel(120, 98); assertEquals(a, c)
            val gray = android.graphics.Color.red(a); assertTrue("probe pixels need shadow", gray in 20..235)
            b.setPixel(80, 98, android.graphics.Color.rgb(gray + 8, gray + 8, gray + 8)); b.setPixel(120, 98, android.graphics.Color.rgb(gray - 8, gray - 8, gray - 8))
        }
        assertEquals(meanLuminance(reference), meanLuminance(mutated), 1e-6f)
        rejects(mutated, reference, white)
    }

    @Test fun alphaPointOneNineAgainstPointTwoFails() {
        val reference = shadowCapture { drawRect(Color.Black.copy(alpha = .2f), probeRegion.topLeft, probeRegion.size) }
        val actual = shadowCapture { drawRect(Color.Black.copy(alpha = .19f), probeRegion.topLeft, probeRegion.size) }
        assertTrue(probe.compare(actual, reference).maximumRgbaError > .008f); rejects(actual, reference)
    }

    @Test fun croppedOuterRingFailsAlthoughEveryPixelErrorIsWithinBudget() {
        val reference = field()
        // Clear the faint tail (alpha ≤ 2/255): every pixel error stays below .008, but the field's coverage is cut.
        val cropped = edit(reference) { b -> for (y in 0 until b.height) for (x in 0 until b.width) if (android.graphics.Color.alpha(b.getPixel(x, y)) <= 2) b.setPixel(x, y, 0) }
        val result = probe.compare(cropped, reference)
        assertTrue(result.maximumRgbaError <= .008f); assertTrue(result.missingCoveragePixels > 0); rejects(cropped, reference)
    }

    @Test fun staleOrForeignCompositionFailsBeforePixelMath() {
        val reference = field()
        assertThrows(IllegalStateException::class.java) { probe.compare(reference.copy(drawGeneration = 2), reference) }
        assertThrows(IllegalStateException::class.java) { probe.compare(reference.copy(windowIds = listOf("popup")), reference) }
        assertThrows(IllegalStateException::class.java) { probe.compare(reference.copy(hostEpoch = 2), reference) }
        assertThrows(IllegalStateException::class.java) { probe.compare(shadowCapture(width = 201) { }, reference) }
        assertThrows(IllegalStateException::class.java) { ShadowFieldProbe(listOf(Rect(150f, 100f, 260f, 160f)), 1f / 255).compare(reference, reference) }
    }
}

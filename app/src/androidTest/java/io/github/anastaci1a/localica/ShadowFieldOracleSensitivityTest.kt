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
import io.github.anastaci1a.localica.ui.engine.GlassShadowGeometry
import io.github.anastaci1a.localica.ui.engine.glassShadows
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import io.github.anastaci1a.localica.navigation.GlassRendererMode

/** Offscreen capture at density 1 with the given composition identities. */
internal fun shadowCapture(width: Int = 200, height: Int = 140, generation: Long = 1, draw: DrawScope.() -> Unit): PresentedCapture {
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), draw)
    return PresentedCapture(image, listOf("offscreen"), 1, generation, generation, null, System.nanoTime(), offscreen = true)
}
internal val probeBody = RoundRect(40f, 40f, 160f, 88f, CornerRadius(24f))
/** The whole ring field of [probeBody] at spread .5 (reach 18 px, shift 2 px) plus margin. */
internal val probeRegion = Rect(16f, 16f, 184f, 116f)

/** U12.01c red witness: every mutation below passes a mean-only probe or a max-only probe, and the full-field oracle rejects it. */
class ShadowFieldOracleSensitivityTest {
    @get:Rule val compose = localicaComposeRule()
    private val probe = ShadowFieldProbe(listOf(probeRegion), 1f / 255)
    private fun field(white: Boolean = false) = shadowCapture { if (white) drawRect(Color.White); glassShadows(listOf(probeBody to 1f), 1f, .5f, GlassShadowGeometry()) }
    private fun edit(capture: PresentedCapture, change: (Bitmap) -> Unit): PresentedCapture {
        val bitmap = capture.image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true); change(bitmap)
        return capture.copy(image = bitmap.asImageBitmap())
    }
    private fun meanLuminance(capture: PresentedCapture) = ShadowFrameProbe(probeRegion).sample(capture.image)
    private var mutation = 0
    private fun rejects(actual: PresentedCapture, reference: PresentedCapture, background: PresentedCapture? = null) {
        val result = probe.compare(actual, reference, background)
        val caller = Throwable().stackTrace.first { it.className == javaClass.name && !it.methodName.startsWith("rejects") }.methodName
        shadowOracleEvidence("$caller-${++mutation}", actual, reference, result)
        assertThrows(AssertionError::class.java) { probe.assertWithin(result, .008f, "mutation") }
    }

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
            val y = (89..107).first { android.graphics.Color.red(b.getPixel(80, it)) in 20..235 }
            val a = b.getPixel(80, y); val c = b.getPixel(120, y); assertEquals(a, c); val gray = android.graphics.Color.red(a)
            b.setPixel(80, y, android.graphics.Color.rgb(gray + 8, gray + 8, gray + 8)); b.setPixel(120, y, android.graphics.Color.rgb(gray - 8, gray - 8, gray - 8))
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

    private fun nativeField(token: Int): PresentedCapture = edit(field()) { bitmap ->
        bitmap.setPixel(2, 2, android.graphics.Color.rgb(token shr 16, token shr 8 and 255, token and 255))
        bitmap.setPixel(6, 2, android.graphics.Color.MAGENTA)
    }.copy(windowIds = listOf("native-window-1"), nativeGeneration = NativeShadowGeneration(token,
        androidx.compose.ui.unit.IntOffset(2, 2)), offscreen = false)

    @Test fun missingNativePresentationCorrelationIsIncomplete() {
        val reference = nativeField(10)
        assertThrows(IllegalStateException::class.java) {
            probe.compare(reference.copy(nativeGeneration = null), reference)
        }
        shadowOracleEvidence("missing-native-correlation", reference.copy(nativeGeneration = null), reference)
    }

    @Test fun sameClockWithDifferentNativeGenerationIsRejected() {
        val reference = nativeField(11)
        val stale = nativeField(10)
        assertEquals(reference.clockSequence, stale.clockSequence)
        assertEquals(reference.drawGeneration, stale.drawGeneration)
        assertThrows(IllegalStateException::class.java) { probe.compare(stale, reference) }
        // A fresh metadata label cannot turn an old original image into a new presentation.
        assertThrows(IllegalStateException::class.java) { probe.compare(reference.copy(image = stale.image), reference) }
        shadowOracleEvidence("stale-same-clock", stale, reference)
    }

    @Test fun matchingOriginalNativeMarkerPasses() {
        val reference = nativeField(12)
        probe.assertWithin(probe.compare(reference, reference), .008f, "native marker")
    }

    @Test fun realShadowCallersHaveCompleteNativeFieldsInNormal() = verifyNativeShadowCallers(compose, GlassRendererMode.Normal)
    @Test fun realShadowCallersHaveCompleteNativeFieldsInNone() = verifyNativeShadowCallers(compose, GlassRendererMode.None)
}

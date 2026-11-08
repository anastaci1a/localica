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

    @Test fun everyWindowNeedsItsOwnOriginalGenerationAndCoherentDrawState() {
        val state = ShadowDrawState(1, 1, listOf(probeBody to 1f))
        val first = ShadowWindowReceipt("native-window-1", NativeShadowGeneration(12,
            androidx.compose.ui.unit.IntOffset(2, 2), 1000), state)
        val second = ShadowWindowReceipt("native-window-2", NativeShadowGeneration(19,
            androidx.compose.ui.unit.IntOffset(12, 2), 2000), state)
        val original = edit(nativeField(12)) {
            it.setPixel(12, 2, android.graphics.Color.BLACK or 19)
            it.setPixel(16, 2, android.graphics.Color.MAGENTA)
        }.copy(windowIds = listOf(first.windowId, second.windowId), nativeWindows = listOf(first, second))
        probe.assertWithin(probe.compare(original, original), 2f / 255f, "different native window generations")
        shadowOracleEvidence("multiwindow-valid-distinct-generations", original, original)
        val missing = original.copy(nativeWindows = listOf(first))
        val stale = edit(original) { it.setPixel(12, 2, android.graphics.Color.BLACK or 18) }
        val dimmed = edit(original) { it.setPixel(16, 2, android.graphics.Color.rgb(127, 0, 127)) }
        val foreign = original.copy(nativeWindows = listOf(first, second.copy(state = state.copy(epoch = 2))))
        for ((label, actual) in listOf("missing-root" to missing, "stale-root" to stale,
            "dimmed-root" to dimmed, "foreign-state" to foreign)) {
            assertThrows(IllegalStateException::class.java) { probe.compare(actual, actual) }
            shadowOracleEvidence("multiwindow-$label", actual, original)
        }
    }

    @Test fun popupScrimsNeedExactHigherReceiptsAndRejectOneQuantumOrExtraDimming() {
        fun marker(bitmap: Bitmap, left: Int, blue: Int, sentinel: Int) {
            for (y in 1..8) for (x in 0..7) bitmap.setPixel(left + x, y,
                if (x < 4) android.graphics.Color.BLACK or blue else android.graphics.Color.rgb(sentinel, 0, sentinel))
        }
        val state = ShadowDrawState(1, 1, listOf(probeBody to 1f))
        val lower = ShadowWindowReceipt("native-window-1", NativeShadowGeneration(12,
            androidx.compose.ui.unit.IntOffset(2, 2), 1000), state, PopupShadowReceipt(null, listOf("native-window-2")))
        val higher = ShadowWindowReceipt("native-window-2", NativeShadowGeneration(19,
            androidx.compose.ui.unit.IntOffset(12, 2), 2000), state, PopupShadowReceipt(PopupShadowScrim(1f, 41), emptyList()))
        // Independent fixed pixels: spaced code12 is blue48; one black alpha41 gives blue40, sentinel214.
        val original = edit(nativeField(12)) {
            marker(it, 1, 40, 214); marker(it, 11, 76, 255)
        }.copy(windowIds = listOf(lower.windowId, higher.windowId), nativeWindows = listOf(lower, higher))
        probe.assertWithin(probe.compare(original, original), 2f / 255f, "recorded popup composition")
        shadowOracleEvidence("popup-composition-valid", original, original)
        val wrongQuantum = original.copy(nativeWindows = listOf(lower,
            higher.copy(popup = PopupShadowReceipt(PopupShadowScrim(1f, 42), emptyList()))))
        val missing = original.copy(nativeWindows = listOf(lower))
        val unknown = original.copy(nativeWindows = listOf(lower, higher.copy(popup = null)))
        val extraDim = edit(original) { marker(it, 1, 40, 213) }
        for ((label, actual) in listOf("alpha-off-one-quantum" to wrongQuantum, "missing-receipt" to missing,
            "unknown-higher-composition" to unknown, "extra-unrecorded-dim" to extraDim)) {
            assertThrows(IllegalStateException::class.java) { probe.compare(actual, actual) }
            shadowOracleEvidence("popup-composition-$label", actual, original)
        }
        val noScrim = edit(original) { marker(it, 1, 48, 255) }.copy(nativeWindows = listOf(lower,
            higher.copy(popup = PopupShadowReceipt(null, emptyList()))))
        probe.assertWithin(probe.compare(noScrim, noScrim), 2f / 255f, "explicit known no-scrim receipt")
        shadowOracleEvidence("popup-composition-known-no-scrim", noScrim, noScrim)
        val top = higher.copy(windowId = "native-window-3", generation = NativeShadowGeneration(23,
            androidx.compose.ui.unit.IntOffset(22, 2), 3000))
        val nested = edit(original) {
            marker(it, 1, 34, 180); marker(it, 11, 64, 214); marker(it, 21, 92, 255)
        }.copy(windowIds = listOf(lower.windowId, higher.windowId, top.windowId), nativeWindows = listOf(
            lower.copy(popup = PopupShadowReceipt(null, listOf(higher.windowId, top.windowId))),
            higher.copy(popup = PopupShadowReceipt(PopupShadowScrim(1f, 41), listOf(top.windowId))), top))
        probe.assertWithin(probe.compare(nested, nested), 2f / 255f, "different per-window nested operations")
        shadowOracleEvidence("popup-composition-nested-valid", nested, nested)
        assertThrows(IllegalStateException::class.java) { shadowMarkerColor(0x40000, true) }
    }

    @Test fun realShadowCallersHaveCompleteNativeFieldsInNormal() = verifyNativeShadowCallers(compose, GlassRendererMode.Normal)
    @Test fun realShadowCallersHaveCompleteNativeFieldsInNone() = verifyNativeShadowCallers(compose, GlassRendererMode.None)
    @Test fun diagnosticRetainedSamplingReferencesAcrossFractionalTranslations() = collectRetainedSamplingDiagnostics(compose)
}

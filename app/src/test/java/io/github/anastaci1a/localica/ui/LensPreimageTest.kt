package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class LensPreimageTest {
    private val button = Rect(0f, 0f, 150f, 150f)

    @Test fun flatPanelReadsItsFootprintPlusTheBlurReachWithinItsPadding() {
        val blur = 10f
        val reach = glassBlurReach(blur)
        assertEquals(kotlin.math.ceil(3f * (.57735f * blur + .5f)), reach, 1e-4f)
        assertEquals(button.inflate(reach), lensRefractionPreimage(button, LensPreimageOptics(null, 0f, 0f, 0f, blur, 100f)))
        // Nothing beyond the recorded padding exists for the lens to read.
        assertEquals(button.inflate(6f), lensRefractionPreimage(button, LensPreimageOptics(null, 0f, 0f, 0f, blur, 6f)))
        assertEquals(button, lensRefractionPreimage(button, LensPreimageOptics(null, 0f, 0f, 0f, 0f, 30f)))
    }

    @Test fun lensDisplacementIsTheShadersLookupAndMagnifiesTheCentre() {
        val optics = liquidGlassPreimageOptics(button.size, null, 1.35f, 1f, 1f, 0f, 96f)
        val preimage = lensRefractionPreimage(button, optics)
        // A convex lens bends every edge lookup inward: it reads a centred region smaller than its face.
        assertTrue("$preimage", preimage.left > 0f && preimage.top > 0f && preimage.right < 150f && preimage.bottom < 150f)
        assertEquals(button.center.x, preimage.center.x, .5f)
        assertEquals(button.center.y, preimage.center.y, .5f)
        // Independent brute-force oracle of the same lookup p + lensRay(p).offset × power.
        val geometry = requireNotNull(optics.geometry)
        var left = Float.MAX_VALUE
        for (i in 0..600) {
            val p = Offset(150f * i / 600f, 75f)
            val f = geometry.field(p)
            if (f.distance < 0f) continue
            left = minOf(left, p.x + lensRay(f, geometry.span, optics.depth, optics.flattening).offset.x)
        }
        assertEquals(left, preimage.left, 1.5f)
        // Without refraction power the lens reads exactly its face.
        assertEquals(button, lensRefractionPreimage(button, optics.copy(power = 0f)))
    }

    @Test fun blurReachExtendsTheRefractedRegionAndIsClampedByPadding() {
        val clear = lensRefractionPreimage(button, liquidGlassPreimageOptics(button.size, null, 1.35f, 1f, 1f, 0f, 96f))
        val blurred = lensRefractionPreimage(button, liquidGlassPreimageOptics(button.size, null, 1.35f, 1f, 1f, 20f, 96f))
        assertEquals(clear.inflate(glassBlurReach(20f)).intersect(button.inflate(96f)), blurred)
        val padded = lensRefractionPreimage(button, liquidGlassPreimageOptics(button.size, null, 1.35f, 1f, 1f, 80f, 32f))
        assertEquals(button.inflate(32f), padded)
    }

    @Test fun preimageIsRelativeToTheFootprintSoTranslationIsFree() {
        val optics = liquidGlassPreimageOptics(Size(240f, 64f), 32f, 1.35f, 1f, 1f, 8f, 64f)
        val at = lensRefractionPreimage(Rect(0f, 0f, 240f, 64f), optics)
        val cache = LensPreimageCache()
        assertEquals(at, cache.preimage(Rect(0f, 0f, 240f, 64f), optics))
        assertSame(cache.preimage(Rect(0f, 0f, 240f, 64f), optics), cache.preimage(Rect(0f, 0f, 240f, 64f), optics))
    }

    @Test fun convexClipKeepsTheOverlap() {
        val square = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))
        val shifted = square.map { it + Offset(5f, 5f) }
        val clipped = clipConvexPolygon(square, shifted.asReversed())
        val area = kotlin.math.abs(clipped.indices.sumOf { i ->
            val a = clipped[i]; val b = clipped[(i + 1) % clipped.size]; (a.x * b.y - b.x * a.y).toDouble() }) / 2
        assertEquals(25.0, area, 1e-3)
    }
}

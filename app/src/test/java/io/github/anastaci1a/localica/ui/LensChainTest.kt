package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LensChainTest {
    private fun chain(blur: String?) = lensChain(blur, "lens") { outer, inner -> "$outer($inner)" }

    @Test fun blurFeedsTheLensInTheSharedFilterOrder() = assertEquals("lens(blur)", chain("blur"))

    @Test fun identityBlurIsElided() = assertEquals("lens", chain(null))

    // Glass map words (height, curvature, offsets scaled to pixels; cos, sin), the displacement
    // lanes in pixels for an identity content transform, then the upper sample limit.
    @Test fun warpPacksBothShadersWordsInUploadOrder() = assertArrayEquals(
        floatArrayOf(40f, 1f, 3f, 0f, 1f, 0f, -150f, 0f, 0f, -150f, 99.5f, 49.5f),
        LensWarp(20.0, -75.0, 1.5f, IntSize(100, 50)).uniforms(2f), 0f)

    @Test fun curvatureClampsAndTransformReachesTheLanes() {
        val transform = DoubleArray(16).also { it[0] = 2.0; it[1] = 3.0; it[4] = 5.0; it[5] = 7.0 }
        val words = LensWarp(20.0, -1.0, 0f, IntSize(16, 8), transform, curvature = 4f).uniforms(1f)
        assertEquals(1f, words[1])
        assertArrayEquals(floatArrayOf(-2f, -5f, -3f, -7f), words.copyOfRange(6, 10), 0f)
    }
}

package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class GlassSurfacePresentationTest {
    private val source = GlassSurfacePresentation(.3f, 1.4f, .23f, Color(0x3300FF00), .72f, lens = 0f)
    private val target = GlassSurfacePresentation(1f, 1f, 1f, Color.Transparent, 1.3f)

    @Test fun sourceEndpointIsTheRecordActuallyDrawn() {
        assertSame(source, source.towards(target, 0f))
        assertSame(source, source.towards(target, -.2f))
        assertSame(target, source.towards(target, 1f))
        assertSame(target, source.towards(target, 1.03f))
        val half = source.towards(target, .5f)
        assertEquals(.65f, half.frost, 1e-6f)
        assertEquals(1.2f, half.refraction, 1e-6f)
        assertEquals(.615f, half.lightMix, 1e-6f)
        assertEquals(1.01f, half.depthScale, 1e-6f)
        assertEquals("A source without a lens morphs its lens in with every other field (#847)", .5f, half.lens, 1e-6f)
        assertEquals(androidx.compose.ui.graphics.lerp(source.tint, target.tint, .5f), half.tint)
        // Reversible: the return passes the same records in reverse.
        target.towards(source, .5f).let { assertEquals(half.frost, it.frost, 1e-6f); assertEquals(half.lightMix, it.lightMix, 1e-6f) }
        val samples = (0..100).map { source.towards(target, it / 100f).lightMix }
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
    }

    @Test fun materialBindingExistsOnlyWithAValidSourceRecord() {
        val anchor = GlassAnchor()
        assertNull("A geometry-only anchor has no material, not a pending one", anchor.material)
        assertEquals(RaisedGlassDepthScale, anchor.depthScale, 0f)
        val holder = mutableStateOf(source)
        anchor.material = holder
        assertSame(source, anchor.material?.value)
        assertEquals(source.depthScale, anchor.depthScale, 0f)
        val eased = source.copy(lightMix = .8f)
        holder.value = eased
        assertSame("Source and morph read the same live holder", eased, anchor.material?.value)
        assertEquals(.8f, anchor.material!!.value.lightMix, 0f)
    }
}

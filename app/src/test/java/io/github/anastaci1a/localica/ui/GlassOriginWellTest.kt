package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassOriginWellTest {
    @Test fun basinJoinsBothPlanesWithoutASlope() {
        assertEquals(0f, glassWellDepth(0f), 0f)
        assertEquals(1f, glassWellDepth(1f), 0f)
        assertEquals(0f, glassWellSlope(0f), 0f)
        assertEquals(0f, glassWellSlope(1f), 0f)
        for (i in 0..1000) {
            val t = i / 1000f
            assertTrue(glassWellDepth(t) in 0f..1.000001f)
            assertTrue(glassWellSlope(t) >= 0f)
            if (i > 0) assertTrue(glassWellDepth(t) + .000001f >= glassWellDepth(t - .001f))
        }
    }
    @Test fun circlePillAndRoundedRectangleHaveFiniteCenterWithoutNormalCreases() {
        val profile = GlassOriginWellProfile()
        for ((w, h, radius) in listOf(Triple(64f,64f,32f), Triple(180f,48f,24f), Triple(150f,80f,16f))) {
            for (y in 0..80) for (x in 0..180) {
                assertTrue(glassWellShade(x*w/180, y*h/80, w,h,radius,profile).isFinite())
            }
            val center = glassWellShade(w/2,h/2,w,h,radius,profile)
            assertEquals(-profile.ambient,center,.000001f)
            assertTrue(abs(center-glassWellShade(w/2+.001f,h/2-.001f,w,h,radius,profile)) < .00001f)
            assertEquals(0f,glassWellShade(w/2,0f,w,h,radius,profile),0f)
        }
    }
    @Test fun casterUsesDrawScaleAndTranslationNotLayoutRectangle() {
        val caster = glassWellCaster(Size(120f,50f),25f,1.2f,.8f,7f)
        assertEquals(-5f,caster.left,.00001f)
        assertEquals(139f,caster.right,.00001f)
        assertEquals(5f,caster.top,.00001f)
        assertEquals(45f,caster.bottom,.00001f)
        assertEquals(30f,caster.topLeftCornerRadius.x,.00001f)
        assertEquals(20f,caster.topLeftCornerRadius.y,.00001f)
    }
    @Test fun invalidDepthProfilesFailClosed() {
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.1f,.5f)) {
            assertTrue(runCatching { GlassOriginWellProfile(depthFraction=bad) }.isFailure)
        }
    }
}

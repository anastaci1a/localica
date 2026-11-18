package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class GlassShadowModelTest {
    @Test fun geometryUsesOneDensityAndModelMultipliers() {
        for (density in listOf(2f, 2.48125f, 3f)) {
            assertEquals(8f * density, GlassShadowShift * density, 0f)
            assertEquals(24f * density, GlassShadowMaskRadius * density, 0f)
            assertEquals(48f * density, glassShadowReach(1f) * density, 0f)
            assertEquals(56f * density, glassShadowInset(1f).value * density, 0f)
            assertEquals(32f * density, glassShadowInset(.5f).value * density, 0f)
            assertEquals(104f * density, glassShadowInset(2f).value * density, 0f)
        }
        assertEquals(.20f, GlassShadowFill, 0f)
    }
    @Test fun profilePinsHalfArithmeticAndSettingsScaleOnlyTheModel() {
        val samples = listOf(-8f to .06805419921875f, 0f to .04998779296875f,
            24f to .0076141357421875f, 48f to 0f)
        for (density in listOf(2f, 2.48125f, 3f)) for ((dp, alpha) in samples) {
            assertEquals(alpha, glassShadowAlpha((dp * density) / density, 1f), 0f)
            assertEquals(alpha * .5f, glassShadowAlpha(dp, .5f), 0f)
            assertEquals(alpha * 2f, glassShadowAlpha(dp, 2f), 0f)
        }
        assertEquals(0f, glassShadowAlpha(100f, 1f), 0f)
    }
    @Test fun cappedRingCountDisclosesItsPhysicalWidth() {
        for ((density, count) in listOf(2f to 112, 2.48125f to 128, 3f to 128)) {
            val reach = glassShadowReach(1f) * density
            assertEquals(count, glassShadowRingCount(reach))
            assertTrue(glassShadowRingExpansion(0, reach) < 48f * density)
            assertTrue(glassShadowRingExpansion(count - 1, reach) > -8f * density)
        }
    }
}

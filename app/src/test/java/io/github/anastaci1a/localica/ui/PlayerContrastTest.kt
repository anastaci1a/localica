package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class PlayerContrastTest {
    @Test fun mixedArtworkUsesPageInkRatherThanBottomPolarity() {
        assertFalse(PlayerPalette(Color.Black, Color.Black, Color.White).isLight())
        assertTrue(PlayerPalette(Color.White, Color.White, Color.Black).isLight())
    }

    @Test fun bothInkPolaritiesUseConfiguredTrimAndWeight() {
        for (lightInk in listOf(false, true)) {
            val off = GlassTone(0f, 2f, lightInk)
            val even = GlassTone(.5f, 1f, lightInk)
            val focused = GlassTone(.5f, 2f, lightInk)
            assertEquals(.5f, off.channel(.5f), 0f)
            assertEquals(if (lightInk) .25f else .75f, even.channel(.5f), 0f)
            assertEquals(if (lightInk) .375f else .625f, focused.channel(.5f), 0f)
        }
    }
}

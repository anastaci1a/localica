package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** #553: lift shadow = Glass spread exactly, a 50-150% multiple of Glass strength, shadow or glow by ink. */
class LiftShadowStyleTest {
        @Test fun defaultIsExactlyTheGlassShadowAndGlowShareTheStrength() {
        assertEquals(1f, io.github.anastaci1a.localica.ui.spec.GlassLiftShadowStrength, 0f)
        assertEquals(io.github.anastaci1a.localica.ui.spec.GlassShadowStrength, liftShadow(false).strength, 0f)
        assertEquals(liftShadow(false).strength, liftShadow(true).strength, 0f)
    }

    }

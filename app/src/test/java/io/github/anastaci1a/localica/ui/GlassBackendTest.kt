package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.TabLensStyle
import org.junit.Assert.*
import org.junit.Test

class GlassBackendTest {
    @Test fun automaticRetainsPlatformDefaultsAndLegacyIsAlwaysAvailable() {
        for (api in listOf(26, 28, 31, 32, 33, 36)) {
            val automatic = if (api >= 33) GlassBackend.Modern else GlassBackend.Legacy
            assertEquals(automatic, effectiveGlassBackend(GlassRendererMode.Automatic, api))
            assertEquals(GlassBackend.Legacy, effectiveGlassBackend(GlassRendererMode.Legacy, api))
        }
    }

    @Test fun unsupportedModernPreferencesResolveSafely() {
        for (api in 26..32) assertEquals(GlassBackend.Legacy,
            effectiveGlassBackend(GlassRendererMode.Modern, api))
        assertEquals(GlassBackend.Modern, effectiveGlassBackend(GlassRendererMode.Modern, 33))
        assertEquals(GlassBackend.Modern, effectiveGlassBackend(GlassRendererMode.Modern, 36))
    }

    @Test fun normalizationKeepsTheRequestedModeEvenWhenItsBackendIsUnsupported() {
        for (mode in GlassRendererMode.entries) {
            val requested = GlassAppearance(blur = Float.NaN, whiteTint = Float.POSITIVE_INFINITY, renderer = mode).normalized()
            assertEquals(GlassAppearance(renderer = mode), requested)
            effectiveGlassBackend(requested.renderer, 26)
            assertEquals(mode, requested.renderer)
            assertEquals(mode, requested.copy(depth = .8f).normalized().renderer)
        }
    }

    @Test fun automaticIsAppendedWithoutChangingExistingPositionalArgumentsOrDefaults() {
        assertEquals(GlassRendererMode.Automatic, GlassAppearance().renderer)
        assertEquals(GlassRendererMode.Automatic, InterfaceSettings().glass.renderer)
        val positional = GlassAppearance(.7f, .1f, .2f, .8f, .3f, TabLensStyle.Overlay,
            .4f, .2f, 19f, .8f, 1.5f, .3f, 1.25f, .17f, true)
        assertEquals(GlassAppearance(blur = .7f, whiteTint = .1f, shadowStrength = .2f, shadowSpread = .8f,
            contrast = .3f, tabStyle = TabLensStyle.Overlay, tabBlur = .4f, tabWhiteTint = .2f,
            shapeDamping = 19f, depth = .8f, contrastWeight = 1.5f, classicOpacity = .3f,
            controlExpansion = 1.25f, overlayDarkening = .17f, overlayForeground = true), positional)
        assertEquals(positional, positional.normalized())
    }
}

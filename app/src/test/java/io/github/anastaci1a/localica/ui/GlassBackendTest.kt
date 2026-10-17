package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassRendererMode
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
    }

    @Test fun liveOpticsRemainUntilTheRequestedLegacyCaptureIsReady() {
        assertTrue(useModernGlass(GlassBackend.Legacy, 36, false))
        assertFalse(useModernGlass(GlassBackend.Legacy, 36, true))
        assertTrue(useModernGlass(GlassBackend.Modern, 36, true))
        assertTrue(useModernGlass(GlassBackend.Modern, 36, false))
        for (backend in GlassBackend.entries) for (ready in listOf(false, true)) {
            assertFalse(useModernGlass(backend, 26, ready))
            assertFalse(useModernGlass(backend, 32, ready))
        }
    }
}

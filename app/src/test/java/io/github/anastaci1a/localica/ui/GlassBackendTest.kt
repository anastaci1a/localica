package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class GlassBackendTest {
    @Test fun normalUsesPlatformSupport() {
        for (api in listOf(26, 28, 31, 32, 33, 36)) {
            val normal = if (api >= 33) GlassBackend.Normal else GlassBackend.AverageFallback
            assertEquals(normal, effectiveGlassBackend(api))
        }
    }

    @Test fun unsupportedNormalResolvesToAverageFallback() {
        for (api in 26..32) assertEquals(GlassBackend.AverageFallback, effectiveGlassBackend(api))
        assertEquals(GlassBackend.Normal, effectiveGlassBackend(33))
        assertEquals(GlassBackend.Normal, effectiveGlassBackend(36))
    }
}

package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassRendererMode
import org.junit.Assert.*
import org.junit.Test

class GlassRendererGenerationTest {
    @Test fun returningToTheSameBackendCannotAdmitAnEarlierGeneration() {
        val first = GlassRendererGeneration(GlassBackend.Modern, 0L)
        val legacy = nextGlassRendererGeneration(first, GlassRendererMode.Legacy, 36)
        val returned = nextGlassRendererGeneration(legacy, GlassRendererMode.Modern, 36)
        assertEquals(GlassRendererGeneration(GlassBackend.Legacy, 1L), legacy)
        assertEquals(GlassRendererGeneration(GlassBackend.Modern, 2L), returned)
        assertNotEquals("A previous Modern capture is not current after A→B→A", first, returned)
        assertNotEquals("A previous Legacy capture is not current after B→A→B", legacy,
            nextGlassRendererGeneration(returned, GlassRendererMode.Legacy, 36))
    }

    @Test fun requestedModeChangesWithoutEffectiveChangesDoNotRestartTheGeneration() {
        for (api in listOf(33, 36)) {
            val current = GlassRendererGeneration(GlassBackend.Modern, 7L)
            assertSame(current, nextGlassRendererGeneration(current, GlassRendererMode.Automatic, api))
            assertSame(current, nextGlassRendererGeneration(current, GlassRendererMode.Modern, api))
        }
    }

    @Test fun unsupportedModernDoesNotTurnLegacyIntoANewCaptureGeneration() {
        for (api in listOf(26, 28, 29, 32)) {
            val current = GlassRendererGeneration(GlassBackend.Legacy, 4L)
            for (mode in GlassRendererMode.entries)
                assertSame("api=$api requested=$mode", current,
                    nextGlassRendererGeneration(current, mode, api))
        }
    }

    @Test fun repeatedEffectiveTransitionsAdvanceExactlyOnceEach() {
        var current = GlassRendererGeneration(GlassBackend.Legacy, 0L)
        repeat(12) { index ->
            val mode = if (index % 2 == 0) GlassRendererMode.Modern else GlassRendererMode.Legacy
            val next = nextGlassRendererGeneration(current, mode, 33)
            assertEquals(index + 1L, next.epoch)
            assertEquals(if (index % 2 == 0) GlassBackend.Modern else GlassBackend.Legacy, next.backend)
            assertSame(next, nextGlassRendererGeneration(next, mode, 33))
            current = next
        }
    }
}

package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassRendererMode
import org.junit.Assert.*
import org.junit.Test

class GlassRendererGenerationTest {
    @Test fun returningToTheSameBackendCannotAdmitAnEarlierGeneration() {
        val first = GlassRendererGeneration(GlassBackend.Normal, 0L)
        val legacy = nextGlassRendererGeneration(first, GlassRendererMode.None, 36)
        val returned = nextGlassRendererGeneration(legacy, GlassRendererMode.Normal, 36)
        assertEquals(GlassRendererGeneration(GlassBackend.None, 1L), legacy)
        assertEquals(GlassRendererGeneration(GlassBackend.Normal, 2L), returned)
        assertNotEquals("A previous Modern capture is not current after A→B→A", first, returned)
        assertNotEquals("A previous Legacy capture is not current after B→A→B", legacy,
            nextGlassRendererGeneration(returned, GlassRendererMode.None, 36))
    }

    @Test fun requestedModeChangesWithoutEffectiveChangesDoNotRestartTheGeneration() {
        for (api in listOf(33, 36)) {
            val current = GlassRendererGeneration(GlassBackend.Normal, 7L)
            assertSame(current, nextGlassRendererGeneration(current, GlassRendererMode.Normal, api))
            assertSame(current, nextGlassRendererGeneration(current, GlassRendererMode.Normal, api))
        }
    }

    @Test fun unsupportedModernDoesNotTurnLegacyIntoANewCaptureGeneration() {
        for (api in listOf(26, 28, 29, 32)) {
            val current = GlassRendererGeneration(GlassBackend.None, 4L)
            for (mode in GlassRendererMode.entries)
                assertSame("api=$api requested=$mode", current,
                    nextGlassRendererGeneration(current, mode, api))
        }
    }

    @Test fun repeatedEffectiveTransitionsAdvanceExactlyOnceEach() {
        var current = GlassRendererGeneration(GlassBackend.None, 0L)
        repeat(12) { index ->
            val mode = if (index % 2 == 0) GlassRendererMode.Normal else GlassRendererMode.None
            val next = nextGlassRendererGeneration(current, mode, 33)
            assertEquals(index + 1L, next.epoch)
            assertEquals(if (index % 2 == 0) GlassBackend.Normal else GlassBackend.None, next.backend)
            assertSame(next, nextGlassRendererGeneration(next, mode, 33))
            current = next
        }
    }
}

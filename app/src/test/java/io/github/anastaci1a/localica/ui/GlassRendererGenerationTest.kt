package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.navigation.GlassRendererMode
import org.junit.Assert.*
import org.junit.Test

class GlassRendererGenerationTest {
    @Test fun returningToTheSameBackendCannotAdmitAnEarlierGeneration() {
        val first = GlassRendererGeneration(GlassBackend.Normal, 0L)
        val none = nextGlassRendererGeneration(first, GlassRendererMode.FlatNone, 36)
        val returned = nextGlassRendererGeneration(none, GlassRendererMode.Normal, 36)
        assertEquals(GlassRendererGeneration(GlassBackend.FlatNone, 1L), none)
        assertEquals(GlassRendererGeneration(GlassBackend.Normal, 2L), returned)
        assertNotEquals("A previous Normal capture is not current after A→B→A", first, returned)
        assertNotEquals("A previous None capture is not current after B→A→B", none,
            nextGlassRendererGeneration(returned, GlassRendererMode.FlatNone, 36))
    }

    @Test fun requestedModeChangesWithoutEffectiveChangesDoNotRestartTheGeneration() {
        for (api in listOf(33, 36)) {
            val current = GlassRendererGeneration(GlassBackend.Normal, 7L)
            assertSame(current, nextGlassRendererGeneration(current, GlassRendererMode.Normal, api))
        }
    }

    @Test fun unsupportedNormalRetainsItsFallbackGeneration() {
        for (api in listOf(26, 28, 29, 32)) {
            val current = GlassRendererGeneration(GlassBackend.AverageFallback, 4L)
            for (mode in listOf(GlassRendererMode.Normal))
                assertSame("api=$api requested=$mode", current,
                    nextGlassRendererGeneration(current, mode, api))
        }
    }

    @Test fun repeatedEffectiveTransitionsAdvanceExactlyOnceEach() {
        var current = GlassRendererGeneration(GlassBackend.FlatNone, 0L)
        repeat(12) { index ->
            val mode = if (index % 2 == 0) GlassRendererMode.Normal else GlassRendererMode.FlatNone
            val next = nextGlassRendererGeneration(current, mode, 33)
            assertEquals(index + 1L, next.epoch)
            assertEquals(if (index % 2 == 0) GlassBackend.Normal else GlassBackend.FlatNone, next.backend)
            assertSame(next, nextGlassRendererGeneration(next, mode, 33))
            current = next
        }
    }
}

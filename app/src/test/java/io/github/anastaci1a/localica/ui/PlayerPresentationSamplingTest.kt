package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.PlayerReveal
import io.github.anastaci1a.localica.ui.engine.PlayerPresentationSampling
import org.junit.Assert.*
import org.junit.Test

class PlayerPresentationSamplingTest {
    private val binding = PlayerPresentationSampling(PlayerReveal.Lyrics::at, PlayerReveal.Handle::at,
        PlayerReveal.Background::at, PlayerReveal.Actions::at)

    @Test fun artworkAdmissionMatchesTheOriginalLyricsProjectionAcrossExactBoundariesAndReversals() {
        val phases = listOf(-.05f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, .72f, .78f,
            Math.nextUp(.78f), .83f, .97f, 1f, 1.05f, Float.NaN)
        for (p in phases + phases.reversed()) for (mode in phases) for (resting in listOf(false, true)) {
            val original = resting && !(playerLyricsMotion(p, mode).contentAlpha > 0f)
            assertEquals("p=$p mode=$mode resting=$resting", original, resting && !binding.sample(p, mode).lyricsOwnInput())
        }
    }

    @Test fun atlasAdmissionPreservesSuppliedIdentityOnlyAtExactZero() {
        val input = Any()
        for (p in listOf(-.05f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, .00001f, .72f, 1f, Float.NaN)) {
            val sample = binding.sample(p, 0f)
            assertSame("p=$p", input.takeIf { p == 0f }, sample.collapsedMaterial(input))
            assertNull(sample.collapsedMaterial<Any>(null))
        }
    }

    @Test fun oneBindingRefreshesExactInputsWithoutMutatingEarlierSamples() {
        val input = Any()
        val collapsed = binding.sample(0f, 0f)
        val lyrics = binding.sample(1f, 1f)
        assertFalse(collapsed.lyricsOwnInput())
        assertSame(input, collapsed.collapsedMaterial(input))
        assertTrue(lyrics.lyricsOwnInput())
        assertNull(lyrics.collapsedMaterial(input))
        val ordinary = binding.sample(1f, 0f)
        assertFalse(ordinary.lyricsOwnInput())
        assertTrue(lyrics.lyricsOwnInput())
        assertSame(input, collapsed.collapsedMaterial(input))
    }
}

package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.PlayerReveal
import io.github.anastaci1a.localica.ui.engine.ExpansionView
import io.github.anastaci1a.localica.ui.engine.FrameStamp
import io.github.anastaci1a.localica.ui.engine.Kinematic
import io.github.anastaci1a.localica.ui.engine.MotionValue
import io.github.anastaci1a.localica.ui.engine.PlayerPresentationSampling
import org.junit.Assert.*
import org.junit.Test

class PlayerPresentationSamplingTest {
    private class Phase(var value: Float) : MotionValue {
        override fun value() = value
        override fun velocity() = 0f
        override fun sample() = Kinematic(value, 0f)
        override fun valueAt(stamp: FrameStamp) = value
        override fun velocityAt(stamp: FrameStamp) = 0f
        override fun sampleAt(stamp: FrameStamp) = sample()
        override val target get() = value
        override val settled = true
    }

    private fun view(phase: Phase) = object : ExpansionView {
        override val phase = phase
        override val expanded get() = phase.value == 1f
        override val inContact = false
        override val canContact = true
    }

    private fun sampling(player: Phase, lyrics: Phase) = PlayerPresentationSampling(view(player), view(lyrics),
        PlayerReveal.Lyrics::at, PlayerReveal.Handle::at, PlayerReveal.Background::at, PlayerReveal.Actions::at)

    @Test fun artworkAdmissionMatchesTheOriginalLyricsProjectionAcrossExactBoundariesAndReversals() {
        val player = Phase(0f)
        val lyrics = Phase(0f)
        val sampling = sampling(player, lyrics)
        val phases = listOf(-.05f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, .72f, .78f,
            Math.nextUp(.78f), .83f, .97f, 1f, 1.05f, Float.NaN)
        for (p in phases + phases.reversed()) for (mode in phases) for (resting in listOf(false, true)) {
            player.value = p
            lyrics.value = mode
            val original = resting && !(playerLyricsMotion(p, mode).contentAlpha > 0f)
            assertEquals("p=$p mode=$mode resting=$resting", original, sampling.artworkInputEnabled(resting))
        }
    }

    @Test fun atlasAdmissionPreservesSuppliedIdentityOnlyAtExactZero() {
        val player = Phase(0f)
        val sampling = sampling(player, Phase(0f))
        val input = Any()
        for (p in listOf(-.05f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, .00001f, .72f, 1f, Float.NaN)) {
            player.value = p
            val original = input.takeIf { p == 0f }
            assertSame("p=$p", original, sampling.collapsedMaterial(input))
            assertNull(sampling.collapsedMaterial<Any>(null))
        }
    }
}

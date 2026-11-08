package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class SettingsHighlightMotionTest {
    @Test fun pulseUsesTheSharedClockAndRetiresAfterRevealHoldAndFade() = EngineHarness().use { h ->
        val owner = SettingsHighlightMotion(h.clock)
        owner.request(true, false)
        assertEquals(1, h.clock.activeCount)
        assertEquals(0f, owner.value(), 0f)
        h.advance(.075)
        val halfway = owner.value()
        assertTrue(halfway > 0f && halfway < .18f)
        h.advance(.075)
        assertEquals(.18f, owner.value(), 0f)
        h.advance(.45)
        assertEquals(.18f, owner.value(), 0f)
        h.advance(.3)
        assertTrue(owner.value() > 0f && owner.value() < .18f)
        h.advance(.3)
        assertEquals(0f, owner.value(), 0f)
        assertTrue(owner.settled)
        assertEquals(0, h.clock.activeCount)
        owner.dispose()
    }

    @Test fun reducedMotionKeepsTheAccessibleHoldWithoutAnimatingAndCancellationRetires() =
        EngineHarness(MotionAccess.Reduced).use { h ->
            val owner = SettingsHighlightMotion(h.clock)
            owner.request(true, true)
            assertEquals(.18f, owner.value(), 0f)
            h.advance(1.199)
            assertEquals(.18f, owner.value(), 0f)
            h.advance(.001)
            assertEquals(0f, owner.value(), 0f)
            assertEquals(0, h.clock.activeCount)
            owner.request(false, true)
            owner.request(true, true)
            assertEquals(.18f, owner.value(), 0f)
            owner.request(false, true)
            assertEquals(0f, owner.value(), 0f)
            assertEquals(1, h.clock.activeCount)
            h.advance(1.2)
            assertEquals(0, h.clock.activeCount)
            owner.dispose()
        }

    @Test fun validatedPresetOverridesDriveThePulseAndMalformedSettingsAreRejected() = EngineHarness().use { h ->
        val preset = SettingsHighlightSpec(100, 200, 300, 900, .12f, EasingSpec(.4f,0f,.6f,1f))
        val spec = (uiSpec(io.github.anastaci1a.localica.navigation.InterfaceSettings(),
            UiSpecData(motion = mapOf(MotionProfile.SettingsHighlight to preset))) as SpecResult.Accepted).spec
        h.clock.configure(EngineContext(1f/120,1000f,MotionAccess.Standard), spec)
        val owner = SettingsHighlightMotion(h.clock)
        owner.request(true, false)
        h.advance(.05)
        assertEquals(.06f, owner.value(), .000001f)
        h.advance(.25)
        assertEquals(.12f, owner.value(), 0f)
        h.advance(.3)
        assertEquals(0, h.clock.activeCount)
        assertTrue(uiSpec(io.github.anastaci1a.localica.navigation.InterfaceSettings(),
            UiSpecData(motion = mapOf(MotionProfile.SettingsHighlight to preset.copy(revealMs = 0)))) is SpecResult.Rejected)
        assertTrue(uiSpec(io.github.anastaci1a.localica.navigation.InterfaceSettings(),
            UiSpecData(motion = mapOf(MotionProfile.SettingsHighlight to preset.copy(easing = EasingSpec(.4f,2f,.2f,2f))))) is SpecResult.Rejected)
        assertTrue(uiSpec(io.github.anastaci1a.localica.navigation.InterfaceSettings(),
            UiSpecData(motion = mapOf(MotionProfile.SettingsHighlight to preset.copy(easing = EasingSpec(0f,0f,1f,1f))))) is SpecResult.Rejected)
        owner.dispose()
    }
}

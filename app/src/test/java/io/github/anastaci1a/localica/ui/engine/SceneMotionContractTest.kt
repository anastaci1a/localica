package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class SceneMotionContractTest {
    @Test fun catalogRetainsIntegerSettingsArithmeticForNormalFasterAndReduced() {
        val expected = mapOf(MotionProfile.NavigationChrome to (225 to 150),
            MotionProfile.MiniPlayerInk to (165 to 110), MotionProfile.PlayerAspect to (412 to 275),
            MotionProfile.NavigationRoute to (300 to 200), MotionProfile.Heading to (165 to 110),
            MotionProfile.OptionsPage to (140 to 140), MotionProfile.LyricsSwap to (300 to 300),
            MotionProfile.LyricsFollow to (300 to 300))
        for ((profile, durations) in expected) {
            assertEquals(durations.first, sceneMotionDuration(StockUiSpec, profile, false, false))
            assertEquals(durations.second, sceneMotionDuration(StockUiSpec, profile, false, true))
            for (faster in listOf(false, true)) assertEquals(0, sceneMotionDuration(StockUiSpec, profile, true, faster))
        }
    }
    @Test fun semanticScalarUsesTheDeliveredComposeTweenWithoutAnalyticalJoins() {
        for ((profile, duration) in mapOf(MotionProfile.NavigationChrome to 225, MotionProfile.PlayerAspect to 412,
            MotionProfile.Heading to 165, MotionProfile.OptionsPage to 140)) {
            val delivered = TargetBasedAnimation(tween<Float>(duration), Float.VectorConverter, .173f, .927f)
            val shared = TargetBasedAnimation(sceneTween(StockUiSpec, profile, false, false), Float.VectorConverter, .173f, .927f)
            for (millis in 0..duration + 10) {
                val time = millis * 1_000_000L
                assertEquals(delivered.getValueFromNanos(time).toRawBits(), shared.getValueFromNanos(time).toRawBits())
                assertEquals(delivered.getVelocityVectorFromNanos(time), shared.getVelocityVectorFromNanos(time))
            }
        }
    }
    @Test fun playerParticipationPreservesStrictAndInclusiveThresholdsAndRawOvershoot() {
        for (p in listOf(-.01f, 0f, Math.nextUp(0f), .7f, Math.nextUp(.7f), .99f, Math.nextUp(.99f),
            Math.nextDown(.999f), .999f, 1f, Math.nextUp(1f))) {
            val facts = playerParticipation(p, .2f, 1f, 1f, 1f, 0f, 1f, false, false, 0f)
            assertEquals(p == 0f, facts.collapsed)
            assertEquals(p > 0f && p < 1f, facts.movingCover)
            assertEquals(p > .7f, facts.recordLyricsBackdrop)
            assertEquals(p >= .99f, facts.recordPlayerBackdrop)
            assertEquals(p > .99f, facts.scrollReady)
            assertEquals(p >= .999f, facts.backgroundGeometryReady)
            assertTrue(facts.lyricsVisible); assertTrue(facts.lyricsInteractive); assertTrue(facts.retainLyrics)
            assertTrue(facts.seekVisible); assertFalse(facts.actionsVisible); assertFalse(facts.recordCoverBackdrop)
        }
        val hidden = playerParticipation(1f, 0f, 0f, 0f, 0f, 0f, 0f, true, true, 1f)
        assertTrue(hidden.retainLyrics); assertFalse(hidden.lyricsVisible); assertFalse(hidden.backgroundGeometryReady)
    }
}

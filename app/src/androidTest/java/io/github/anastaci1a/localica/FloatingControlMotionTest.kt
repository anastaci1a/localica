package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingControlMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newlyMountedControlsFadeAndRiseAndReversalsRetainDisplayedProgress() {
        var shown by mutableStateOf(true)
        lateinit var motion: FloatingControlMotion
        compose.mainClock.autoAdvance = false
        compose.setContent { motion = rememberFloatingControlMotion(shown) }
        compose.runOnIdle {
            assertEquals(0f, motion.alpha, 0f)
            assertEquals(20f, motion.offsetY(20f), 0f)
            assertTrue(motion.retained)
            assertFalse("A fully hidden target cannot accept input", motion.interactive)
        }
        compose.mainClock.advanceTimeBy(96)
        var entering = 0f
        compose.runOnIdle {
            entering = motion.progress
            assertTrue(entering in .01f.. .99f)
            assertEquals(20f * (1f - entering), motion.offsetY(20f), .001f)
            shown = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertFalse("Retained exit drawing immediately releases input", motion.interactive)
            assertTrue(motion.retained)
            assertEquals("Interruption starts at displayed progress", entering, motion.progress, .08f)
        }
        compose.mainClock.advanceTimeBy(64)
        var leaving = 0f
        compose.runOnIdle { leaving = motion.progress; assertTrue(leaving < entering); shown = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(leaving, motion.progress, .08f); assertTrue(motion.interactive) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(1f, motion.progress, 0f); shown = false }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertFalse(motion.retained); assertFalse(motion.interactive) }
    }

    @Test fun fasterFinishesSoonerAndLiveReductionSnapsWithoutResurrectingOldMotion() {
        var shown by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        lateinit var normal: FloatingControlMotion
        lateinit var faster: FloatingControlMotion
        compose.setContent {
            normal = rememberFloatingControlMotion(shown, reduced)
            CompositionLocalProvider(LocalMotionSpeed provides 2f) { faster = rememberFloatingControlMotion(shown, reduced) }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = true }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle {
            assertEquals(1f, faster.progress, 0f)
            assertTrue(normal.progress in .01f.. .999f)
            reduced = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, normal.progress, 0f); shown = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertFalse(normal.retained); assertEquals(0f, normal.progress, 0f); reduced = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("Restoring motion cannot replay the old entrance", 0f, normal.progress, 0f); shown = true }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertTrue(normal.progress in .01f.. .99f) }
    }
}

@OptIn(ExperimentalTestApi::class)
class FloatingControlSystemMotionTest {
    @Test fun liveSystemDisableSnapsAndCannotOverrideExplicitAppReduction() {
        val scale = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
        runComposeUiTest(effectContext = scale) {
            var shown by mutableStateOf(false)
            var appReduced by mutableStateOf(false)
            lateinit var motion: FloatingControlMotion
            setContent { motion = rememberFloatingControlMotion(shown, appReduced) }
            mainClock.autoAdvance = false
            runOnIdle { shown = true }
            mainClock.advanceTimeBy(64)
            runOnIdle { assertTrue(motion.progress in .01f.. .99f); scale.scaleFactor = 0f }
            mainClock.advanceTimeByFrame()
            runOnIdle { assertEquals(1f, motion.progress, 0f); appReduced = true; scale.scaleFactor = 1f; shown = false }
            mainClock.advanceTimeByFrame()
            runOnIdle { assertEquals(0f, motion.progress, 0f); shown = true }
            mainClock.advanceTimeByFrame()
            runOnIdle { assertEquals(1f, motion.progress, 0f); appReduced = false }
            mainClock.advanceTimeByFrame()
            runOnIdle { assertEquals(1f, motion.progress, 0f); shown = false }
            mainClock.advanceTimeBy(64)
            runOnIdle { assertTrue(motion.progress in .01f.. .99f); assertFalse(motion.interactive) }
        }
    }
}

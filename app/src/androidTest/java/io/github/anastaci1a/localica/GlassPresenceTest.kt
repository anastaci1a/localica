package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.rememberProfileValue
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassPresenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun profileAdapterSelectsRiseAndFallAndRetainsScalarTargets() {
        lateinit var harness: EngineHarness
        lateinit var producer: MotionValue
        lateinit var scalar: MotionValue
        var requested by mutableFloatStateOf(0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { harness = EngineHarness() }
        try {
            compose.setContent { ProfileRecord(harness) {
                producer = rememberProfileValue("producer", { requested }, MotionProfile.TouchLight, MotionProfile.ControlOff)
                scalar = rememberProfileValue("scalar", requested, MotionProfile.Control)
            } }
            compose.runOnIdle { requested = 1f }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(1f, producer.target, 0f); assertEquals(1f, scalar.target, 0f)
                harness.advance(.099); assertFalse(producer.settled)
                harness.advance(.001); assertTrue(producer.settled); assertEquals(1f, producer.value(), 0f)
                harness.advance(.079); assertFalse(scalar.settled)
                harness.advance(.001); assertTrue(scalar.settled)
                requested = 0f
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(0f, producer.target, 0f); assertEquals(0f, scalar.target, 0f)
                harness.advance(.179); assertFalse(scalar.settled)
                harness.advance(.001); assertTrue(scalar.settled)
                harness.advance(.089); assertFalse(producer.settled)
                harness.advance(.001); assertTrue(producer.settled); assertEquals(0f, producer.value(), 0f)
            }
        } finally { compose.runOnIdle { harness.close() } }
    }

    @Test fun unchangedScalarTargetJoinsLiveLawAndSpeedContinuously() {
        lateinit var harness: EngineHarness
        lateinit var motion: MotionFloat
        var requested by mutableFloatStateOf(0f)
        var duration by mutableIntStateOf(180)
        var speed by mutableFloatStateOf(1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { harness = EngineHarness() }
        try {
            compose.setContent { ProfileRecord(harness, duration, speed) {
                motion = rememberProfileValue("scalar", requested, MotionProfile.Control) as MotionFloat
            } }
            for (changeLaw in listOf(true, false)) {
                compose.runOnIdle { requested = 1f - requested }
                compose.mainClock.advanceTimeByFrame()
                lateinit var owner: MotionFloat
                lateinit var stamp: FrameStamp
                lateinit var before: Kinematic
                var epoch = 0
                compose.runOnIdle {
                    harness.advance(.04)
                    owner = motion; stamp = harness.clock.stampNow; before = motion.sampleAt(stamp); epoch = motion.epoch.intValue
                    assertTrue(kotlin.math.abs(before.velocity) > .01f)
                    if (changeLaw) duration = 360 else speed = 1.5f
                }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertSame(owner, motion); assertEquals(epoch + 1, motion.epoch.intValue)
                    assertEquals(requested, motion.target, 0f)
                    val after = motion.sampleAt(stamp)
                    assertEquals(before.value, after.value, .00001f); assertEquals(before.velocity, after.velocity, .0001f)
                    harness.advance(duration / speed.toDouble() / 1000 - .001); assertFalse(motion.settled)
                    harness.advance(.001); assertTrue(motion.settled); assertEquals(requested, motion.value(), 0f)
                }
            }
        } finally { compose.runOnIdle { harness.close() } }
    }

    @Composable private fun ProfileRecord(harness: EngineHarness, duration: Int = 180, speed: Float = 1f, content: @Composable () -> Unit) {
        val spec = remember(duration, speed) {
            val law = StockUiSpec.motion.getValue(MotionProfile.Control) as MotionProfileSpec
            ResolvedUiSpec((uiSpec(InterfaceSettings(), UiSpecData(motion = mapOf(MotionProfile.Control to law.copy(durationMs = duration)), speed = speed),
                ExpansionGeometryDomain(1000f, 3000f, 1f / 60)) as SpecResult.Accepted).spec, Density(1f))
        }
        CompositionLocalProvider(LocalUiClock provides harness.clock, LocalEngineContext provides EngineContext(1f / 120, 1000f, MotionAccess.Standard, speed = speed),
            LocalUiSpec provides spec, content = content)
    }

    @Test fun hiddenFootprintIsStableAndRetainedExitReleasesInputImmediately() {
        var visible by mutableStateOf(false)
        var frontClicks = 0
        var behindClicks = 0
        compose.setContent { LocalicaTheme {
            Box(Modifier.padding(40.dp).testTag("slot")) {
                Box(Modifier.matchParentSize().background(Color.White).clickable { behindClicks++ })
                GlassPresence(visible) {
                    GlassActionButton(AppIcon.ActionRevert, "Revert", onClick = { frontClicks++ })
                }
            }
        } }
        val hiddenBounds = compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Revert").performClick()
        assertEquals(hiddenBounds, compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Revert").assertDoesNotExist()
        val exiting = compose.onNodeWithTag("slot").captureToImage().toPixelMap()
        compose.onNodeWithTag("slot").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, frontClicks); assertEquals(1, behindClicks) }
        compose.mainClock.advanceTimeBy(1600)
        val hidden = compose.onNodeWithTag("slot").captureToImage().toPixelMap()
        assertEquals(hiddenBounds, compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot)
        var differences = 0
        for (y in 0 until hidden.height) for (x in 0 until hidden.width) {
            if (hidden[x, y] != exiting[x, y]) differences++
        }
        assertTrue("Closing glass must remain drawn after its input disappears", differences > 20)
    }

    @Test fun reversingPresenceKeepsItsFootprintAndReducedMotionRemovesExitImmediately() {
        var visible by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        compose.setContent { EngineTheme(reduced = reduced) { CompositionLocalProvider(LocalReducedMotion provides reduced) {
            Box(Modifier.padding(40.dp).testTag("slot")) {
                GlassPresence(visible) { GlassActionButton(AppIcon.ActionRevert, "Revert", onClick = {}) }
            }
        } } }
        compose.onNodeWithText("Revert").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Revert").assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("slot").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { reduced = true; visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Revert").assertIsDisplayed()
    }
}

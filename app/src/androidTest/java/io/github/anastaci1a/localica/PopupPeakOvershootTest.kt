package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.StockPopupMotion
import io.github.anastaci1a.localica.ui.spec.springOvershoot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.math.abs
import kotlin.math.max

/** #854 round 2 G3 (native set): a near-full-height dialog rests exactly at the v0.34.1 12 dp margin, and its flight-only spring
 * overshoot passes that margin by at most kappa x edge travel, clipped at the window edge, then settles back. Portrait and landscape
 * each run in their own class, rotated before the activity starts.
 * Native round (item 3): the flight starts at a real laid-out trigger opened by a click, every frame is stepped with the clock held,
 * and each sample is the panel's UNCLIPPED drawn rectangle (its layout corners through localToWindow), so no frame is excluded. */
abstract class PopupPeakOvershootTest(private val landscape: Boolean) {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : TestWatcher() {
        override fun starting(description: Description) { if (landscape) device.setOrientationLeft() else device.setOrientationNatural(); device.waitForIdle() }
        override fun finished(description: Description) { device.setOrientationNatural(); device.unfreezeRotation() }
    }).around(compose)

    private fun drawn(c: LayoutCoordinates): Rect {
        val s = c.size; val pts = listOf(Offset.Zero, Offset(s.width.toFloat(), 0f), Offset(0f, s.height.toFloat()), Offset(s.width.toFloat(), s.height.toFloat())).map { c.localToWindow(it) }
        return Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    @Test fun nearFullHeightDialogRestsAtItsMarginAndOnlyItsFlightPassesIt() {
        val samples = mutableListOf<Rect>()
        var window = Rect.Zero
        var marginPx = 0f
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            anchor = rememberGlassAnchor()
            val view = LocalView.current
            val density = LocalDensity.current
            SideEffect { window = Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()); marginPx = density.popupMarginPx(page = false) }
            Box(Modifier.fillMaxSize()) {
                // A real trigger near the bottom centre, as the queue selector's: laid out, anchored and clicked.
                Box(Modifier.offset { IntOffset(view.width / 2 - 60, view.height - 220) }.size(with(density) { 120.toDp() }, with(density) { 64.toDp() })
                    .testTag("trigger").glassAnchor(anchor).clickable { open = true })
                GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Center) {
                    Box(Modifier.fillMaxWidth().height(4000.dp).testTag("tall"))
                }
            }
        } }
        compose.waitForIdle()
        val trigger = compose.onNodeWithTag("trigger").fetchSemanticsNode().boundsInWindow
        // R2-2 and item 3: the clock is held from before the click, so every flight frame is stepped and sampled; the run ends after the
        // panel has been still for 60 frames (C-R3-2: a run that reaches the 1200-frame cap does not count).
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("trigger").performClick()
        var still = 0; var frames = 0
        while (still < 60 && frames < 1200) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); frames++
            val panel = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            // Before the first sample the popup may not be composed yet; after it, a missing or detached panel fails (no skipped frame).
            if (panel == null || !panel.layoutInfo.isAttached) { assertTrue("panel lost mid-flight at frame $frames", samples.isEmpty()); continue }
            val r = drawn(panel.layoutInfo.coordinates)
            // Every stepped frame is a sample; stillness only decides when the run ends.
            if (samples.lastOrNull() == r) still++ else still = 0
            samples += r
        }
        compose.mainClock.autoAdvance = true
        assertTrue("still for 60 frames before the cap (frames=$frames)", still >= 60)
        compose.onNodeWithTag("tall").assertExists()
        val flight = samples.toList()
        val rest = flight.last()
        val tag = if (landscape) "landscape" else "portrait"
        val kappa = springOvershoot(StockPopupMotion.open.zeta.toDouble()).toFloat() / (1f - StockPopupMotion.preparedRamp - StockPopupMotion.rampBlend / 2f)
        // Rest margin and near-full height; the flight was observed (>= 10 samples, some smaller than rest, a real overshoot past
        // rest), stayed within kappa x travel on every edge, and settled back exactly (peakOvershootVerdict, JVM-proven non-vacuous).
        // The flight starts at the trigger: the first unclipped sample is the trigger rectangle (within 2 px).
        val first = flight.first()
        assertTrue("$tag first sample $first is not the trigger $trigger", listOf(first.left - trigger.left, first.top - trigger.top,
            first.right - trigger.right, first.bottom - trigger.bottom).all { abs(it) <= 2f })
        val verdict = peakOvershootVerdict(flight, rest, trigger, window, marginPx, kappa)
        Log.i("PopupPeakOvershoot", "$tag window=$window margin=$marginPx rest=$rest samples=${flight.size} frames=$frames peakBeyondRest=${verdict.peakBeyondRest} beyondWindow=${verdict.beyondWindow} kappa=$kappa")
        assertEquals("$tag", emptyList<String>(), verdict.failures)
        compose.runOnIdle { open = false }
        compose.waitForIdle()
    }
}

class PopupPeakOvershootPortraitTest : PopupPeakOvershootTest(landscape = false)
class PopupPeakOvershootLandscapeTest : PopupPeakOvershootTest(landscape = true)

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

    /** #854 F-I5-2 (reviewer bar 3): a cold window's first admitted glass frame is slow. Here its presentation receipt is held for 50 ms
     * of clock at the trigger, at phase 0 (FixtureTime.presentation), then released. The opening must begin on that receipt, not when
     * admission was recorded: the first presented panel sample sits at the trigger and no presented progress step exceeds 0.35 in the
     * first 5 visible frames at 1.0x. */
    @Test fun aSlowFirstAdmittedFrameDoesNotSkipTheOpening() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            anchor = rememberGlassAnchor()
            val view = LocalView.current; val density = LocalDensity.current
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset { IntOffset(view.width / 2 - 60, view.height - 220) }.size(with(density) { 120.toDp() }, with(density) { 64.toDp() })
                    .testTag("trigger").glassAnchor(anchor).clickable { open = true })
                GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Center) { Box(Modifier.fillMaxWidth().height(400.dp)) }
            }
        } }
        compose.waitForIdle()
        val trigger = compose.onNodeWithTag("trigger").fetchSemanticsNode().boundsInWindow
        compose.mainClock.autoAdvance = false
        val slowPresentation = kotlinx.coroutines.CompletableDeferred<Unit>()
        FixtureTime.presentation = slowPresentation
        try {
        compose.onNodeWithTag("trigger").performClick()
        // Step until the body is admitted at the trigger (phase 0), then make that first admitted frame a slow one.
        var frames = 0
        while (compose.runOnIdle { !anchor.hidden } && frames < 600) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); frames++ }
        assertTrue("admitted before the cap (frames=$frames)", frames < 600)
        // The first presented panel sample is the admitted body at the trigger, at phase 0 (unclipped drawn rect).
        val admitted = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes().single().layoutInfo.coordinates.let { drawn(it) }
        assertTrue("admitted sample $admitted is not at the trigger $trigger", listOf(admitted.left - trigger.left, admitted.top - trigger.top,
            admitted.right - trigger.right, admitted.bottom - trigger.bottom).all { abs(it) <= 2f })
        compose.mainClock.advanceTimeBy(50); compose.waitForIdle()
        // The receipt, not the six-frame deadline, must start the opening: still at rest when the held receipt is released.
        val held = compose.runOnIdle { anchor.contact.expansion.value }
        slowPresentation.complete(Unit)
        val released = frames; var firstVisible = -1
        val visible = mutableListOf<Pair<Float, Rect>>()
        while (visible.size < 5 && frames < 1200) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); frames++
            val p = compose.runOnIdle { anchor.contact.expansion.value }
            val panel = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            if (p > 0f && panel != null && panel.layoutInfo.isAttached) { if (visible.isEmpty()) firstVisible = frames - released; visible += p to drawn(panel.layoutInfo.coordinates) }
        }
        compose.mainClock.autoAdvance = true
        val steps = (listOf(0f) + visible.map { it.first }).zipWithNext { a, b -> b - a }
        Log.i("PopupPeakOvershoot", "slow-first-frame heldProgress=$held framesToFirstVisible=$firstVisible progress=${visible.map { it.first }} steps=$steps frames=$frames")
        assertTrue("5 visible frames before the cap (frames=$frames)", visible.size == 5)
        assertEquals("progress while the receipt is held", 0f, held)
        assertTrue("first visible frame $firstVisible frames after the receipt", firstVisible in 1..2)
        assertTrue("a presented progress step exceeds 0.35: $steps", steps.all { it <= .35f })
        } finally { slowPresentation.complete(Unit); FixtureTime.presentation = null }
    }
    /** #854 F-I5-2 (lead: reopen latency at most +1 frame): a warm reopen's first non-zero frame is at most one frame later than at
     * 31046ed94, before the receipt existed. The spring begins at the presented frame's completion, not at the receipt's delivery. */
    @Test fun aWarmReopenStartsWithinOneFrameOfTheUngatedOpening() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            anchor = rememberGlassAnchor()
            val view = LocalView.current; val density = LocalDensity.current
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset { IntOffset(view.width / 2 - 60, view.height - 220) }.size(with(density) { 120.toDp() }, with(density) { 64.toDp() })
                    .testTag("trigger").glassAnchor(anchor).clickable { open = true })
                GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Center) { Box(Modifier.fillMaxWidth().height(400.dp)) }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("trigger").performClick(); compose.waitForIdle()
        compose.runOnIdle { open = false }; compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("trigger").performClick()
        // Frames from the click to the first frame at or past each progress mark, and every presented step until half open.
        val reached = IntArray(Marks.size) { -1 }; val progress = mutableListOf(0f); var frames = 0
        while (reached.last() < 0 && frames < 120) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); frames++
            val value = compose.runOnIdle { anchor.contact.expansion.value }; progress += value
            Marks.forEachIndexed { i, mark -> if (reached[i] < 0 && value >= mark) reached[i] = frames }
        }
        compose.mainClock.autoAdvance = true
        val steps = progress.zipWithNext { x, y -> y - x }
        Log.i("PopupPeakOvershoot", "warm-reopen framesToMarks=${Marks.toList()}:${reached.toList()} progress=$progress steps=$steps")
        Marks.forEachIndexed { i, mark -> assertTrue("progress $mark within ${ReopenBaselineFrames[i]} + 1 frames (was ${reached[i]})", reached[i] in 0..ReopenBaselineFrames[i] + 1) }
        assertTrue("a presented reopen step exceeds 0.35: $steps", steps.all { it <= .35f })
    }
}

/** The marks a warm reopen is timed to: first motion, 25% and 50% progress. */
private val Marks = floatArrayOf(1e-6f, .25f, .5f)
/** Frames from the reopen click to each of [Marks] at 31046ed94, before the receipt existed, measured with this test on the A35 (3 runs, both orientations: 3, 5, 6). */
private val ReopenBaselineFrames = intArrayOf(3, 5, 6)

class PopupPeakOvershootPortraitTest : PopupPeakOvershootTest(landscape = false)
class PopupPeakOvershootLandscapeTest : PopupPeakOvershootTest(landscape = true)

package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
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
 * each run in their own class, rotated before the activity starts. */
abstract class PopupPeakOvershootTest(private val landscape: Boolean) {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : TestWatcher() {
        override fun starting(description: Description) { if (landscape) device.setOrientationLeft() else device.setOrientationNatural(); device.waitForIdle() }
        override fun finished(description: Description) { device.setOrientationNatural(); device.unfreezeRotation() }
    }).around(compose)

    @Test fun nearFullHeightDialogRestsAtItsMarginAndOnlyItsFlightPassesIt() {
        val samples = mutableListOf<Rect>()
        var window = Rect.Zero
        var marginPx = 0f
        var open by mutableStateOf(false)
        val anchor = GlassAnchor()
        compose.setContent { LocalicaTheme {
            val view = LocalView.current
            val density = LocalDensity.current
            SideEffect {
                window = Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()); marginPx = density.popupMarginPx(page = false)
                // A trigger near the bottom centre, as the queue selector's.
                if (anchor.bounds == Rect.Zero) anchor.bounds = Rect(view.width / 2f - 60f, view.height - 220f, view.width / 2f + 60f, view.height - 156f)
            }
            Box(Modifier.fillMaxSize()) {
                GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Center) {
                    Box(Modifier.fillMaxWidth().height(4000.dp).testTag("tall").onGloballyPositioned { samples += it.boundsInWindow() })
                }
            }
        } }
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("tall").assertExists()
        // R2-2: waitForIdle does not wait for the engine clock. Step frames until the panel has stopped moving for 60 frames
        // (the engine's settle is well under that at 1.0x), so rest is the settled rectangle, not a mid-flight one.
        var still = 0; var frames = 0; var seen = 0
        while (still < 60 && frames < 1200) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); frames++
            val count = compose.runOnIdle { samples.size }
            if (count == seen) still++ else { still = 0; seen = count }
        }
        val flight = compose.runOnIdle { samples.toList() }
        val rest = flight.last()
        val tag = if (landscape) "landscape" else "portrait"
        val kappa = springOvershoot(StockPopupMotion.open.zeta.toDouble()).toFloat() / (1f - StockPopupMotion.preparedRamp - StockPopupMotion.rampBlend / 2f)
        // Rest margin and near-full height; the flight was observed (>= 10 samples, some smaller than rest, a real overshoot past
        // rest), stayed within kappa x travel on every edge, and settled back exactly (peakOvershootVerdict, JVM-proven non-vacuous).
        val verdict = peakOvershootVerdict(flight, rest, anchor.bounds, window, marginPx, kappa)
        Log.i("PopupPeakOvershoot", "$tag window=$window margin=$marginPx rest=$rest samples=${flight.size} frames=$frames peakBeyondRest=${verdict.peakBeyondRest} beyondWindow=${verdict.beyondWindow} kappa=$kappa")
        assertEquals("$tag", emptyList<String>(), verdict.failures)
        compose.runOnIdle { open = false }
        compose.waitForIdle()
    }
}

class PopupPeakOvershootPortraitTest : PopupPeakOvershootTest(landscape = false)
class PopupPeakOvershootLandscapeTest : PopupPeakOvershootTest(landscape = true)

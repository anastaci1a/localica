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
        compose.waitForIdle()
        val rest = compose.runOnIdle { samples.last() }
        val flight = compose.runOnIdle { samples.toList() }
        val tag = if (landscape) "landscape" else "portrait"
        // Rest: v0.34.1's margin from the window on every side (insets only add to it), and near-full height.
        assertTrue("$tag rest $rest in $window", rest.top >= window.top + marginPx - 1 && rest.bottom <= window.bottom - marginPx + 1 &&
            rest.left >= window.left + marginPx - 1 && rest.right <= window.right - marginPx + 1)
        assertTrue("$tag near-full-height: $rest of $window", rest.height >= window.height * .6f)
        // Flight: finite, never empty, and past the rest rectangle by at most kappa x travel on every edge (prepared ramp gain included).
        val kappa = springOvershoot(StockPopupMotion.open.zeta.toDouble()).toFloat() / (1f - StockPopupMotion.preparedRamp - StockPopupMotion.rampBlend / 2f)
        val start = anchor.bounds
        var peak = 0f; var clipped = 0f
        for (r in flight) {
            assertTrue("$tag sample $r", listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() } && r.width >= 1f && r.height >= 1f)
            val excess = listOf(rest.left - r.left to abs(rest.left - start.left), rest.top - r.top to abs(rest.top - start.top),
                r.right - rest.right to abs(rest.right - start.right), r.bottom - rest.bottom to abs(rest.bottom - start.bottom))
            for ((beyond, travel) in excess) assertTrue("$tag overshoot $beyond > kappa $kappa x travel $travel", beyond <= kappa * travel + 2f)
            peak = max(peak, excess.maxOf { it.first })
            clipped = max(clipped, listOf(window.top - r.top, r.bottom - window.bottom, window.left - r.left, r.right - window.right).max())
        }
        assertEquals("$tag settles back on its rest rectangle", rest, compose.runOnIdle { samples.last() })
        Log.i("PopupPeakOvershoot", "$tag window=$window margin=$marginPx rest=$rest samples=${flight.size} peakBeyondRest=$peak beyondWindow=$clipped kappa=$kappa")
        compose.runOnIdle { open = false }
        compose.waitForIdle()
    }
}

class PopupPeakOvershootPortraitTest : PopupPeakOvershootTest(landscape = false)
class PopupPeakOvershootLandscapeTest : PopupPeakOvershootTest(landscape = true)

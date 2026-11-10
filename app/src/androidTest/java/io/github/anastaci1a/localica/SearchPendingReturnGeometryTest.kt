package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A real pending Search return must keep its captured physical envelope while
 * its caller's visibility commit catches up. Uses the existing absent-editor
 * readiness report and shared test clock, without native-inset injection. */
class SearchPendingReturnGeometryTest {
    @get:Rule val compose = localicaComposeRule()
    private data class Measurement(val generation: Long, val height: Int, val clock: Long)
    private data class Placement(val sample: SearchMorphSample, val measurement: Measurement, val clock: Long)
    private data class Physical(val bounds: Rect, val phase: Float, val measurement: Measurement,
        val clock: Long, val viewIdentity: Int, val windowToken: Int)

    @Test fun pendingReturnKeepsItsCapturedEnvelopeAcrossForegroundRemeasurement() {
        var visible by mutableStateOf(false)
        var bodyHeight by mutableStateOf(220.dp)
        var measurement = Measurement(0, 0, 0)
        var grownHeight = 0
        var returnIntents = 0
        var rootActions = 0
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        var rootView: View? = null
        val samples = mutableListOf<SearchMorphSample>()
        val placements = mutableListOf<Placement>()
        val physical = mutableListOf<Physical>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false,
                LocalSearchMorphObserver provides {
                    samples += it
                    placements += Placement(it, measurement, compose.mainClock.currentTime)
                }) {
                rootView = LocalView.current
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    anchor = rememberGlassAnchor()
                    Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).width(240.dp).height(48.dp)
                        .glassAnchor(anchor))
                    ActionIcon(AppIcon.ActionEllipsis, "Root action", glass = false) { rootActions++ }
                    GlassMorphPopup(visible, anchor, { visible = false }, GlassPlacement.Search) {
                        val view = LocalView.current
                        val ready = LocalSearchOpeningReady.current
                        val beginReturn = LocalSearchReturnStart.current
                        val density = LocalDensity.current
                        SideEffect {
                            popupView = view
                            grownHeight = with(density) { 320.dp.roundToPx() }
                            ready?.invoke(false)
                        }
                        Column(Modifier.fillMaxWidth().height(bodyHeight).onSizeChanged {
                            if (measurement.height != it.height)
                                measurement = Measurement(measurement.generation + 1, it.height, compose.mainClock.currentTime)
                        }) {
                            ActionTextButton({
                                checkNotNull(beginReturn).invoke()
                                // Model the interval between the editor's return intent
                                // and its caller committing visibility. Change real
                                // foreground measurement during that same interval.
                                bodyHeight = 320.dp
                                returnIntents++
                            }) { Text("Begin pending return") }
                        }
                    }
                }
            }
        } }
        fun nativeClick(text: String, expected: View, description: Boolean = false) {
            compose.waitUntil(5_000) { expected.hasWindowFocus() }
            val node = (if (description) compose.onNodeWithContentDescription(text) else compose.onNodeWithText(text))
                .fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            assertSame("The intended native window owns the input", expected, view)
            val location = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(location) }
            val point = node.boundsInRoot.center
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .click((location[0] + point.x).toInt(), (location[1] + point.y).toInt()))
        }
        fun sameRect(label: String, expected: Rect, actual: Rect) {
            assertEquals("$label left", expected.left, actual.left, 2f)
            assertEquals("$label top", expected.top, actual.top, 2f)
            assertEquals("$label right", expected.right, actual.right, 2f)
            assertEquals("$label bottom", expected.bottom, actual.bottom, 2f)
        }
        fun physicalObservation(expected: View): Physical {
            val node = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode()
            val native = (node.root as ViewRootForTest).view
            return compose.runOnUiThread {
                assertSame("The same native root owns the physical observation", expected, native)
                val location = IntArray(2)
                native.getLocationOnScreen(location)
                val bounds = node.boundsInRoot.translate(androidx.compose.ui.geometry.Offset(
                    location[0].toFloat(), location[1].toFloat()))
                Physical(bounds, anchor.contact.expansion.value, measurement,
                    compose.mainClock.currentTime, System.identityHashCode(native),
                    System.identityHashCode(requireNotNull(native.windowToken)))
            }
        }
        compose.mainClock.autoAdvance = false
        var home: Rect? = null
        var captured: Rect? = null
        var originalToken: Int? = null
        var admitted: Measurement? = null
        var intentClock: Long? = null
        var primaryFailure: Throwable? = null
        try {
            compose.runOnUiThread { home = anchor.presentedBounds; visible = true }
            compose.advanceMorphOpening(3_200, anchor) { popupView }
            val nativeWindow = requireNotNull(popupView)
            val token = requireNotNull(nativeWindow.windowToken)
            originalToken = System.identityHashCode(token)
            val before = physicalObservation(nativeWindow)
            val index = compose.runOnUiThread {
                assertEquals(1f, anchor.contact.expansion.value, 0f)
                sameRect("Opening sample matches the native physical bounds", samples.last().bounds, before.bounds)
                captured = before.bounds
                placements.size
            }
            nativeClick("Begin pending return", nativeWindow)
            compose.waitUntil(5_000) { compose.runOnUiThread { returnIntents == 1 } }
            intentClock = compose.mainClock.currentTime
            // Frozen before execution: two measurement-admission frames, then
            // the original four post-measurement frames. Never adapt the count
            // to geometry success or an assertion outcome.
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            admitted = compose.runOnUiThread {
                assertEquals("The new foreground was actually measured", grownHeight, measurement.height)
                assertTrue(measurement.generation > before.measurement.generation)
                assertTrue(measurement.clock >= requireNotNull(intentClock))
                measurement
            }
            repeat(4) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val checkpoint = compose.mainClock.currentTime
                val observed = physicalObservation(nativeWindow)
                assertEquals("A passive query admits no extra shared frame", checkpoint, observed.clock)
                physical += observed
            }
            compose.runOnUiThread {
                assertTrue("The caller has not committed visibility yet", visible)
                assertSame(token, nativeWindow.windowToken)
                val pending = placements.drop(index).filter { it.sample.opening && it.sample.flight?.opening == false }
                assertTrue("Observe the real pending return before visibility commits", pending.isNotEmpty())
                val postMeasurement = physical.filter {
                    it.measurement.generation == requireNotNull(admitted).generation &&
                        it.measurement.height == grownHeight && it.clock >= requireNotNull(admitted).clock
                }
                assertEquals("Four actual post-measurement physical observations are retained", 4, postMeasurement.size)
                postMeasurement.forEach { observed ->
                    assertEquals(1f, observed.phase, 0f)
                    assertEquals(originalToken, observed.windowToken)
                    sameRect("PENDING_SEARCH_RETURN: captured physical envelope", requireNotNull(captured), observed.bounds)
                }
                pending.map { it.sample }.forEach { sample ->
                    assertEquals(1f, sample.progress, 0f)
                    sameRect("Captured pending flight", requireNotNull(captured), requireNotNull(sample.flight).from)
                    sameRect("Pending return keeps its physical envelope", requireNotNull(captured), sample.bounds)
                }
                visible = false
            }
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                !anchor.modalPresent && !nativeWindow.isAttachedToWindow
            }
            compose.runOnUiThread {
                val returning = placements.drop(index).map { it.sample }.filter { !it.opening }
                assertTrue(returning.any { it.progress in .01f.. .99f })
                returning.forEach { sample ->
                    val from = requireNotNull(captured)
                    val destination = requireNotNull(home)
                    fun part(a: Float, b: Float) = a + (b - a) * sample.progress
                    sameRect("Committed direct return", Rect(part(destination.left, from.left),
                        part(destination.top, from.top), part(destination.right, from.right),
                        part(destination.bottom, from.bottom)), sample.bounds)
                }
                assertFalse(anchor.hidden)
                assertTrue(anchor.restoredDrawn)
            }
            nativeClick("Root action", requireNotNull(rootView), description = true)
            compose.waitUntil(5_000) { rootActions == 1 }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var diagnosticFailure: Throwable? = null
            fun retainDiagnostic(error: Throwable) {
                val original = primaryFailure
                if (original != null) original.addSuppressed(error)
                else {
                    val prior = diagnosticFailure
                    if (prior == null) diagnosticFailure = error else prior.addSuppressed(error)
                }
            }
            try {
                try {
                    val frozen = compose.runOnUiThread {
                        "measurementFrames=2\npostMeasurementFrames=4\n" +
                            "home=$home\ncaptured=$captured\noriginalToken=$originalToken\n" +
                            "measurement=$measurement\nadmitted=$admitted\nintentClock=$intentClock\n" +
                            "grownHeight=$grownHeight\nreturnIntents=$returnIntents\nrootActions=$rootActions\n" +
                            "originalFailure=$primaryFailure\n" +
                            placements.joinToString("\n", postfix = "\n") +
                            physical.joinToString("\n", postfix = "\n")
                    }
                    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    if (output != null) {
                        val directory = java.io.File(output, "search-pending-return-geometry").apply { mkdirs() }
                        java.io.File(directory, "pending-return.txt").writeText(frozen)
                    }
                } catch (error: Throwable) { retainDiagnostic(error) }
            } finally {
                try {
                    try { compose.runOnUiThread { visible = false } }
                    catch (error: Throwable) { retainDiagnostic(error) }
                } finally {
                    try { compose.mainClock.autoAdvance = true }
                    catch (error: Throwable) { retainDiagnostic(error) }
                    try { compose.waitForIdle() }
                    catch (error: Throwable) { retainDiagnostic(error) }
                }
            }
            if (primaryFailure == null) diagnosticFailure?.let { throw it }
        }
    }
}

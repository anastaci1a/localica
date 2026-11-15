package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupSiblingOwnershipTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun disposingTheOwningHomeClosesItsRegisteredFlightsInThatApplyPass() {
        var mounted by mutableStateOf(true)
        lateinit var home: GlassPopupHome
        val flight = GlassPopupFlight().testRun(PopupEvent.Admitted)
        var firstAfterDisposal: PopupState? = null
        compose.setContent { LocalicaTheme {
            if (mounted) home = rememberGlassPopupHome(false)
            SideEffect { if (!mounted && firstAfterDisposal == null) firstAfterDisposal = flight.state }
            Text("Home lifetime")
        } }
        compose.runOnIdle { home.flights.add(flight) }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { mounted = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                assertFalse(home.mounted)
                assertEquals("the owning dispose, before a later popup composition", PopupState(gen = flight.gen), firstAfterDisposal)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun disposingAClosedSiblingDoesNotRetireTheOpenPresentation() = overlap(false)
    @Test fun heldExitDoesNotRetireANewerPresentationOnTheSameTrigger() = overlap(true)
    @Test fun disposingAWaitingSiblingNeverFollowsTheHeldPresentation() = overlap(true, true)
    @Test fun dismissingAWaitingSiblingNeverFollowsTheHeldPresentation() = overlap(true, dismissWaiting = true)

    /** #920: the registration and window disposers reset the open that holds them now, after a same-window reopen between two recompositions. */
    @Test fun disposingAfterASameWindowReopenResetsTheCurrentOpen() {
        var mounted by mutableStateOf(true)
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var home: GlassPopupHome? = null
        var view: View? = null
        val steps = mutableListOf<GlassTransitionReceipt>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false,
                LocalGlassSurfaceDrawObserver provides { glassDrawProbe?.invoke(it); if (it is GlassTransitionReceipt) steps += it }) {
                anchor = rememberGlassAnchor()
                GlassPopupHome(rememberGlassPopupHome(false).also { home = it }) { Box(Modifier.fillMaxSize()) {
                    ListSearchPill("Reopen trigger", {}, Modifier.align(Alignment.BottomCenter).padding(24.dp).width(240.dp), anchor)
                    if (mounted) GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Search) { view = LocalView.current; Text("Reopened", Modifier.height(200.dp)) }
                } }
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { open = true }
            compose.advanceMorphOpening(2000, anchor) { view }
            lateinit var flight: GlassPopupFlight
            var reopened = 0
            compose.runOnUiThread {
                flight = requireNotNull(anchor.popup)
                val window = requireNotNull(flight.window)
                assertTrue("registered in its home", flight in requireNotNull(home).flights)
                // Closed and reopened in its admitted window with no recomposition between, so both disposers outlive the first open.
                assertTrue(flight.advance(flight.state.gen, PopupEvent.Gone))
                assertTrue(flight.open(SamplingBasis(null, Rect.Zero, null, false)))
                reopened = flight.state.gen
                assertTrue(flight.advance(reopened, PopupEvent.Admitted, window))
                steps.clear()
                mounted = false
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("HomeLeft resets the reopened open: $steps", steps.any { it.event == "HomeLeft" && it.gen == reopened })
                assertNull("the layer is cleared", flight.layer)
                assertFalse("the home no longer lists it", flight in requireNotNull(home).flights)
                assertEquals(PopupTakeover.Closed, flight.takeover)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test fun lateWindowDetachAfterFrameAllowanceUnblocksTheNextFollow() {
        lateinit var host: View
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { host = LocalView.current; scope = rememberCoroutineScope(); Text("Late detach") } }
        compose.mainClock.autoAdvance = false
        lateinit var h: EngineHarness
        lateinit var dialog: android.app.Dialog
        lateinit var node: SurfaceNode
        lateinit var first: ExpandCollapse
        lateinit var next: PopupWindowMotion
        lateinit var home: ShadowPlane
        lateinit var waiting: Job
        lateinit var returning: Job
        lateinit var windowView: View
        val cleanup = mutableListOf<() -> Unit>()
        try {
            compose.runOnUiThread {
                windowView = View(host.context)
                dialog = android.app.Dialog(host.context).apply { setContentView(windowView); show() }
                cleanup += dialog::dismiss
            }
            compose.waitUntil(5000) { windowView.isAttachedToWindow }
            compose.runOnUiThread {
                val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
                h = EngineHarness(spec = spec)
                cleanup += h::close
                home = ShadowPlane(h.clock, HostKey.next(host.rootView))
                val window = ShadowPlane(h.clock, HostKey.next(windowView.rootView)).also { it.view = windowView }
                val start = Rect(0f, 0f, 48f, 48f)
                val end = Rect(40f, 50f, 300f, 500f)
                first = ExpandCollapse(h.clock, spec.expansion, false).also { it.nativeGate(0f); it.setBasis(ExpansionBasis(start, end, 1f)) }
                cleanup += first::dispose
                val bounds = MotionRect(h.clock, start, EdgeRanges(4000f, 4000f, 4000f, 4000f))
                first.bind(bounds)
                node = SurfaceNode(h.clock, "late-window", PersistentShape(h.clock, ShapeTarget(emptyList(), JoinPolicy.Union)), SurfaceTarget(Stratum.Floating, 1f, ShadowRole.Glass), home)
                cleanup += node::dispose
                node.follow(first, Curve.constant(1f), bounds)
                val profile = motionProfileId(MotionProfile.Control)
                first.target(true); node.transfer(window, profile); window.acknowledge()
                first.acknowledgeNativeGate(first.nativeGeneration); h.advance(2.0)
                first.target(false); h.advance(2.0); node.transfer(home, profile)
                assertTrue(node.owns(window))
                next = PopupWindowMotion(h.clock, spec.expansion, "next-window", false, false)
                cleanup += next::dispose
                next.place(start, end, 1f); next.show(true)
                waiting = scope.launch(start = CoroutineStart.UNDISPATCHED) { node.followWindow(next) }
                returning = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitTriggerReturn { awaitCancellation() }
                    first.acknowledgeNativeGate(first.nativeGeneration)
                }
            }
            compose.mainClock.advanceTimeBy(160)
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("The existing frame allowance completed", returning.isCompleted)
                assertTrue(first.retirementReady)
                assertTrue(windowView.isAttachedToWindow)
                assertFalse("The native window is still attached after the allowance", waiting.isCompleted)
                assertFalse(node.canFollow(next.expansion, requireNotNull(next.bounds)))
            }
            compose.mainClock.advanceTimeBy(160)
            compose.waitForIdle()
            compose.runOnUiThread { assertFalse(waiting.isCompleted); dialog.dismiss() }
            compose.waitUntil(5000) { waiting.isCompleted }
            compose.runOnUiThread {
                assertFalse(waiting.isCancelled)
                assertSame(next.bounds, node.transform)
                assertTrue(node.owns(home))
                assertTrue(first.lifecycle.isEmpty())
            }
        } finally {
            compose.runOnUiThread { scope.coroutineContext.cancelChildren(); cleanup.asReversed().forEach { it() } }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun fieldLeases(anchor: GlassAnchor): Int = requireNotNull(anchor.surfaceNode).let { node ->
        node.javaClass.getDeclaredField("windowLeases").apply { isAccessible = true }.getInt(node)
    }

    private fun fieldHost(anchor: GlassAnchor): Any {
        val node = requireNotNull(anchor.surfaceNode)
        val owner = node.javaClass.getDeclaredField("owner\$delegate").apply { isAccessible = true }.get(node) as State<*>
        return (owner.value as ShadowPlane).host.window
    }

    private fun overlap(heldExit: Boolean, disposeWaiting: Boolean = false, dismissWaiting: Boolean = false) {
        var aOpen by mutableStateOf(false)
        var bOpen by mutableStateOf(false)
        var aMounted by mutableStateOf(true)
        var bMounted by mutableStateOf(true)
        lateinit var anchor: GlassAnchor
        var aView: View? = null
        var bView: View? = null
        var aHome: GlassPopupHome? = null
        var dismissA: (() -> Unit)? = null
        var dismissB: (() -> Unit)? = null
        val cancelWaiting = disposeWaiting || dismissWaiting
        val child = GlassPopupFlight()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                anchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    ListSearchPill("Shared trigger", {}, Modifier.align(Alignment.BottomCenter).padding(24.dp).width(240.dp), anchor)
                    if (aMounted) GlassMorphPopup(aOpen, anchor, { aOpen = false }, GlassPlacement.Search) {
                        aView = LocalView.current
                        aHome = LocalGlassPopupHome.current
                        dismissA = LocalPopupDismiss.current
                        Text("Popup A", Modifier.height(220.dp))
                    }
                    if (bMounted) GlassMorphPopup(bOpen, anchor, { bOpen = false }, GlassPlacement.Search) {
                        bView = LocalView.current
                        dismissB = LocalPopupDismiss.current
                        Text("Popup B", Modifier.height(180.dp))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            var a: GlassPopupFlight? = null
            if (heldExit) {
                compose.runOnUiThread { aOpen = true }
                compose.advanceMorphOpening(2000, anchor) { aView }
                compose.onNodeWithText("Popup A").assertIsDisplayed()
                compose.runOnUiThread {
                    a = requireNotNull(anchor.popup)
                    requireNotNull(aHome).flights.add(child)
                    requireNotNull(dismissA).invoke()
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnUiThread {
                    assertFalse(aOpen)
                    assertTrue(requireNotNull(aView).isAttachedToWindow)
                    assertEquals("Physical presentation stays held after logical Hide", 1f, requireNotNull(a).progress.value, 0f)
                    assertEquals("Hide takes effect before child handoff", 0f, requireNotNull(a).progress.targetValue, 0f)
                }
            }
            compose.runOnUiThread { bOpen = true }
            if (heldExit) {
                compose.advanceMorphOpening(2000, anchor) { bView }
                lateinit var waiting: GlassPopupFlight
                compose.runOnUiThread {
                    waiting = requireNotNull(anchor.popup)
                    assertNotSame(a, waiting)
                    assertEquals(PopupTakeover.Admitting, waiting.takeover)
                    assertEquals(1f, requireNotNull(a).progress.value, 0f)
                    assertTrue(requireNotNull(aView).isAttachedToWindow)
                    assertEquals("B reached the native follow wait", 2, fieldLeases(anchor))
                    assertSame("B waits without following A's physical surface", requireNotNull(aView).rootView, fieldHost(anchor))
                    if (disposeWaiting) bMounted = false
                    if (dismissWaiting) requireNotNull(dismissB).invoke()
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnUiThread {
                    if (cancelWaiting) {
                        assertEquals(PopupTakeover.Closed, waiting.takeover)
                        assertNull(anchor.popup)
                        assertSame(requireNotNull(aView).rootView, fieldHost(anchor))
                        assertEquals(1f, requireNotNull(a).progress.value, 0f)
                        assertEquals("Cancelling B releases its wait lease without retiring A", 1, fieldLeases(anchor))
                    }
                    child.testHandOff()
                }
                if (cancelWaiting) {
                    compose.advanceMorphOpening(2000, anchor) { aView }
                    compose.runOnUiThread {
                        assertEquals(PopupTakeover.Closed, requireNotNull(a).takeover)
                        assertFalse(requireNotNull(aView).isAttachedToWindow)
                        assertFalse(requireNotNull(bView).isAttachedToWindow)
                        assertNull(anchor.popup)
                        assertFalse(requireNotNull(anchor.surfaceNode).windowRetained())
                        assertSame("The surface returns home after the waiting collector is cancelled", anchor.shadowPlane?.host?.window, fieldHost(anchor))
                    }
                    compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
                    return
                }
            }
            compose.advanceMorphOpening(2000, anchor) { bView }
            compose.onNodeWithText("Popup B").assertIsDisplayed()
            lateinit var b: GlassPopupFlight
            compose.runOnUiThread {
                b = requireNotNull(anchor.popup)
                assertNotSame(a, b)
                assertSame("Trigger expansion belongs to the current presentation", b.progress, anchor.contact.expansion)
                assertEquals(PopupTakeover.InWindow, b.takeover)
                assertSame("The new presentation owns the physical source field", requireNotNull(bView).rootView, fieldHost(anchor))
                if (heldExit) child.testHandOff() else aMounted = false
            }
            compose.advanceMorphOpening(2000, anchor) { bView }
            compose.runOnUiThread {
                if (heldExit) {
                    assertEquals(PopupTakeover.Closed, requireNotNull(a).takeover)
                    assertFalse(requireNotNull(aView).isAttachedToWindow)
                }
                assertTrue(bOpen)
                assertSame("Old exit/disposal cannot clear a newer admission", b, anchor.popup)
                assertTrue(anchor.hidden && anchor.modalPresent)
                assertEquals(1f, anchor.contact.expansion.value, 0f)
                assertTrue(requireNotNull(bView).isAttachedToWindow)
                assertSame("The old return cannot steal the new window field", requireNotNull(bView).rootView, fieldHost(anchor))
                aMounted = false
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Popup B").assertIsDisplayed()
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
            compose.runOnUiThread { assertSame(b, anchor.popup) }
        } finally {
            compose.runOnUiThread { child.testHandOff(); aOpen = false; bOpen = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
}

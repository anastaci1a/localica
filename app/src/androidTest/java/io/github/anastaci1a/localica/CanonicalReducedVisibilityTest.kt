package io.github.anastaci1a.localica

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Matched proof only. Production, ordinary fixture rules and all original regressions stay immutable. */
class CanonicalReducedVisibilityTest {
    @get:Rule val compose = localicaComposeRule()
    private var reduced by mutableStateOf(false)
    private val ticks = (0L..320L step 16).toList() + 800L

    @Test fun r3FixedReducedParentChild() = popup("r3FixedReducedParentChild", true, false, false)
    @Test fun r3FixedReducedPendingReopen() = popup("r3FixedReducedPendingReopen", true, true, false)
    @Test fun popupLiveReductionDuringOpening() = popup("popupLiveReductionDuringOpening", false, false, true)
    @Test fun popupLiveReductionDuringPendingClose() = popup("popupLiveReductionDuringPendingClose", false, true, true)
    @Test fun searchFixedReducedPendingReturn() = search("searchFixedReducedPendingReturn", true, false, false)
    @Test fun searchFixedReducedInterruptedReopen() = search("searchFixedReducedInterruptedReopen", true, true, false)
    @Test fun searchLiveReductionDuringOpening() = search("searchLiveReductionDuringOpening", false, false, true)
    @Test fun searchLiveReductionDuringReturn() = search("searchLiveReductionDuringReturn", false, true, true)

    private fun owner(anchor: GlassAnchor): ExpandCollapse? {
        val phase = anchor.contact.expansion.javaClass.getDeclaredField("phase").apply { isAccessible = true }
            .get(anchor.contact.expansion)
        return phase.javaClass.getDeclaredMethod("getOwner").apply { isAccessible = true }.invoke(phase) as ExpandCollapse?
    }
    private fun rectangle(owner: ExpandCollapse) = ExpandCollapse::class.java.getDeclaredField("bound")
        .apply { isAccessible = true }.get(owner) as MotionRectView?

    private fun click(description: String, expected: View) {
        compose.waitUntil(5_000) { expected.hasWindowFocus() }
        val matches = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
        assertEquals("Unique semantic input owner", 1, matches.size)
        val node = matches.single(); val view = (node.root as ViewRootForTest).view
        assertSame("The intended native root owns input", expected, view)
        val origin = compose.runOnUiThread { IntArray(2).also(view::getLocationOnScreen) }
        val center = node.boundsInRoot.center
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(
            (origin[0] + center.x).toInt(), (origin[1] + center.y).toInt()))
    }

    /** Fixed ordered clock inventory. A policy assignment and its applied frame have separate receipts. */
    private fun phase(name: String, capture: CanonicalReducedCapture, live: Boolean = false, policyStart: Long = 48L,
        events: (Long) -> Unit = {}, supplemental: () -> Map<String, Any?>) {
        val start = compose.mainClock.currentTime
        for (at in ticks) {
            if (at > 0) compose.mainClock.advanceTimeBy(at - (compose.mainClock.currentTime - start))
            compose.runOnUiThread { events(at) }
            if (live && at in listOf(policyStart, policyStart + 48L)) {
                capture.capture(CanonicalReducedCheckpoint("$name-$at-before-policy", name, at), supplemental)
                compose.runOnUiThread { reduced = at == policyStart }
                // No frame is advanced to force this intent to apply; actual access is exported.
                capture.capture(CanonicalReducedCheckpoint("$name-$at-policy-intent", name, at, at == policyStart), supplemental)
            } else capture.capture(CanonicalReducedCheckpoint("$name-$at", name, at), supplemental)
        }
        assertEquals("Observation never changes the phase clock", start + 800L, compose.mainClock.currentTime)
    }

    private fun popup(method: String, fixed: Boolean, pending: Boolean, live: Boolean) {
        reduced = fixed
        var mounted by mutableStateOf(true)
        var parentOpen by mutableStateOf(false)
        var childOpen by mutableStateOf(false)
        var callerDisposed = false
        var rootActions = 0
        var parentActions = 0
        lateinit var parentAnchor: GlassAnchor
        lateinit var childAnchor: GlassAnchor
        var home: GlassPopupHome? = null
        val root = CanonicalReducedProbe("root", 0)
        val parent = CanonicalReducedProbe("parent", 1)
        val child = CanonicalReducedProbe("child", 2)
        val gate = AtomicBoolean(false)
        val blockedDraws = AtomicInteger(0)
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (gate.get()) { blockedDraws.incrementAndGet(); false } else true
        }
        compose.setContent { EngineRecord(reduced = reduced) {
            CompositionLocalProvider(LocalReducedMotion provides reduced) { LocalicaTheme {
                parentAnchor = rememberGlassAnchor(); childAnchor = rememberGlassAnchor()
                val source = rememberFrostedBackdrop(Unit, dark = false)
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().then(canonicalReducedProbe(root)).recordFrostedBackdrop(source)) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Red) }
                        Column(Modifier.padding(48.dp)) {
                            ActionIcon(AppIcon.ActionEllipsis, "Open parent", anchor = parentAnchor, glass = false) { parentOpen = true }
                            ActionIcon(AppIcon.ActionEllipsis, "Root action", glass = false) { rootActions++ }
                        }
                        if (mounted) {
                            DisposableEffect(Unit) { onDispose { callerDisposed = true } }
                            GlassMenu(parentOpen, parentAnchor, { parentOpen = false }) {
                                home = LocalGlassPopupHome.current
                                Box(Modifier.fillMaxWidth().height(220.dp).then(canonicalReducedProbe(parent))) {
                                    Canvas(Modifier.fillMaxSize()) { drawRect(Color.Green) }
                                    Column(Modifier.padding(24.dp)) {
                                        Text("Parent marker")
                                        ActionIcon(AppIcon.ActionEllipsis, "Open child", anchor = childAnchor, glass = false) { childOpen = true }
                                        ActionIcon(AppIcon.ActionEllipsis, "Parent action", glass = false) { parentActions++ }
                                        GlassMenu(childOpen, childAnchor, { childOpen = false }) {
                                            Box(Modifier.fillMaxWidth().height(180.dp).then(canonicalReducedProbe(child))) {
                                                Canvas(Modifier.fillMaxSize()) { drawRect(Color.Blue) }
                                                Text("Child marker")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
        } }
        compose.mainClock.autoAdvance = false
        val capture = CanonicalReducedCapture(compose, method, listOf(root, parent, child)) { reduced }
        fun state(): Map<String, Any?> {
            val o = owner(parentAnchor)
            val bounds = parentAnchor.presentedBounds
            return linkedMapOf("parentOpen" to parentOpen, "childOpen" to childOpen, "mounted" to mounted,
                "callerDisposed" to callerDisposed, "departing" to (home?.departing ?: false),
                "handedOff" to (home?.handedOff ?: true), "blockedDraws" to blockedDraws.get(),
                "parentModal" to parentAnchor.modalPresent, "childModal" to childAnchor.modalPresent,
                "parentPhase" to parentAnchor.contact.expansion.value, "parentTarget" to parentAnchor.contact.expansion.targetValue,
                "parentBounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                "ownerIdentity" to (o?.let(System::identityHashCode) ?: 0),
                "rectangleIdentity" to (o?.let(::rectangle)?.let(System::identityHashCode) ?: 0),
                "rootActions" to rootActions, "parentActions" to parentActions)
        }
        try {
            capture.capture(CanonicalReducedCheckpoint("initial", "initial", 0), ::state)
            click("Open parent", requireNotNull(root.view))
            compose.waitUntil(5_000) { parentOpen }
            phase("parent-open", capture, live && !pending, supplemental = ::state)
            assertTrue("Parent admitted by frozen final checkpoint", parent.view?.isAttachedToWindow == true)
            val retainedOwner = compose.runOnUiThread { requireNotNull(owner(parentAnchor)) }
            val retainedRectangle = compose.runOnUiThread { rectangle(retainedOwner) }
            click("Open child", requireNotNull(parent.view))
            compose.waitUntil(5_000) { childOpen }
            phase("child-open", capture, supplemental = ::state)
            assertTrue("Child admitted by frozen final checkpoint", child.view?.isAttachedToWindow == true)
            if (pending) {
                compose.runOnUiThread {
                    requireNotNull(child.view).viewTreeObserver.addOnPreDrawListener(preDraw)
                    gate.set(true); child.gateBlocked = true
                    childOpen = false; parentOpen = false
                }
                phase("pending-close-reopen", capture, live, events = { at ->
                    if (at == 64L) parentOpen = true
                    if (at == 128L) { gate.set(false); child.gateBlocked = false; child.view?.invalidate() }
                }, supplemental = ::state)
                compose.runOnUiThread {
                    assertTrue("Original pending native draw gate was exercised", blockedDraws.get() > 0)
                    assertSame(retainedOwner, owner(parentAnchor)); assertSame(retainedRectangle, rectangle(retainedOwner))
                }
            } else {
                compose.runOnUiThread { childOpen = false }
                phase("child-close", capture, supplemental = ::state)
            }
            assertFalse("Child retires at frozen final checkpoint", child.view?.isAttachedToWindow == true)
            click("Parent action", requireNotNull(parent.view))
            compose.waitUntil(5_000) { parentActions == 1 }
            compose.runOnUiThread { parentOpen = false }
            phase("parent-close", capture, supplemental = ::state)
            compose.runOnUiThread { mounted = false }
            phase("caller-disposal", capture, supplemental = ::state)
            assertFalse(parent.view?.isAttachedToWindow == true)
            assertTrue(callerDisposed)
            click("Root action", requireNotNull(root.view))
            compose.waitUntil(5_000) { rootActions == 1 }
            assertEquals(1, rootActions); assertEquals(1, parentActions)
            capture.capture(CanonicalReducedCheckpoint("root-input-restored", "final", 0), ::state)
        } finally {
            compose.runOnUiThread {
                gate.set(false); child.gateBlocked = false
                child.view?.let { if (it.viewTreeObserver.isAlive) it.viewTreeObserver.removeOnPreDrawListener(preDraw); it.invalidate() }
                childOpen = false; parentOpen = false; mounted = false
            }
            capture.close(); compose.mainClock.autoAdvance = true
        }
    }

    private fun search(method: String, fixed: Boolean, interrupted: Boolean, live: Boolean) {
        reduced = fixed
        var visible by mutableStateOf(false)
        var height by mutableStateOf(220.dp)
        var generation = 0
        var measuredHeight = 0
        var measurementClock = 0L
        var returns = 0
        var rootActions = 0
        lateinit var anchor: GlassAnchor
        var beginReturn: (() -> Unit)? = null
        val placements = mutableListOf<SearchMorphSample>()
        val root = CanonicalReducedProbe("root", 0)
        val editor = CanonicalReducedProbe("editor", 1)
        compose.setContent { EngineRecord(reduced = reduced) {
            CompositionLocalProvider(LocalReducedMotion provides reduced, LocalSearchMorphObserver provides { placements += it }) {
                LocalicaTheme {
                    anchor = rememberGlassAnchor()
                    val source = rememberFrostedBackdrop(Unit, dark = false)
                    CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().then(canonicalReducedProbe(root)).recordFrostedBackdrop(source)) {
                            Canvas(Modifier.fillMaxSize()) { drawRect(Color.Red) }
                            Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).width(240.dp).height(48.dp).glassAnchor(anchor))
                            ActionIcon(AppIcon.ActionEllipsis, "Root action", glass = false) { rootActions++ }
                            GlassMorphPopup(visible, anchor, { visible = false }, GlassPlacement.Search) {
                                val ready = LocalSearchOpeningReady.current
                                val startReturn = LocalSearchReturnStart.current
                                SideEffect { ready?.invoke(false); beginReturn = startReturn }
                                Box(Modifier.fillMaxWidth().height(height).then(canonicalReducedProbe(editor))
                                    .onSizeChanged {
                                        if (measuredHeight != it.height) {
                                            measuredHeight = it.height; generation++; measurementClock = compose.mainClock.currentTime
                                        }
                                    }) {
                                    Canvas(Modifier.fillMaxSize()) { drawRect(Color.Green) }
                                    Column {
                                        Text("Editor marker")
                                        ActionIcon(AppIcon.ActionEllipsis, "Begin pending return", glass = false) {
                                            checkNotNull(startReturn).invoke(); height = 320.dp; returns++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        val capture = CanonicalReducedCapture(compose, method, listOf(root, editor)) { reduced }
        fun state(): Map<String, Any?> {
            val bounds = anchor.presentedBounds
            return linkedMapOf("visible" to visible, "modalPresent" to anchor.modalPresent,
                "progress" to anchor.contact.expansion.value, "target" to anchor.contact.expansion.targetValue,
                "presentedBounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                "measurementGeneration" to generation, "measurementHeight" to measuredHeight,
                "measurementClock" to measurementClock, "returnIntents" to returns, "rootActions" to rootActions,
                "lastPlacement" to placements.lastOrNull())
        }
        try {
            capture.capture(CanonicalReducedCheckpoint("initial", "initial", 0), ::state)
            compose.runOnUiThread { visible = true }
            phase("editor-open", capture, live && !interrupted, supplemental = ::state)
            assertTrue("Editor admitted by frozen final checkpoint", editor.view?.isAttachedToWindow == true)
            val originalToken = compose.runOnUiThread { requireNotNull(editor.view?.windowToken) }
            val originalGeneration = generation
            click("Begin pending return", requireNotNull(editor.view))
            compose.waitUntil(5_000) { returns == 1 }
            phase("pending-return", capture, live && interrupted, policyStart = 16L, supplemental = ::state)
            compose.runOnUiThread {
                assertEquals(1, returns); assertTrue(generation > originalGeneration)
                assertSame(originalToken, editor.view?.windowToken)
                assertTrue("Caller remains visible during original pending-return interval", visible)
            }
            compose.runOnUiThread { visible = false }
            phase("caller-close", capture, events = { at -> if (interrupted && !live && at == 16L) visible = true }, supplemental = ::state)
            if (interrupted && !live) {
                assertTrue("Interrupted reopen paints a live editor window", editor.view?.isAttachedToWindow == true)
                compose.runOnUiThread { visible = false }
                phase("reopened-editor-close", capture, supplemental = ::state)
            }
            assertFalse("Editor retires at frozen final checkpoint", editor.view?.isAttachedToWindow == true)
            compose.runOnUiThread { visible = true }
            phase("new-intent-reopen", capture, supplemental = ::state)
            assertTrue(editor.view?.isAttachedToWindow == true)
            compose.runOnUiThread { visible = false }
            phase("final-close", capture, supplemental = ::state)
            assertFalse(editor.view?.isAttachedToWindow == true)
            click("Root action", requireNotNull(root.view))
            compose.waitUntil(5_000) { rootActions == 1 }
            assertEquals(1, rootActions)
            capture.capture(CanonicalReducedCheckpoint("root-input-restored", "final", 0), ::state)
        } finally { compose.runOnUiThread { visible = false }; capture.close(); compose.mainClock.autoAdvance = true }
    }
}

package io.github.anastaci1a.localica

import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.ExpandCollapse
import io.github.anastaci1a.localica.ui.engine.MotionRectView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real child-window draw admission, parent cancellation and native input.
 * Read-only Engine reflection identifies the actual owner/rectangle on this pinned DEBUG source.
 * No alpha reader, production observer or presented-field inference is installed. */
class PopupParentPendingHandoffTest {
    @get:Rule val compose = localicaComposeRule()

    private fun owner(anchor: GlassAnchor): ExpandCollapse? {
        val adapter = anchor.contact.expansion
        val phase = adapter.javaClass.getDeclaredField("phase").apply { isAccessible = true }.get(adapter)
        return phase.javaClass.getDeclaredMethod("getOwner").apply { isAccessible = true }.invoke(phase) as ExpandCollapse?
    }
    private fun rectangle(owner: ExpandCollapse): MotionRectView = requireNotNull(
        ExpandCollapse::class.java.getDeclaredField("bound").apply { isAccessible = true }.get(owner) as MotionRectView?)

    @Test fun pendingDescendantHandoffCanBeReopenedWithoutReplacingOwners() {
        var parentCallerMounted by mutableStateOf(true)
        var parentCallerDisposed = false
        var parentOpen by mutableStateOf(false)
        var childOpen by mutableStateOf(false)
        var parentActions = 0
        var rootActions = 0
        lateinit var parentAnchor: GlassAnchor
        lateinit var childAnchor: GlassAnchor
        var rootView: View? = null
        var parentView: View? = null
        var childView: View? = null
        var parentHome: GlassPopupHome? = null
        val blocked = AtomicBoolean(false)
        val blockedDraws = AtomicInteger(0)
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (blocked.get()) { blockedDraws.incrementAndGet(); false } else true
        }
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            rootView = LocalView.current
            parentAnchor = rememberGlassAnchor()
            childAnchor = rememberGlassAnchor()
            val source = rememberFrostedBackdrop(Unit, dark = false)
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                Box(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF808080)) }
                    Column(Modifier.padding(48.dp)) {
                        ActionIcon(AppIcon.ActionEllipsis, "Open parent", anchor = parentAnchor, glass = false) { parentOpen = true }
                        ActionIcon(AppIcon.ActionEllipsis, "Root action", glass = false) { rootActions++ }
                    }
                    if (parentCallerMounted) {
                    DisposableEffect(Unit) { onDispose { parentCallerDisposed = true } }
                    GlassMenu(parentOpen, parentAnchor, { parentOpen = false }) {
                        parentView = LocalView.current
                        parentHome = LocalGlassPopupHome.current
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("Parent")
                            ActionIcon(AppIcon.ActionEllipsis, "Open child", anchor = childAnchor, glass = false) { childOpen = true }
                            ActionIcon(AppIcon.ActionEllipsis, "Parent action", glass = false) { parentActions++ }
                            GlassMenu(childOpen, childAnchor, { childOpen = false }) {
                                childView = LocalView.current
                                Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Child") }
                            }
                        }
                    }
                    }
                }
            }
        } } }
        fun nativeClick(description: String, expectedView: View) {
            compose.waitUntil(5_000) { expectedView.hasWindowFocus() }
            val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            assertSame("The intended native root owns the control", expectedView, view)
            val origin = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(origin) }
            val point = node.boundsInRoot.center
            assertTrue("Native click was accepted", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .click((origin[0] + point.x).toInt(), (origin[1] + point.y).toInt()))
        }
        fun record(stage: String) = compose.runOnUiThread {
            val currentOwner = owner(parentAnchor)
            Log.i("Stage3HandoffReopen", org.json.JSONObject()
                .put("stage", stage).put("clock", compose.mainClock.currentTime)
                .put("blockedDraws", blockedDraws.get()).put("parentOpen", parentOpen).put("childOpen", childOpen)
                .put("parentCallerMounted", parentCallerMounted).put("parentCallerDisposed", parentCallerDisposed)
                .put("departing", parentHome?.departing).put("handedOff", parentHome?.handedOff)
                .put("target", parentAnchor.contact.expansion.targetValue).put("phase", parentAnchor.contact.expansion.value)
                .put("owner", currentOwner?.let { System.identityHashCode(it) })
                .put("rectangle", currentOwner?.let { System.identityHashCode(rectangle(it)) })
                .put("parentWindow", parentView?.windowToken?.let { System.identityHashCode(it) })
                .put("childAttached", childView?.isAttachedToWindow).toString())
        }
        compose.mainClock.autoAdvance = false
        try {
            nativeClick("Open parent", requireNotNull(rootView))
            compose.waitUntil(5_000) { parentOpen }
            compose.advanceMorphOpening(3_200, parentAnchor) { parentView }
            nativeClick("Open child", requireNotNull(parentView))
            compose.waitUntil(5_000) { childOpen }
            compose.advanceMorphOpening(3_200, childAnchor) { childView }
            val home = requireNotNull(parentHome)
            val nativeParent = requireNotNull(parentView)
            val nativeChild = requireNotNull(childView)
            val parentToken = requireNotNull(nativeParent.windowToken)
            val childToken = requireNotNull(nativeChild.windowToken)
            assertNotSame("Nested windows have distinct tokens", parentToken, childToken)
            lateinit var retainedOwner: ExpandCollapse
            lateinit var retainedRectangle: MotionRectView
            compose.runOnUiThread {
                retainedOwner = requireNotNull(owner(parentAnchor))
                retainedRectangle = rectangle(retainedOwner)
                assertTrue(retainedOwner.owns(retainedRectangle))
                nativeChild.viewTreeObserver.addOnPreDrawListener(preDraw)
                blocked.set(true)
                childOpen = false
                parentOpen = false
            }
            // Admit both close intents without waiting for the deliberately blocked child draw.
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.waitUntil(5_000) { compose.runOnUiThread { home.departing && blockedDraws.get() > 0 } }
            compose.mainClock.advanceTimeBy(48)
            record("pending-child-handoff")
            compose.runOnUiThread {
                assertEquals("The pending flight belongs to this one native child", 1, home.flights.size)
                assertNotNull(home.flights.single().layer)
                assertFalse(home.flights.single().prepared)
                assertFalse("The fixture has a real pending descendant handoff", home.handedOff)
                assertEquals("PENDING_CHILD_HANDOFF: parent target remains open", 1f, parentAnchor.contact.expansion.targetValue, 0f)
                assertEquals("The existing parent phase is held", 1f, parentAnchor.contact.expansion.value, 0f)
                assertSame(retainedOwner, owner(parentAnchor))
                assertSame(retainedRectangle, rectangle(retainedOwner))
                assertSame(parentToken, nativeParent.windowToken)
                parentOpen = true
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            record("reopened-before-handoff")
            compose.runOnUiThread {
                assertTrue(parentOpen)
                assertEquals(1f, parentAnchor.contact.expansion.targetValue, 0f)
                assertSame(retainedOwner, owner(parentAnchor))
                assertSame(retainedRectangle, rectangle(retainedOwner))
                assertTrue(nativeParent.isAttachedToWindow)
                assertSame(parentToken, nativeParent.windowToken)
                assertTrue("The child draw is still deliberately blocked", blocked.get())
                assertFalse("The actual parent home consumed reopen before release", home.departing)
                assertTrue(nativeChild.isAttachedToWindow)
                assertSame(childToken, nativeChild.windowToken)
                assertEquals(1, home.flights.size)
                assertFalse(home.flights.single().prepared)
                assertFalse(home.handedOff)
                assertEquals(1f, parentAnchor.contact.expansion.value, 0f)
                assertTrue(retainedOwner.owns(retainedRectangle))
                blocked.set(false)
                nativeChild.invalidate()
            }
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                !childAnchor.modalPresent && !nativeChild.isAttachedToWindow
            }
            compose.runOnUiThread {
                assertTrue(home.handedOff)
                assertFalse(home.departing)
                assertSame(retainedOwner, owner(parentAnchor))
                assertSame(retainedRectangle, rectangle(retainedOwner))
            }
            nativeClick("Parent action", nativeParent)
            compose.waitUntil(5_000) { parentActions == 1 }
            compose.runOnUiThread { parentOpen = false }
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                !parentAnchor.modalPresent && !nativeParent.isAttachedToWindow
            }
            compose.runOnUiThread {
                assertFalse(parentAnchor.hidden)
                assertTrue(parentAnchor.restoredDrawn)
                // A closed GlassMenu deliberately keeps its owner while its caller is composed.
                parentCallerMounted = false
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("The actual caller scope has left composition", parentCallerDisposed)
                // #812 made disposal observable state: read the plain Boolean field or its Boolean State delegate,
                // whichever exactly one representation the source declares, with the same strict assertion.
                val disposal = ExpandCollapse::class.java.declaredFields.single { it.name == "disposed" || it.name == "disposed\$delegate" }
                    .apply { isAccessible = true }.get(retainedOwner)
                assertTrue("The retained owner is disposed with its caller", (if (disposal is State<*>) disposal.value else disposal) == true)
            }
            nativeClick("Root action", requireNotNull(rootView))
            compose.waitUntil(5_000) { rootActions == 1 }
            assertEquals(1, parentActions)
            assertEquals(1, rootActions)
            record("retired-native-windows-and-restored-root-input")
        } finally {
            compose.runOnUiThread {
                blocked.set(false)
                childView?.let { view -> if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(preDraw); view.invalidate() }
                childOpen = false
                parentOpen = false
            }
            compose.mainClock.autoAdvance = true
        }
    }
}

package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.toast.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated live component probes. Real application root/Queue acceptance is separate. */
class ToastPresenterNativeTest {
    @get:Rule val compose = localicaComposeRule()
    private class Fixture {
        var reduced by mutableStateOf(true)
        val surfaces = mutableSetOf<Int>()
        var restingLeft = 0f
        var now = 0L
        val queue = ToastQueue(clockMillis = { now })
        var width by mutableIntStateOf(320)
        var fontScale by mutableFloatStateOf(1f)
        var root: ToastRowRoot? = null
        var blocked by mutableStateOf(false)
        var calls = 0
        var destination: ToastCommand? = null
    }
    private val target = ToastCommand.OpenList("list", "occurrence")
    @Composable private fun Harness(fixture: Fixture, width: Int = 320) {
        val physicalDensity = LocalDensity.current
        val density = Density(physicalDensity.density, fixture.fontScale)
        val spec = remember(density, fixture.reduced) {
            uiSpec(InterfaceSettings(reducedMotion = fixture.reduced), ExpansionGeometryDomain(1000f, 4000f, 1f / 24), density)
        }
        CompositionLocalProvider(LocalEngineContext provides EngineContext(1f / 60, 1000f, spec.spec.access),
            LocalUiSpec provides spec, LocalDensity provides density,
            LocalGlassSurfaceDrawObserver provides { fixture.surfaces += it.surfaceIdentity }) {
            LocalicaTheme {
                val viewport = remember { FloatingRowViewport() }
                val bottom = { (viewport.bounds?.bottom ?: 0f) - with(density) { 8.dp.toPx() } }
                val seat = rememberFloatingRowSeat(bottom, bottom, { 0f }, { null }, { false })
                val root = remember { ToastRowRoot(viewport, seat, ToastRowPresentation()) }
                SideEffect { fixture.root = root; fixture.restingLeft = with(density) { spec.spec.space.getValue(Space.Base).toPx() } }
                Box(Modifier.width(fixture.width.dp).fillMaxHeight().floatingRowViewport(viewport)) {
                    ToastPresenter(fixture.queue, root, fixture.blocked, { command, current ->
                        assertTrue(current()); fixture.calls++; fixture.destination = command
                    }, with(density) { glassShadowInset().toPx() },
                        surface = { modifier, body -> LiquidGlassSurface(modifier, pressFeedback = false) { body() } },
                        content = { model -> ToastBody(model, MaterialTheme.typography.bodyMedium,
                            artwork = { uri, modifier -> AlbumArtwork(uri, modifier) },
                            action = { label, activate, enabled, modifier ->
                                ActionTextButton(activate, modifier, enabled) { Text(label) }
                            }) })
                }
            }
        }
    }
    private fun launch(fixture: Fixture, message: ToastMessage, width: Int = 320) {
        fixture.width = width
        fixture.queue.showToast(message.copy(durationMillis = 60_000))
        compose.setContent { Harness(fixture, width) }
        compose.waitUntil(10000) { fixture.queue.state.value.phase == ToastPhase.Visible &&
            fixture.queue.state.value.deadline != null }
        compose.waitForIdle()
    }
    @Test fun nativeBodyDownUpClaimsOnceAfterContactSettles() {
        val fixture = Fixture()
        launch(fixture, ToastMessage("Added", tap = target))
        compose.onNodeWithTag("toast713-body").performTouchInput { click() }
        compose.waitUntil(10000) { fixture.calls == 1 }
        assertEquals(target, fixture.destination)
        compose.waitForIdle()
        assertEquals(1, fixture.calls)
    }
    @Test fun trailingActionDownUpClaimsOnceOnPassiveBody() {
        val fixture = Fixture()
        launch(fixture, ToastMessage("Added", action = ToastAction("Open", target)))
        compose.onNodeWithTag("toast713-action").performTouchInput { click() }
        compose.waitUntil(10000) { fixture.calls == 1 }
        assertEquals(target, fixture.destination)
        compose.waitForIdle()
        assertEquals(1, fixture.calls)
    }
    @Test fun operationRemainsPassiveAndBlockedDismissMatchesTouch() {
        val fixture = Fixture()
        val operation = ToastCommand.Operation("future", "retry", 7)
        launch(fixture, ToastMessage("Status", tap = operation, action = ToastAction("Retry", operation)))
        compose.onNodeWithTag("toast713-body").assertHasNoClickAction()
        compose.onNodeWithTag("toast713-action").assertDoesNotExist()
        compose.onNodeWithTag("toast713-body").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        val bodyBounds = compose.onNodeWithTag("toast713-body").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { fixture.blocked = true }
        compose.onNodeWithTag("toast713-body").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(bodyBounds.center) }
        assertEquals(0, fixture.calls)
        assertEquals(ToastPhase.Visible, fixture.queue.state.value.phase)
    }
    @Test fun longActionCannotStarveRequiredReadingWidth() {
        val fixture = Fixture()
        fixture.queue.showToast(ToastMessage("Required message", action = ToastAction("Open a very long destination label", target), durationMillis = 60_000))
        fixture.width = 180
        fixture.fontScale = 2.5f
        compose.setContent { Harness(fixture, 180) }
        compose.onNodeWithTag("toast713-message", useUnmergedTree = true).assertWidthIsAtLeast(1.dp)
        compose.runOnIdle { assertEquals(0, fixture.calls) }
    }
    @Test fun nativeHoldInvalidatesOldDeadlineAndReleaseResetsFullDwellAtRest() {
        val fixture = Fixture()
        launch(fixture, ToastMessage("Status"))
        val old = fixture.queue.state.value.deadline!!
        compose.mainClock.autoAdvance = false
        try {
        compose.onNodeWithTag("toast713-body").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(5000) { ToastPause.Contact in fixture.queue.state.value.pauses }
        val fullAccessibleDwell = old.atMillis - fixture.now
        assertTrue("The recommended dwell must be finite", fullAccessibleDwell in 60_000L..Int.MAX_VALUE.toLong())
        fixture.now += maxOf(120_000L, fullAccessibleDwell + 10000)
        assertNull(fixture.queue.state.value.deadline)
        assertFalse(fixture.queue.timeout(old))
        assertEquals(ToastPhase.Visible, fixture.queue.state.value.phase)
        compose.onNodeWithTag("toast713-body").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(10000) { fixture.queue.state.value.deadline != null }
        assertEquals(fixture.now + fullAccessibleDwell, fixture.queue.state.value.deadline!!.atMillis)
        assertFalse(fixture.queue.timeout(old))
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun rightClampThenLeftSwipeUsesPhysicalDistanceAndRetiresIntoFifo() {
        val fixture = Fixture()
        launch(fixture, ToastMessage("First"))
        val first = fixture.queue.state.value.current!!.id
        fixture.queue.showToast(ToastMessage("Next", durationMillis = 60000))
        val bodyWidth = compose.onNodeWithTag("toast713-body").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("toast713-body").performTouchInput {
            down(center)
            moveBy(Offset(bodyWidth * .8f, 0f), delayMillis = 500)
            moveBy(Offset(-bodyWidth * .65f, 0f), delayMillis = 500)
            up()
        }
        compose.waitUntil(10000) { fixture.queue.state.value.current?.id != first }
        assertEquals("Next", fixture.queue.state.value.current!!.content.message)
        assertEquals(0, fixture.calls)
    }
    @Test fun positiveWidthReflowKeepsUnreadTextGatedBeforeDwell() {
        val fixture = Fixture()
        fixture.queue.showToast(ToastMessage("A long message that must travel all the way through the measured text viewport before dwell", durationMillis = 60000))
        compose.setContent { Harness(fixture) }
        compose.waitUntil(10000) { fixture.queue.state.value.phase == ToastPhase.Visible }
        assertNull(fixture.queue.state.value.deadline)
        compose.mainClock.autoAdvance = false
        try {
        compose.runOnIdle { fixture.width = 180; fixture.fontScale = 2.5f }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("toast713-message", useUnmergedTree = true).assertWidthIsAtLeast(1.dp)
        assertNull(fixture.queue.state.value.deadline)
        assertFalse(fixture.queue.state.value.marqueeComplete)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun realAccessibleActionClaimsOnceWhileActualFocusPausesDwell() {
        ToastNativeServiceFixture(compose).use { service ->
            val fixture = Fixture()
            launch(fixture, ToastMessage("Added", action = ToastAction("Open", target)))
            service.act("toast713-action", AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            compose.waitUntil(5000) { ToastPause.Accessibility in fixture.queue.state.value.pauses }
            assertNull(fixture.queue.state.value.deadline)
            service.act("toast713-action", AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitUntil(10000) { fixture.calls == 1 }
            assertEquals(target, fixture.destination)
            compose.waitForIdle()
            assertEquals(1, fixture.calls)
        }
    }
    @Test fun realAccessibleDismissIsHiddenWhenBlockedThenRetiresWithoutClaim() {
        ToastNativeServiceFixture(compose).use { service ->
            val fixture = Fixture()
            launch(fixture, ToastMessage("Status"))
            service.act("toast713-body", AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            compose.waitUntil(5000) { ToastPause.Accessibility in fixture.queue.state.value.pauses }
            val old = service.node("toast713-body")!!
            compose.runOnIdle { fixture.blocked = true }
            compose.waitForIdle()
            assertNull(service.node("toast713-body"))
            try { assertFalse(old.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) } finally { old.recycle() }
            assertEquals(ToastPhase.Visible, fixture.queue.state.value.phase)
            compose.runOnIdle { fixture.blocked = false }
            service.act("toast713-body", AccessibilityNodeInfo.ACTION_DISMISS)
            compose.waitUntil(10000) { fixture.queue.state.value.current == null }
            assertEquals(0, fixture.calls)
        }
    }

    @Test fun standardCancelledSwipeReturnsSameSurfaceAndResetsFullDwell() {
        val fixture = Fixture().apply { reduced = false }
        launch(fixture, ToastMessage("Status"))
        val entry = fixture.queue.state.value.current!!
        val before = requireNotNull(fixture.root!!.presentation.body())
        val fullDwell = fixture.queue.state.value.deadline!!.atMillis - fixture.now
        assertEquals(1, fixture.surfaces.size)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("toast713-body").performTouchInput {
                down(center); moveBy(Offset(-before.width * .2f, 0f), delayMillis = 500); cancel()
            }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(requireNotNull(fixture.root!!.presentation.body()).left < before.left)
            assertNull(fixture.queue.state.value.deadline)
            compose.mainClock.advanceTimeBy(1200)
            compose.waitUntil(5000) { fixture.queue.state.value.deadline != null }
            assertEquals(entry.id, fixture.queue.state.value.current!!.id)
            assertEquals(before.left, requireNotNull(fixture.root!!.presentation.body()).left, .01f)
            assertEquals(fixture.now + fullDwell, fixture.queue.state.value.deadline!!.atMillis)
            assertEquals(1, fixture.surfaces.size)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun standardShortFlingDismissesBeforePhysicalHalfWidth() {
        val fixture = Fixture().apply { reduced = false }
        launch(fixture, ToastMessage("First"))
        val first = fixture.queue.state.value.current!!.id
        fixture.queue.showToast(ToastMessage("Next", durationMillis = 60000))
        val width = compose.onNodeWithTag("toast713-body").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("toast713-body").performTouchInput {
            down(center)
            moveBy(Offset(-width * .07f, 0f), delayMillis = 16)
            moveBy(Offset(-width * .07f, 0f), delayMillis = 16)
            up()
        }
        compose.waitUntil(10000) { fixture.queue.state.value.current?.id != first }
        assertEquals("Next", fixture.queue.state.value.current!!.content.message)
        assertEquals(0, fixture.calls)
    }
    @Test fun standardInterruptedEntryCancelRetainsOneSurfaceUntilRealRest() {
        val fixture = Fixture().apply { reduced = false }
        fixture.queue.showToast(ToastMessage("Status", durationMillis = 60000))
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { Harness(fixture) }
            var entering = false
            for (frame in 0 until 16) {
                compose.mainClock.advanceTimeByFrame()
                val extent = fixture.root?.presentation?.body()
                if (extent != null && extent.right > 16 && extent.left < fixture.restingLeft - 1) {
                    entering = true; break
                }
            }
            assertTrue("Contact interrupts actual nonzero entry", entering)
            val extent = requireNotNull(fixture.root!!.presentation.body())
            val point = Offset((maxOf(0f, extent.left) + extent.right) / 2, extent.center.y)
            compose.onRoot().performTouchInput {
                down(point); moveBy(Offset(-12f, 0f), delayMillis = 500); cancel()
            }
            compose.mainClock.advanceTimeBy(1200)
            compose.waitUntil(5000) { fixture.queue.state.value.phase == ToastPhase.Visible && fixture.queue.state.value.deadline != null }
            assertEquals(fixture.restingLeft, requireNotNull(fixture.root!!.presentation.body()).left, .01f)
            assertEquals(1, fixture.surfaces.size)
            assertEquals(0, fixture.calls)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun coalescingDuringNativeContactRetainsSurfaceAndFencesOldDeadline() {
        val fixture = Fixture()
        val key = ToastKey("status", "same", ToastKind.Info, null, null)
        launch(fixture, ToastMessage("Status", coalescingKey = key))
        val initial = fixture.queue.state.value.current!!
        val old = fixture.queue.state.value.deadline!!
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("toast713-body").performTouchInput { down(center) }
            compose.mainClock.advanceTimeByFrame()
            fixture.queue.showToast(ToastMessage("Updated", durationMillis = 60000, coalescingKey = key))
            fixture.now = 120000
            compose.mainClock.advanceTimeBy(64)
            assertEquals(initial.id, fixture.queue.state.value.current!!.id)
            assertEquals(initial.generation + 1, fixture.queue.state.value.current!!.generation)
            assertFalse(fixture.queue.timeout(old))
            assertNull(fixture.queue.state.value.deadline)
            compose.onNodeWithTag("toast713-body").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1200)
            compose.waitUntil(5000) { fixture.queue.state.value.deadline != null }
            assertEquals(2, fixture.queue.state.value.current!!.count)
            assertEquals(1, fixture.surfaces.size)
            assertEquals(0, fixture.calls)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedCancelledSwipeResetsTicketWithoutRecreatingSurface() {
        val fixture = Fixture()
        launch(fixture, ToastMessage("Status"))
        val original = requireNotNull(fixture.root!!.presentation.body())
        val old = fixture.queue.state.value.deadline!!
        compose.onNodeWithTag("toast713-body").performTouchInput {
            down(center); moveBy(Offset(-original.width * .2f, 0f), delayMillis = 500); cancel()
        }
        compose.waitUntil(5000) { fixture.queue.state.value.deadline?.epoch?.let { it > old.epoch } == true }
        assertEquals(original.left, requireNotNull(fixture.root!!.presentation.body()).left, .01f)
        assertFalse(ToastPause.Contact in fixture.queue.state.value.pauses)
        assertEquals(1, fixture.surfaces.size)
    }
    @Test fun standardRightClampThenLeftSwipeRetiresByPhysicalDistance() {
        val fixture = Fixture().apply { reduced = false }
        launch(fixture, ToastMessage("First"))
        val first = fixture.queue.state.value.current!!.id
        fixture.queue.showToast(ToastMessage("Next", durationMillis = 60000))
        val width = requireNotNull(fixture.root!!.presentation.body()).width
        compose.onNodeWithTag("toast713-body").performTouchInput {
            down(center)
            moveBy(Offset(width * .8f, 0f), delayMillis = 500)
            moveBy(Offset(-width * .65f, 0f), delayMillis = 500)
            up()
        }
        compose.waitUntil(10000) { fixture.queue.state.value.current?.id != first }
        assertEquals("Next", fixture.queue.state.value.current!!.content.message)
        assertEquals(0, fixture.calls)
    }

}

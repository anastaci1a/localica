package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ModalGestureOwnershipTest {
    private class Windows(private val scope: CoroutineScope) {
        val coordinator = ModalGestureCoordinator()
        val root = ModalGestureScope(coordinator, null, scope).also(coordinator::attach)

        fun modal(parent: ModalGestureScope = root, attached: Boolean = true) =
            ModalGestureScope(coordinator, parent, scope).also {
                if (attached) coordinator.attach(it)
            }
    }

    @Test fun rootAloneReceivesInputWhileRetainedDismissedWindowsStayBlocked() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal()
        assertFalse(windows.root.blocked)
        assertTrue("A prepared but inactive window must not own input", popup.blocked)

        popup.activate()
        assertTrue(windows.root.blocked)
        assertFalse(popup.blocked)

        popup.deactivate()
        assertFalse("Logical dismissal releases the root before exit drawing finishes", windows.root.blocked)
        assertTrue("Retained exit drawing does not keep a live input surface", popup.blocked)
    }

    @Test fun activationTakesOverBeforeTheNewNativeWindowAttaches() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal(attached = false)
        popup.activate()
        assertTrue("Source input ends while the popup is still preparing", windows.root.blocked)

        windows.coordinator.attach(popup)
        assertFalse(popup.blocked)
        assertTrue(windows.root.blocked)
    }

    @Test fun siblingPopupTakesOverThePreviousWindowAndDismissalRestoresIt() = runTest {
        val windows = Windows(backgroundScope)
        val first = windows.modal()
        val second = windows.modal()
        first.activate()
        var firstCancellations = 0
        first.registerCancellation { firstCancellations++ }

        second.activate()
        assertEquals(1, firstCancellations)
        assertTrue(windows.root.blocked)
        assertTrue(first.blocked)
        assertFalse(second.blocked)

        second.deactivate()
        assertTrue("The remaining popup still owns the root", windows.root.blocked)
        assertFalse(first.blocked)
        assertTrue(second.blocked)
    }

    @Test fun nestedDismissedWindowDoesNotInheritItsParentsInputPermission() = runTest {
        val windows = Windows(backgroundScope)
        val parent = windows.modal()
        val child = windows.modal(parent)
        parent.activate()
        child.activate()
        assertTrue(windows.root.blocked)
        assertTrue(parent.blocked)
        assertFalse(child.blocked)

        child.deactivate()
        assertFalse(parent.blocked)
        assertTrue("A dismissed child is not enabled merely because its parent is topmost", child.blocked)
        assertTrue(windows.root.blocked)

        parent.deactivate()
        assertFalse(windows.root.blocked)
        assertTrue(parent.blocked)
        assertTrue(child.blocked)
    }

    @Test fun dismissingALowerWindowDoesNotReleaseOrCancelTheCurrentTop() = runTest {
        val windows = Windows(backgroundScope)
        val lower = windows.modal()
        val top = windows.modal()
        lower.activate()
        top.activate()
        var topCancellations = 0
        top.registerCancellation { topCancellations++ }

        lower.deactivate()
        assertTrue(windows.root.blocked)
        assertTrue(lower.blocked)
        assertFalse(top.blocked)
        assertEquals(0, topCancellations)

        top.deactivate()
        assertFalse(windows.root.blocked)
        assertTrue(lower.blocked)
        assertTrue(top.blocked)
        assertEquals(1, topCancellations)
    }

    @Test fun repeatedActivationNeitherReordersTheStackNorNeedsExtraDismissals() = runTest {
        val windows = Windows(backgroundScope)
        val first = windows.modal()
        val second = windows.modal()
        first.activate()
        first.activate()
        second.activate()
        first.activate()
        second.activate()
        assertTrue(first.blocked)
        assertFalse(second.blocked)

        second.deactivate()
        second.deactivate()
        assertFalse(first.blocked)
        assertTrue(windows.root.blocked)

        first.deactivate()
        first.deactivate()
        assertFalse("Idempotent acquisition must not leave a phantom modal lease", windows.root.blocked)
        assertTrue(first.blocked)
        assertTrue(second.blocked)
    }

    @Test fun replacingANativeWindowPreservesItsStillVisibleModalLease() = runTest {
        val windows = Windows(backgroundScope)
        val lower = windows.modal()
        val top = windows.modal()
        lower.activate()
        top.activate()

        windows.coordinator.detach(top)
        assertTrue("Detaching the native window must not dismiss the logical popup", windows.root.blocked)
        assertTrue(lower.blocked)

        windows.coordinator.attach(top)
        assertFalse(top.blocked)
        assertTrue(lower.blocked)
        assertTrue(windows.root.blocked)

        top.deactivate()
        assertFalse(lower.blocked)
        assertTrue(windows.root.blocked)
    }

    @Test fun aDetachedPopupCanDismissBeforeItsRetainedWindowReattaches() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal()
        popup.activate()
        windows.coordinator.detach(popup)
        popup.deactivate()
        assertFalse(windows.root.blocked)

        windows.coordinator.attach(popup)
        assertTrue(popup.blocked)
        assertFalse(windows.root.blocked)
    }

    @Test fun cancellationsRunOncePerTakeoverAndLateRegistrationCancelsImmediately() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal()
        var cancellations = 0
        val unregister = windows.root.registerCancellation { cancellations++ }
        popup.activate()
        popup.activate()
        windows.coordinator.attach(windows.root)
        assertEquals(1, cancellations)

        var lateCancellations = 0
        windows.root.registerCancellation { lateCancellations++ }
        assertEquals("A newly composed handler cannot escape an existing modal", 1, lateCancellations)
        unregister()
        popup.deactivate()
        popup.activate()
        assertEquals(1, cancellations)
        assertEquals(2, lateCancellations)
    }

    @Test fun quickDismissalDoesNotReviveAnOldQueuedFlingOrItsSnapDeltas() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal()
        val barrier = ModalScrollBarrier(windows.root)
        val delta = Offset(3f, -21f)
        val velocity = Velocity(40f, -850f)
        assertEquals(Offset.Zero, barrier.onPreScroll(delta, NestedScrollSource.UserInput))

        popup.activate()
        popup.deactivate()
        assertFalse(windows.root.blocked)
        assertEquals("A queued drag-stop still belongs to the canceled contact", velocity,
            barrier.onPreFling(velocity))
        assertEquals("Zero-velocity snap behavior must not move the canceled surface", delta,
            barrier.onPreScroll(delta, NestedScrollSource.SideEffect))
        assertEquals(velocity, barrier.onPreFling(velocity))
    }

    @Test fun newUnblockedUserScrollingRearmsFlingAndSideEffectDeltas() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal()
        val barrier = ModalScrollBarrier(windows.root)
        val delta = Offset(-18f, 27f)
        val velocity = Velocity(-700f, 900f)
        popup.activate()
        popup.deactivate()
        assertEquals(delta, barrier.onPreScroll(delta, NestedScrollSource.SideEffect))

        assertEquals(Offset.Zero, barrier.onPreScroll(delta, NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, barrier.onPreFling(velocity))
        assertEquals(Offset.Zero, barrier.onPreScroll(delta, NestedScrollSource.SideEffect))

        popup.activate()
        popup.deactivate()
        assertEquals("A later takeover invalidates the rearmed contact again", velocity,
            barrier.onPreFling(velocity))
    }

    @Test fun userInputReceivedWhileBlockedCannotRearmTheOldContact() = runTest {
        val windows = Windows(backgroundScope)
        val popup = windows.modal()
        val barrier = ModalScrollBarrier(windows.root)
        val delta = Offset(0f, 32f)
        val velocity = Velocity(0f, 1200f)
        popup.activate()
        assertEquals(delta, barrier.onPreScroll(delta, NestedScrollSource.UserInput))
        assertEquals(delta, barrier.onPreScroll(delta, NestedScrollSource.SideEffect))
        assertEquals(velocity, barrier.onPreFling(velocity))

        popup.deactivate()
        assertEquals("Rejected input cannot authorize a post-dismissal fling", velocity,
            barrier.onPreFling(velocity))
        assertEquals(delta, barrier.onPreScroll(delta, NestedScrollSource.SideEffect))
        assertEquals(Offset.Zero, barrier.onPreScroll(delta, NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, barrier.onPreFling(velocity))
    }

    @Test fun barrierWithoutModalOwnerLeavesOrdinaryNestedScrollingUnchanged() = runTest {
        val barrier = ModalScrollBarrier(null)
        val delta = Offset(14f, -25f)
        assertEquals(Offset.Zero, barrier.onPreScroll(delta, NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, barrier.onPreScroll(delta, NestedScrollSource.SideEffect))
        assertEquals(Velocity.Zero, barrier.onPreFling(Velocity(500f, -900f)))
    }

    @Test fun everyOldPointerRemainsQuarantinedUntilAFreshDown() {
        val stream = ModalPointerStream()
        assertTrue(stream.down(4, blocked = false))
        stream.pointerDown(9)
        stream.cancel()
        assertTrue(stream.active)
        assertTrue(stream.quarantined)

        stream.pointerUp(9)
        assertTrue("The first finger still belongs to the canceled stream", stream.active)
        stream.pointerDown(12)
        assertTrue("An additional finger is not a fresh gesture", stream.quarantined)
        stream.pointerUp(4)
        stream.pointerUp(12)
        stream.end()
        assertFalse(stream.active)
        assertTrue("Old releases must not clear quarantine", stream.quarantined)

        assertTrue(stream.down(4, blocked = false))
        assertTrue(stream.active)
        assertFalse(stream.quarantined)
    }

    @Test fun contactBeginningUnderAModalCannotBecomeFreshWhenTheModalDismisses() {
        val stream = ModalPointerStream()
        assertFalse(stream.down(0, blocked = true))
        stream.pointerDown(1)
        stream.pointerUp(0)
        assertTrue(stream.active)
        assertTrue(stream.quarantined)
        stream.end()
        assertTrue(stream.quarantined)

        assertFalse(stream.down(2, blocked = true))
        assertTrue(stream.quarantined)
        stream.end()
        assertTrue(stream.down(2, blocked = false))
        assertFalse(stream.quarantined)
    }

    @Test fun freshDownReplacesTheEntireOldPointerSetEvenWithoutItsFinalUp() {
        val stream = ModalPointerStream()
        stream.down(3, blocked = false)
        stream.pointerDown(7)
        stream.cancel()
        assertTrue(stream.down(11, blocked = false))
        stream.pointerUp(11)
        assertFalse("Missing releases from an abandoned stream cannot leave phantom fingers", stream.active)
        assertFalse(stream.quarantined)
    }

    @Test fun idleCancellationAndOrdinaryReleaseDoNotPoisonTheNextGesture() {
        val stream = ModalPointerStream()
        stream.cancel()
        assertFalse(stream.active)
        assertFalse(stream.quarantined)

        assertTrue(stream.down(6, blocked = false))
        stream.pointerDown(8)
        stream.pointerUp(8)
        assertTrue(stream.active)
        stream.end()
        assertFalse(stream.active)
        assertFalse(stream.quarantined)
        assertTrue(stream.down(6, blocked = false))
    }
}

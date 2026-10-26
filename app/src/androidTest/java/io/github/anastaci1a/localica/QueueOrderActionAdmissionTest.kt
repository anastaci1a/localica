package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.library.OrderCheckpoint
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEdits
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.ui.BrowseSortDialog
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Native input admission only: controlled callbacks stand in for controller/storage latency.
 * OnDraw witnesses a native traversal, not compositor pixel consumption or elapsed latency.
 */
class QueueOrderActionAdmissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freshOrderContactsAreAdmittedOnTheFirstFrameAfterSuccessOrFailureWithoutSettlingMotion() {
        fun track(id: String) = Track("synthetic:$id", id, id = id, fileName = "$id.mp3", relativePath = "$id.mp3")
        val duplicate = track("duplicate")
        val originalIds = listOf("a1", "b1", "a2", "c1")
        val original = ListeningQueue(id = "admission", name = "Synthetic admission", createdAt = 0,
            entries = listOf(QueueEntry("a1", duplicate), QueueEntry("b1", track("b")),
                QueueEntry("a2", duplicate), QueueEntry("c1", track("c"))),
            currentEntryId = "a2", positionMs = 1234,
            order = SavedOrder(originalIds, TrackSort.Title, false))
        val checkpoint = OrderCheckpoint.capture(originalIds, original.order)
        var queue by mutableStateOf(original)
        data class Pending(val action: String, val complete: (Boolean) -> Unit)
        var pending: Pending? = null
        val dispatches = mutableListOf<String>()
        val checkpoints = mutableListOf<OrderCheckpoint>()
        fun admit(action: String, complete: (Boolean) -> Unit) {
            dispatches += action
            // Keep the first callback owned even if a buggy second admission occurs.
            // Assertions below report it without throwing out of a native input callback.
            if (pending == null) pending = Pending(action, complete)
        }
        compose.setContent {
            LocalicaTheme {
                CompositionLocalProvider(LocalReducedMotion provides false) {
                    BrowseSortDialog(BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, original.id)),
                        BrowseStyle(), false, {}, { _, _ -> }, {},
                        mutableOrder = queue.order, mutableOrderIds = queue.entries.map { it.id },
                        onRandomize = { done -> admit("Randomize") { success ->
                            if (success) queue = QueueEdits.randomize(queue, keepCurrentFirst = true,
                                random = object : Random() { override fun nextBits(bitCount: Int) = 0 })
                            done(success)
                        } },
                        onReverseOrder = { done -> admit("Reverse") { success ->
                            if (success) queue = QueueEdits.reverse(queue)
                            done(if (success) queue.order else null)
                        } },
                        onRevertOrder = { opening, done ->
                            checkpoints += opening
                            admit("Revert") { success ->
                                if (success) queue = QueueEdits.revertOrder(queue, opening)
                                done(success)
                            }
                        })
                }
            }
        }
        val randomize = compose.onNodeWithText("Randomize", substring = false)
        compose.awaitExpectedWindowFocus(randomize)
        compose.waitForIdle() // Opening only; completion admission never waits for settlement.
        val view = (randomize.fetchSemanticsNode().root as ViewRootForTest).view
        compose.mainClock.autoAdvance = false
        try {
            fun frame() {
                compose.mainClock.advanceTimeByFrame()
                // Recomposition/layout is synchronized without advancing a paused clock.
                randomize.fetchSemanticsNode()
                nextNativeDraw(view)
            }
            fun assertQueue(ids: List<String>, order: SavedOrder) = compose.runOnUiThread {
                assertEquals(ids, queue.entries.map { it.id })
                assertEquals(order, queue.order)
                assertEquals(original, queue.copy(entries = original.entries, order = original.order))
                queue.entries.forEach { entry -> assertSame(original.entries.single { it.id == entry.id }, entry) }
                assertSame(queue.entries.single { it.id == "a1" }.track, queue.entries.single { it.id == "a2" }.track)
            }
            var admitted = 0
            fun command(action: String, success: Boolean, expectedIds: List<String>, expectedOrder: SavedOrder) {
                val contactFrame = compose.mainClock.currentTime
                val before = compose.runOnUiThread { queue }
                val target = compose.onNodeWithText(action, substring = false).assertIsEnabled()
                nativeContact(target, view)
                val held = compose.runOnUiThread {
                    assertEquals(++admitted, dispatches.size)
                    assertEquals(action, dispatches.last())
                    requireNotNull(pending).also { assertEquals(action, it.action) }
                }
                assertEquals("Fresh contact cannot advance availability/presence motion", contactFrame, compose.mainClock.currentTime)
                frame() // Present busy=true while this exact callback remains withheld.
                val busyFrame = compose.mainClock.currentTime
                val offered = listOf("Randomize", "Reverse") +
                    if (checkpoint.differsFrom(before.entries.map { it.id })) listOf("Revert") else emptyList()
                for (label in offered) {
                    val disabled = compose.onNodeWithText(label, substring = false).assertIsNotEnabled()
                    nativeContact(disabled, view)
                    compose.runOnUiThread {
                        assertEquals("Busy $label must not dispatch another command", admitted, dispatches.size)
                        assertSame(held, pending)
                        assertSame("No order publication before callback completion", before, queue)
                    }
                }
                assertEquals(busyFrame, compose.mainClock.currentTime)
                compose.runOnUiThread {
                    pending = null
                    held.complete(success)
                    if (!success) assertSame("Failed command leaves exact occurrence revision intact", before, queue)
                    checkpoints.forEach { assertEquals(checkpoint, it) }
                }
                frame() // Exactly one Compose frame; no availability or presence wait follows.
                assertQueue(expectedIds, expectedOrder)
                Log.i("QueueOrderAdmission", "completed=$action success=$success frame=${compose.mainClock.currentTime} dispatches=$admitted")
            }
            val randomized = listOf("a2", "b1", "c1", "a1")
            val reversed = randomized.reversed()
            // Revert's first contact is on its first placed frame, with its emergence
            // spring still unsettled. Each later contact follows the prior completion frame.
            command("Randomize", true, randomized, SavedOrder(randomized))
            command("Revert", false, randomized, SavedOrder(randomized))
            command("Reverse", true, reversed, SavedOrder(reversed))
            command("Randomize", false, reversed, SavedOrder(reversed))
            command("Revert", true, originalIds, original.order)
            compose.onNodeWithText("Revert", substring = false).assertDoesNotExist()
            command("Randomize", true, randomized, SavedOrder(randomized))
            command("Reverse", false, randomized, SavedOrder(randomized))
            command("Revert", true, originalIds, original.order)
            compose.onNodeWithText("Revert", substring = false).assertDoesNotExist()
            command("Randomize", false, originalIds, original.order)
            compose.onNodeWithText("Couldn’t save this order. Try again.").assertExists()
            compose.runOnUiThread {
                assertNull(pending)
                assertEquals(listOf("Randomize", "Revert", "Reverse", "Randomize", "Revert",
                    "Randomize", "Reverse", "Revert", "Randomize"), dispatches)
                assertEquals(3, checkpoints.size)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun nativeContact(node: SemanticsNodeInteraction, expectedView: View) {
        val semantics = node.assertIsDisplayed().fetchSemanticsNode()
        val view = (semantics.root as ViewRootForTest).view
        assertSame("All contacts remain in the same actual popup window", expectedView, view)
        val point = compose.runOnUiThread {
            assertTrue("Native popup remains attached/focused", view.isAttachedToWindow && view.hasWindowFocus())
            val location = IntArray(2).also(view::getLocationOnScreen)
            val visible = android.graphics.Rect()
            assertTrue(view.getGlobalVisibleRect(visible))
            val center = semantics.boundsInRoot.center
            val x = location[0] + center.x
            val y = location[1] + center.y
            assertTrue("Pointer center must be inside actual visible popup: $x,$y / $visible",
                x >= visible.left && x < visible.right && y >= visible.top && y < visible.bottom)
            x to y
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        var held = false
        fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Native pointer injection $action", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN)
            held = true
            send(MotionEvent.ACTION_UP)
            held = false
        } finally {
            if (held) send(MotionEvent.ACTION_CANCEL)
        }
    }

    private fun nextNativeDraw(view: View) {
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        val observer = compose.runOnUiThread {
            assertTrue(view.isAttachedToWindow && view.hasWindowFocus())
            view.viewTreeObserver.also { it.addOnDrawListener(listener); view.invalidate() }
        }
        try {
            assertTrue("Next native draw with Compose motion paused", drawn.await(5, TimeUnit.SECONDS))
        } finally {
            compose.runOnUiThread { if (observer.isAlive) observer.removeOnDrawListener(listener) }
        }
    }
}

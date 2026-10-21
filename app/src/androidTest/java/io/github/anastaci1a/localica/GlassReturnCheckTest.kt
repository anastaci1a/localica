package io.github.anastaci1a.localica

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.anastaci1a.localica.ui.GlassAlertDialog
import io.github.anastaci1a.localica.ui.GlassAnchor
import io.github.anastaci1a.localica.ui.LocalGlassOrigin
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.rememberGlassAnchor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassReturnCheckTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frameQueuedNativeDismissalCanRunWithinTheOriginalAnimationTimeBound() {
        lateinit var anchor: GlassAnchor
        var open by mutableStateOf(true)
        var closeRequested by mutableStateOf(false)
        var nativeDismissalDelivered = false
        val handler = Handler(Looper.getMainLooper())
        val dismiss = Runnable { nativeDismissalDelivered = true; open = false }
        compose.setContent { LocalicaTheme {
            anchor = rememberGlassAnchor()
            LaunchedEffect(closeRequested) {
                if (closeRequested) {
                    // Posting from a controlled frame guarantees the old tight
                    // clock loop cannot deliver this work before it times out.
                    withFrameNanos { }
                    handler.post(dismiss)
                }
            }
            CompositionLocalProvider(LocalGlassOrigin provides anchor) {
                if (open) GlassAlertDialog(onDismissRequest = {}, confirmButton = {},
                    text = { Text("Native return") })
            }
        } }
        compose.onNodeWithText("Native return").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(anchor.hidden)
            assertTrue(anchor.modalPresent)
            assertEquals(1f, anchor.contact.expansion.value, 0f)
        }
        compose.mainClock.autoAdvance = false
        try {
            // Publish the request without allowing a native or animation frame.
            compose.runOnUiThread { Snapshot.withMutableSnapshot { closeRequested = true } }
            val start = compose.mainClock.currentTime
            awaitGlassReturn(compose, anchor) { "open=$open nativeDismissalDelivered=$nativeDismissalDelivered" }
            assertTrue("The frame-queued native dismissal must run", nativeDismissalDelivered)
            assertFalse(anchor.hidden)
            assertFalse(anchor.modalPresent)
            assertFalse("The helper restores a manually controlled clock", compose.mainClock.autoAdvance)
            assertTrue("Readiness cannot borrow extra animation time", compose.mainClock.currentTime - start <= 5000)
        } finally {
            handler.removeCallbacks(dismiss)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun returnNearTheAnimationDeadlineUsesBoundedNativeBatches() {
        lateinit var anchor: GlassAnchor
        var returning by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            anchor = rememberGlassAnchor()
            LaunchedEffect(returning) {
                if (returning) {
                    val start = withFrameNanos { it }
                    while (withFrameNanos { it } - start < 4_800_000_000L) { }
                    anchor.hidden = false
                    anchor.modalPresent = false
                }
            }
        } }
        compose.runOnIdle { anchor.hidden = true; anchor.modalPresent = true }
        compose.mainClock.autoAdvance = false
        var nativeTurns = 0
        val pumpRule = object : ComposeContentTestRule by compose {
            override fun <T> runOnUiThread(action: () -> T): T {
                nativeTurns++
                val before = mainClock.currentTime
                return compose.runOnUiThread(action).also {
                    assertTrue("Native work gets a turn at least every eight frames",
                        mainClock.currentTime - before <= 128)
                }
            }
        }
        try {
            compose.runOnUiThread { Snapshot.withMutableSnapshot { returning = true } }
            val start = compose.mainClock.currentTime
            awaitGlassReturn(pumpRule, anchor) { "returning=$returning nativeTurns=$nativeTurns" }
            val elapsed = compose.mainClock.currentTime - start
            assertTrue("Deliver the near-deadline animation without extra time: $elapsed", elapsed in 4800..4992)
            assertTrue("Batch frames instead of pacing each one through native rendering: $nativeTurns",
                nativeTurns in 2..40)
            assertFalse(anchor.hidden)
            assertFalse(anchor.modalPresent)
            assertFalse(compose.mainClock.autoAdvance)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun unresolvedReturnStillFailsAtTheOriginalAnimationTimeBound() {
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme { anchor = rememberGlassAnchor() } }
        compose.runOnIdle { anchor.hidden = true; anchor.modalPresent = true }
        val start = compose.mainClock.currentTime
        var diagnostics = 0
        var failure: ComposeTimeoutException? = null
        try {
            awaitGlassReturn(compose, anchor) { diagnostics++; "deliberately unresolved return" }
        } catch (timeout: ComposeTimeoutException) {
            failure = timeout
        }
        assertNotNull("A stuck return must still fail", failure)
        val elapsed = compose.mainClock.currentTime - start
        assertTrue("Five-second bound with at most one rounded frame: $elapsed", elapsed in 5000..5032)
        assertEquals("Failure state is captured exactly once", 1, diagnostics)
        assertTrue("Failure restores the caller's automatic clock", compose.mainClock.autoAdvance)
        assertTrue(anchor.hidden)
        assertTrue(anchor.modalPresent)
    }
}

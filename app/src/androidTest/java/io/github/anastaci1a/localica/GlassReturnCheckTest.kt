package io.github.anastaci1a.localica

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
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

    @Test fun unresolvedReturnStillFailsAtTheOriginalAnimationTimeBound() {
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme { anchor = rememberGlassAnchor() } }
        compose.runOnIdle { anchor.hidden = true; anchor.modalPresent = true }
        val start = compose.mainClock.currentTime
        val wallStart = android.os.SystemClock.uptimeMillis()
        var diagnostics = 0
        var failure: ComposeTimeoutException? = null
        try {
            awaitGlassReturn(compose, anchor) { diagnostics++; "deliberately unresolved return" }
        } catch (timeout: ComposeTimeoutException) {
            failure = timeout
        }
        assertNotNull("A stuck return must still fail", failure)
        val elapsed = compose.mainClock.currentTime - start
        val wallElapsed = android.os.SystemClock.uptimeMillis() - wallStart
        assertTrue("Five-second animation bound with at most one rounded frame: $elapsed", elapsed <= 5032)
        assertTrue("A timeout reaches the animation or wall bound: virtual=$elapsed wall=$wallElapsed",
            elapsed >= 5000 || wallElapsed >= 5000)
        assertEquals("Failure state is captured exactly once", 1, diagnostics)
        assertTrue("Failure restores the caller's automatic clock", compose.mainClock.autoAdvance)
        assertTrue(anchor.hidden)
        assertTrue(anchor.modalPresent)
    }
}

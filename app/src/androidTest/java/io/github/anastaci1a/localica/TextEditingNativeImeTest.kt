package io.github.anastaci1a.localica

import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Injected observation bridge contract, NOT real floating-IME compatibility. */
class TextEditingNativeImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun nativeFrames(count: Int = 3) {
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            val clock = Choreographer.getInstance()
            var remaining = count
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) done.countDown() else clock.postFrameCallback(this)
                }
            }
            clock.postFrameCallback(callback)
        }
        assertTrue("Native frame callback remains live without Compose frames", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun nativeOnlyChangesWakeFreshDecisionWithoutRestartingInputAndCancelWithScope() {
        val native = AtomicReference<Boolean?>(false)
        val reads = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun eventSnapshot(): List<String> = synchronized(events) { events.toList() }
        val invalidations = AtomicInteger()
        val session = TextEditingImeSession()
        var hostGeneration = 0L
        var composeVisible by mutableStateOf(true)
        var endCount = 0
        var active by mutableStateOf(true)
        var installed by mutableStateOf(true)
        var revisionSeen = -1L
        var confirmedSeen = false
        var hostReady = false
        try {
        compose.setContent {
            CompositionLocalProvider(LocalTextEditingObservation provides { events += it }) {
                TextEditingFocusHost {
                    val focus = remember { FocusRequester() }
                    BasicTextField("input", {}, Modifier.focusRequester(focus))
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    if (installed) {
                        val view = LocalView.current
                        val host = rememberNativeImeHostReady(view) { invalidations.incrementAndGet(); session.invalidateExposure() }
                        val reader = remember { { _: android.view.View -> reads.incrementAndGet(); native.get() } }
                        val revision = rememberNativeImeRevision(view, active && host.ready && composeVisible, host.generation, reader)
                        SideEffect { hostReady = host.ready; hostGeneration = host.generation }
                        LaunchedEffect(revision, host, composeVisible) {
                            revisionSeen = revision
                            confirmedSeen = confirmedNativeZeroHeightIme(view, reader)
                            if (session.shouldEnd(true, host.ready, composeVisible, 0, 0, 0,
                                    endAtHideStart = true, zeroHeightConfirmed = confirmedSeen)) endCount++
                        }
                    }
                }
            }
        }
        compose.waitUntil(5000) { compose.runOnUiThread { hostReady } && eventSnapshot().any { "phase=create" in it } }
        compose.waitForIdle()
        val starts = eventSnapshot().filter { "phase=start" in it }
        val creates = eventSnapshot().filter { "phase=create" in it }
        assertTrue(starts.isNotEmpty())
        assertFalse(compose.runOnUiThread { confirmedSeen })
        val oldRevision = compose.runOnUiThread { revisionSeen }
        compose.mainClock.autoAdvance = false
        val readsBefore = reads.get()
        native.set(true) // Atomic only: no Compose state, layout or draw invalidation.
        nativeFrames()
        assertTrue("Sampling uses native frames while Compose clock is paused", reads.get() > readsBefore)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(compose.runOnUiThread { revisionSeen > oldRevision && confirmedSeen })
        assertEquals("Observation does not restart the input coroutine", starts, eventSnapshot().filter { "phase=start" in it })
        assertEquals("Observation does not recreate InputConnection", creates, eventSnapshot().filter { "phase=create" in it })

        compose.runOnUiThread { composeVisible = false }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { endCount == 1 }
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertEquals("Fresh native-only exposure arms a subsequent hide exactly once", 1,
            compose.runOnUiThread { endCount })
        compose.runOnUiThread { composeVisible = true }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()

        compose.runOnUiThread { active = false }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val stoppedReads = reads.get()
        native.set(false); nativeFrames()
        assertEquals("Inactive scope removes its exact native callback", stoppedReads, reads.get())
        compose.runOnUiThread { active = true }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertFalse("Activation reevaluates current native state, not earlier true", compose.runOnUiThread { confirmedSeen })

        compose.mainClock.autoAdvance = true
        val previousGeneration = compose.runOnUiThread { hostGeneration }
        val previousInvalidations = invalidations.get()
        compose.runOnUiThread {
            assertFalse(session.shouldEnd(true, true, true, 0, 0, 0, true, zeroHeightConfirmed = true))
        }
        val cover = compose.runOnUiThread {
            android.app.Dialog(compose.activity).apply {
                setContentView(android.widget.TextView(context).apply { text = "Focus cover" })
                show()
            }
        }
        try {
            compose.waitUntil(5000) { invalidations.get() > previousInvalidations && compose.runOnUiThread { !hostReady } }
            compose.runOnUiThread {
                assertFalse("Native-only host loss invalidates prior exposure without dismissal",
                    session.shouldEnd(true, true, false, 0, 0, 0, true, zeroHeightConfirmed = false))
            }
        } finally { compose.runOnUiThread { cover.dismiss() } }
        compose.waitUntil(5000) { compose.runOnUiThread { hostReady && hostGeneration > previousGeneration } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { installed = false }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val disposedReads = reads.get()
        native.set(true); nativeFrames()
        assertEquals("Disposal does not leave a polling callback", disposedReads, reads.get())
        } finally { compose.mainClock.autoAdvance = true }
    }
}

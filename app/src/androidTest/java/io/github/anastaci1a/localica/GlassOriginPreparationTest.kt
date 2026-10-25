package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassOriginPreparationTest {
    @get:Rule val compose = createComposeRule()
    private var open by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private lateinit var preparation: GlassOriginPreparation
    private lateinit var anchor: GlassAnchor
    private var popupWindow: android.view.Window? = null
    private var nativeMounts = 0

    private fun fixture(reduced: Boolean = false) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                preparation = rememberGlassOriginPreparation()
                anchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(24.dp).size(48.dp).glassAnchor(anchor))
                    if (mounted) GlassMenu(open, anchor, { open = false },
                        originPreparation = preparation) {
                        val view = androidx.compose.ui.platform.LocalView.current
                        val window = (view.parent as androidx.compose.ui.window.DialogWindowProvider).window
                        DisposableEffect(window) {
                            popupWindow = window
                            nativeMounts++
                            onDispose { if (popupWindow === window) popupWindow = null }
                        }
                        Box(Modifier.size(180.dp))
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advanceWithNativeFrames(durationMillis: Long) {
        val start = compose.mainClock.currentTime
        // Only unobserved endpoints may batch: keep the full horizon and yield
        // for native drawing within four frames (64 ms), below the six-frame fallback.
        // Frame-sensitive handoffs must continue to use frame() directly.
        while (compose.mainClock.currentTime - start < durationMillis) {
            val remaining = durationMillis - (compose.mainClock.currentTime - start)
            compose.mainClock.advanceTimeBy(minOf(64L, remaining))
            compose.waitForIdle()
        }
    }

    @Test fun preparedOriginReclaimsRetainedReadyWindowBeforeSuspending() = retainedReadyReopen(false)

    @Test fun reducedMotionPreparedOriginReclaimsRetainedReadyWindow() = retainedReadyReopen(true)

    private fun retainedReadyReopen(reduced: Boolean) {
        fixture(reduced)
        try {
            compose.runOnIdle { open = true }
            advanceWithNativeFrames(4000)
            val firstWindow = requireNotNull(popupWindow)
            compose.runOnIdle {
                assertTrue(anchor.hidden && anchor.modalPresent && anchor.shadowInModal)
                assertEquals(1f, preparation.progress, 0f)
                open = false
            }
            // Match the existing native-window handoff diagnostic: reopen at the
            // restored trigger's draw acknowledgement, before the next frame can
            // dispose the prepared window or begin rejoining the origin shape.
            var acknowledged = false
            for (attempt in 0 until 240) {
                frame()
                acknowledged = anchor.modalPresent && !anchor.hidden && anchor.restoredDrawn &&
                    anchor.contact.expansion.value == 0f
                if (acknowledged) break
                assertTrue("Observe the draw handoff before logical disposal", anchor.modalPresent)
            }
            assertTrue("Return must acknowledge the restored trigger", acknowledged)
            compose.runOnIdle { open = true }
            repeat(2) {
                frame()
                compose.runOnIdle {
                    assertSame("Reopen must reuse the ready native window", firstWindow, popupWindow)
                    assertEquals("Preparation cannot allocate a replacement popup", 1, nativeMounts)
                    assertTrue("Reclaim surface before origin preparation can suspend", anchor.hidden)
                    assertTrue("Reclaim modal body on the first reopened frame", anchor.modalPresent && anchor.contact.modal)
                    assertTrue("The same window immediately owns the shadow", anchor.shadowInModal)
                    assertEquals("Retained origin remains prepared", 1f, preparation.progress, 0f)
                }
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle { open = false }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertNull(popupWindow)
                assertFalse(anchor.hidden || anchor.modalPresent || anchor.shadowInModal)
                assertEquals(0f, preparation.progress, 0f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun preparationPrecedesModalOwnershipAndRejoinsAfterReturn() {
        fixture()
        try {
            compose.runOnIdle { open = true }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertTrue(preparation.progress > 0f && preparation.progress < 1f)
                assertFalse(anchor.modalPresent)
                assertFalse(anchor.hidden)
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle { assertTrue(anchor.modalPresent); assertEquals(1f, preparation.progress, 0f) }
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertTrue(anchor.modalPresent)
                assertEquals("Origin stays prepared throughout modal return", 1f, preparation.progress, 0f)
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertFalse(anchor.modalPresent); assertFalse(anchor.hidden)
                assertEquals(0f, preparation.progress, 0f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun cancelledPreparationAndReopenNeverCreateAnEarlyModal() {
        fixture()
        try {
            compose.runOnIdle { open = true }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle {
                assertFalse(anchor.modalPresent)
                assertFalse(anchor.hidden)
                assertFalse(anchor.shadowInModal)
                assertNull("Cancelled origin preparation must not mount a native window", popupWindow)
                assertEquals(0, nativeMounts)
                open = true
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle { assertTrue(anchor.modalPresent); assertEquals(1f, preparation.progress, 0f) }
            compose.runOnIdle { mounted = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(0f, preparation.progress, 0f)
                assertFalse(anchor.modalPresent); assertFalse(anchor.hidden)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reopeningDuringRejoinContinuesFromTheVisibleShape() {
        fixture()
        try {
            compose.runOnIdle { open = true }
            advanceWithNativeFrames(4000)
            compose.runOnIdle { assertTrue(anchor.modalPresent); open = false }
            var rejoining = false
            for (frame in 0 until 240) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    rejoining = !anchor.modalPresent && preparation.progress > .1f && preparation.progress < .9f
                }
                if (rejoining) break
            }
            compose.runOnIdle { assertTrue("Return must expose a reversible rejoin stage", rejoining) }
            var before = 0f
            compose.runOnIdle { before = preparation.progress; open = true }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse("Reopen prepares the moving origin before taking ownership", anchor.modalPresent)
                assertFalse(anchor.hidden)
                assertTrue("Reversal cannot replace the current shape with an endpoint",
                    kotlin.math.abs(preparation.progress - before) < .15f)
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertEquals(1f, preparation.progress, 0f)
                assertTrue(anchor.modalPresent)
                assertTrue(anchor.hidden)
                open = false
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertEquals(0f, preparation.progress, 0f)
                assertFalse(anchor.modalPresent)
                assertFalse(anchor.hidden)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedMotionRetainsStagingEndpointsWithoutAnimatedDelay() {
        fixture(reduced = true)
        try {
            compose.runOnIdle { open = true }
            repeat(5) { frame() }
            compose.runOnIdle { assertEquals(1f, preparation.progress, 0f); assertTrue(anchor.modalPresent) }
            compose.runOnIdle { open = false }
            repeat(10) { frame() }
            compose.runOnIdle { assertEquals(0f, preparation.progress, 0f); assertFalse(anchor.modalPresent) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}

package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertDoesNotExist
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
    private var reportOrigin by mutableStateOf(true)
    private lateinit var preparation: GlassOriginPreparation
    private lateinit var anchor: GlassAnchor
    private var popupWindow: android.view.Window? = null
    private var nativeMounts = 0
    private var preAdmissionFocusLosses = 0

    private fun fixture(reduced: Boolean = false) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                preparation = rememberGlassOriginPreparation()
                anchor = rememberGlassAnchor()
                val sourceView = LocalView.current
                DisposableEffect(sourceView) {
                    val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                        if (!focused && open && !anchor.modalPresent) preAdmissionFocusLosses++
                    }
                    sourceView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                    onDispose { sourceView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
                }
                Box(Modifier.fillMaxSize().background(Color(0xFF143A5F))) {
                    Box(Modifier.padding(24.dp).size(48.dp).then(if (reportOrigin) Modifier.preparedOrigin(preparation, preparation.progress) else Modifier).glassAnchor(anchor))
                    if (mounted) GlassMenu(open, anchor, { open = false },
                        originPreparation = preparation) {
                        val view = androidx.compose.ui.platform.LocalView.current
                        val window = (view.parent as androidx.compose.ui.window.DialogWindowProvider).window
                        DisposableEffect(window) {
                            popupWindow = window
                            nativeMounts++
                            onDispose { if (popupWindow === window) popupWindow = null }
                        }
                        Box(Modifier.size(180.dp).testTag("prepared-menu-content"))
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
        // for native drawing within four frames (64 ms).
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
            // Reverse at the actual restored-trigger acknowledgment; native window reuse
            // must survive while continuous phase momentum decides surface ownership.
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
                    // Reversal retains velocity: it may continue toward the trigger before
                    // turning. Ownership must follow the actual seam, not requested intent.
                    assertEquals("Modal body and shadow change ownership together", anchor.modalPresent, anchor.contact.modal)
                    assertEquals("Modal surface and shadow change ownership together", anchor.modalPresent, anchor.shadowInModal)
                    if (anchor.hidden) assertTrue(anchor.modalPresent)
                    else {
                        val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        assertEquals("Reopened return overlay cannot intercept the restored trigger", flags,
                            firstWindow.attributes.flags and flags)
                    }
                    if (anchor.contact.expansion.value > 0f) assertTrue(anchor.hidden)
                }
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertEquals("Reopened flight reaches its menu endpoint", 1f, anchor.contact.expansion.value, 0f)
                assertEquals(1f, preparation.progress, 0f)
                assertTrue(anchor.hidden && anchor.modalPresent && anchor.contact.modal && anchor.shadowInModal)
                open = false
            }
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
        var beforeCapture: android.graphics.Bitmap? = null
        var prewarmCapture: android.graphics.Bitmap? = null
        try {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val before = requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { beforeCapture = it }
            var invisiblePixels = false
            compose.runOnIdle { open = true }
            var preAdmission = false
            var observedPrewarmWindow = false
            var openingOverlap = false
            for (attempt in 0 until 240) {
                frame()
                compose.runOnIdle {
                    if (!anchor.modalPresent && preparation.progress > 0f) {
                        preAdmission = true
                        assertFalse(anchor.hidden)
                        assertFalse(anchor.shadowInModal)
                        popupWindow?.let { window ->
                            observedPrewarmWindow = true
                            val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            assertEquals("Premeasurement must not intercept input/focus", flags, window.attributes.flags and flags)
                        }
                    }
                    if (anchor.contact.expansion.value > 0f && preparation.progress < 1f) {
                        openingOverlap = true
                        assertTrue("Expanding popup owns the separated left surface", anchor.hidden && anchor.modalPresent)
                        assertTrue("No unsplit pill may be lent to the modal", preparation.progress > .5f)
                    }
                }
                if (!anchor.modalPresent) {
                    compose.onNodeWithTag("prepared-menu-content").assertDoesNotExist()
                    if (observedPrewarmWindow && !invisiblePixels) {
                        val prewarm = requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { prewarmCapture = it }
                        val bounds = anchor.bounds
                        for (iy in 1..3) for (ix in 1..3) {
                            val x = (bounds.left + bounds.width * ix / 4).toInt()
                            val y = (bounds.top + bounds.height * iy / 4).toInt()
                            assertEquals("Premeasurement cannot paint its source surface", before.getPixel(x, y), prewarm.getPixel(x, y))
                        }
                        val directory = nativeEvidenceDirectory("lyrics-premeasurement", instrumentation.targetContext.cacheDir)
                        java.io.File(directory, "native-prewarm.png").outputStream().use {
                            prewarm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        prewarm.recycle()
                        prewarmCapture = null
                        invisiblePixels = true
                    }
                }
                if (preparation.progress == 1f && anchor.contact.expansion.value == 1f) break
            }
            compose.runOnIdle {
                assertTrue("Observe usable origin before modal admission", preAdmission)
                assertTrue("Actually observe a non-intercepting premeasured window", observedPrewarmWindow)
                assertTrue("Check native prewarm source pixels", invisiblePixels)
                assertEquals("Premeasurement cannot steal native focus", 0, preAdmissionFocusLosses)
                assertTrue("Split and expansion must overlap, not settle sequentially", openingOverlap)
                assertEquals(1f, anchor.contact.expansion.value, 0f)
                assertEquals(1f, preparation.progress, 0f)
                open = false
            }
            var closingOverlap = false
            var restored = false
            for (attempt in 0 until 240) {
                frame()
                compose.runOnIdle {
                    if (anchor.contact.expansion.value > 0f && preparation.progress < 1f) {
                        closingOverlap = true
                        assertTrue("Return still owns the left surface until the seam", anchor.modalPresent && anchor.hidden)
                        assertTrue(preparation.progress > .5f)
                    }
                    if (anchor.modalPresent && !anchor.hidden && anchor.restoredDrawn &&
                        anchor.contact.expansion.value == 0f) restored = true
                    if (preparation.progress < LyricsSourcePhase.preparation(LyricsSourcePhase.Handoff)) {
                        assertTrue("Restored draw must precede neck rejoin", restored)
                    }
                }
                if (preparation.progress == 0f && popupWindow == null) break
            }
            compose.runOnIdle {
                assertTrue("Return and split preparation must overlap too", closingOverlap)
                assertTrue("Observe actual restored trigger drawing", restored)
                assertNull(popupWindow)
                assertFalse(anchor.modalPresent); assertFalse(anchor.hidden)
                assertEquals(0f, preparation.progress, 0f)
            }
        } finally { beforeCapture?.recycle(); prewarmCapture?.recycle(); compose.mainClock.autoAdvance = true }
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
                popupWindow?.let { window ->
                    val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    assertEquals("Cancelled premeasurement owns no input or focus", flags, window.attributes.flags and flags)
                }
                open = true
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle { assertTrue(anchor.modalPresent); assertEquals(1f, preparation.progress, 0f) }
            compose.runOnIdle { mounted = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(0f, preparation.progress, 0f)
                assertFalse(anchor.modalPresent); assertFalse(anchor.hidden)
                open = false
                reportOrigin = false
                mounted = true
            }
            frame()
            compose.runOnIdle { open = true }
            advanceWithNativeFrames(400)
            compose.runOnIdle {
                assertNotNull("Unacknowledged origin still premeasures its window", popupWindow)
                assertFalse("No guessed frame count may admit an undrawn origin", anchor.modalPresent)
                assertFalse(anchor.hidden)
                mounted = false
            }
            frame()
            compose.runOnIdle {
                assertNull("Disposal retires unready premeasurement", popupWindow)
                assertEquals(0f, preparation.progress, 0f)
                assertFalse(anchor.modalPresent || anchor.hidden || anchor.shadowInModal || anchor.contact.modal)
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


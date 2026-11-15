package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
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
    private var fontScale by mutableFloatStateOf(1f)
    private val replacementSamples = mutableListOf<Pair<Float, android.view.Window?>>()
    private val popupWindows = mutableSetOf<Int>()
    private var admittedScreenCorners: List<Float>? = null

    private fun fixture(reduced: Boolean = false, prepared: Boolean = true) {
        compose.setContent { EngineTheme(reduced = reduced) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalReducedMotion provides reduced,
                LocalDensity provides Density(density.density, fontScale),
                LocalGlassMorphWorkObserver provides { kind, window, _ ->
                    if (kind == "dialog" && window != null) popupWindows += System.identityHashCode(window)
                },
                LocalGlassSurfaceDrawObserver provides { receipt ->
                    glassDrawProbe?.invoke(receipt)
                    if (receipt is GlassTransitionReceipt) android.util.Log.i("PopupAdmissionOrder",
                        "$receipt nativeWindow ${System.identityHashCode(popupWindow)} state ${anchor.popup?.state}")
                    if (receipt is GlassTransitionReceipt && receipt.event == "WindowReplaced") {
                        replacementSamples += requireNotNull(anchor.popup).let { it.progress.value to it.window }
                    }
                    if (receipt is GlassSurfaceDrawObservation && receipt.windowIdentity in popupWindows &&
                        anchor.popup?.modalPresent == true && receipt.windowIdentity == System.identityHashCode(anchor.popup?.window))
                        admittedScreenCorners = receipt.screenCorners
                }) {
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
                        originPreparation = preparation.takeIf { prepared }) {
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
        android.util.Log.i("OriginSharedPhase", "time=${compose.mainClock.currentTime} origin=${preparation.progress} " +
            "popup=${anchor.contact.expansion.value} modal=${anchor.modalPresent} hidden=${anchor.hidden} " +
            "window=${popupWindow?.let(System::identityHashCode)}")
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

    @Test fun preparedOriginSynchronouslyReclaimsTheSameExpansion() = retainedReadyReopen(false, true)
    @Test fun reducedMotionPreparedOriginSynchronouslyReclaimsTheSameExpansion() = retainedReadyReopen(true, true)

    @Test fun anOrdinaryReplacementCannotBorrowThePreviousWindowsAdmission() = replacement(false)
    @Test fun aPreparedReplacementCannotBorrowThePreviousWindowsAdmission() = replacement(true)

    private fun screenSamples(regions: List<androidx.compose.ui.geometry.Rect>): List<Int> {
        val shot = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try { regions.flatMap { r -> (1..3).flatMap { y -> (1..3).map { x ->
            shot.getPixel((r.left + r.width * x / 4).toInt(), (r.top + r.height * y / 4).toInt())
        } } } } finally { shot.recycle() }
    }

    private fun replacement(prepared: Boolean) {
        fixture(prepared = prepared)
        try {
            compose.runOnIdle { open = true }
            advanceWithNativeFrames(4000)
            val flight = requireNotNull(anchor.popup)
            val first = requireNotNull(popupWindow)
            val gen = flight.gen
            val originalScreen = admittedScreenCorners
            val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            compose.runOnIdle {
                assertTrue("initial window is admitted", flight.admitted(first))
                assertEquals("admitted window enables native input and focus", 0, first.attributes.flags and flags)
                if (prepared) reportOrigin = false; fontScale = 1.01f
            }
            frame()
            compose.runOnIdle {
                assertTrue("observe the replacement event", replacementSamples.isNotEmpty())
                assertTrue("the first observable admission has zero expansion and no old window: $replacementSamples",
                    replacementSamples.all { it.first == 0f && it.second == null })
                assertEquals("the old window loses input synchronously", flags, first.attributes.flags and flags)
                assertSame("same presentation", flight, anchor.popup)
                assertEquals(gen, flight.gen)
                assertNotSame("force a real Compose Dialog replacement", first, popupWindow)
            }
            val regions = if (prepared) listOf(compose.runOnUiThread { anchor.bounds }, requireNotNull(originalScreen).let {
                androidx.compose.ui.geometry.Rect(it[0], it[1], it[4], it[5]) }) else emptyList()
            var heldPixels = emptyList<Int>()
            if (prepared) {
                compose.onNodeWithTag("prepared-menu-content").assertDoesNotExist()
                compose.runOnIdle {
                    assertFalse("no admission before the new origin receipt", flight.modalPresent)
                    assertEquals("the replacement also owns no input", flags, requireNotNull(popupWindow).attributes.flags and flags)
                }
                heldPixels = screenSamples(regions)
                android.util.Log.i("PopupAdmissionPixels", "held ${flight.state} regions $regions pixels ${heldPixels.map(Integer::toHexString)}")
                assertEquals("unadmitted source and old popup footprint present only the host", List(18) { 0xFF143A5F.toInt() }, heldPixels)
                compose.runOnIdle { reportOrigin = true }
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertTrue("replacement eventually admits", flight.admitted(popupWindow))
                assertEquals("readmitted window enables native input and focus", 0, requireNotNull(popupWindow).attributes.flags and flags)
                assertEquals(1f, flight.progress.value, 0f)
                assertTrue(open)
            }
            if (prepared) {
                val admittedPixels = screenSamples(regions)
                android.util.Log.i("PopupAdmissionPixels", "admitted ${flight.state} pixels ${admittedPixels.map(Integer::toHexString)}")
                assertTrue("the same footprint samples detect the admitted popup", admittedPixels.drop(9).zip(heldPixels.drop(9)).any { it.first != it.second })
            }
            compose.runOnIdle { open = false }
            advanceWithNativeFrames(4000)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun retainedReadyReopen(reduced: Boolean, synchronous: Boolean = false) {
        fixture(reduced)
        try {
            compose.runOnIdle { open = true }
            advanceWithNativeFrames(4000)
            val firstWindow = requireNotNull(popupWindow)
            val firstFlight = requireNotNull(anchor.popup)
            val firstPreparation = preparation
            val firstContact = anchor.contact
            compose.runOnIdle {
                assertTrue(anchor.hidden && anchor.modalPresent)
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
            compose.runOnIdle {
                val basis = firstFlight.basis
                val gen = firstFlight.gen
                val value = firstFlight.progress.value
                val velocity = firstFlight.progress.velocity
                open = true
                if (synchronous) {
                    requireNotNull(anchor.reclaimOpening).invoke()
                    assertEquals("T11 is synchronous for prepared origins", io.github.anastaci1a.localica.ui.engine.PopupTakeover.InWindow, firstFlight.takeover)
                }
                assertSame(basis, firstFlight.basis); assertEquals(gen, firstFlight.gen)
                assertSame(firstPreparation, preparation); assertSame(firstContact, anchor.contact)
                assertEquals("$reduced: no expansion reset at Show", value, firstFlight.progress.value, 0f)
                assertEquals("$reduced: no velocity reset at Show", velocity, firstFlight.progress.velocity, 0f)
            }
            repeat(2) {
                frame()
                compose.runOnIdle {
                    assertSame("Reopen must reuse the ready native window", firstWindow, popupWindow)
                    assertSame("Reopen retains its presentation", firstFlight, anchor.popup)
                    assertEquals("Preparation cannot allocate a replacement popup", 1, nativeMounts)
                    // Reversal retains velocity: it may continue toward the trigger before
                    // turning. Ownership must follow the actual seam, not requested intent.
                    assertEquals("Modal body and shadow change ownership together", anchor.modalPresent, anchor.contact.modal)
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
                assertTrue(anchor.hidden && anchor.modalPresent && anchor.contact.modal)
                open = false
            }
            advanceWithNativeFrames(4000)
            compose.runOnIdle {
                assertNull(popupWindow)
                assertFalse(anchor.hidden || anchor.modalPresent)
                assertEquals(0f, preparation.progress, 0f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun preparationPrecedesModalOwnershipAndRejoinsAfterReturn() {
        fixture()
        var beforeCapture: android.graphics.Bitmap? = null
        var prewarmCapture: android.graphics.Bitmap? = null
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = nativeEvidenceDirectory("lyrics-premeasurement", instrumentation.targetContext.cacheDir)
        fun saveCapture(name: String, bitmap: android.graphics.Bitmap?) {
            bitmap ?: return
            java.io.File(directory, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            val initialBounds = compose.runOnUiThread { anchor.bounds }
            assertTrue(initialBounds.width > 0f && initialBounds.height > 0f)
            // Compose idle does not prove SurfaceFlinger has presented this fixture.
            // Wait for the actual closed host's known pixels BEFORE starting motion;
            // never compare premeasurement with a stale previous-window screenshot.
            compose.waitUntil(5000) {
                beforeCapture?.recycle()
                beforeCapture = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val shot = requireNotNull(beforeCapture)
                (1..3).all { iy -> (1..3).all { ix ->
                    shot.getPixel((initialBounds.left + initialBounds.width * ix / 4).toInt(),
                        (initialBounds.top + initialBounds.height * iy / 4).toInt()) == 0xFF143A5F.toInt()
                } }
            }
            val before = requireNotNull(beforeCapture)
            saveCapture("native-before.png", before)
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
                        assertFalse(anchor.modalPresent)
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
                        saveCapture("native-prewarm.png", prewarm)
                        val bounds = anchor.bounds
                        for (iy in 1..3) for (ix in 1..3) {
                            val x = (bounds.left + bounds.width * ix / 4).toInt()
                            val y = (bounds.top + bounds.height * iy / 4).toInt()
                            assertEquals("Premeasurement cannot paint its source surface", before.getPixel(x, y), prewarm.getPixel(x, y))
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
                    if (preparation.progress < .68359375f /* independent smoothstep(.25 / .4) handoff reference */) {
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
        } finally {
            saveCapture("native-before.png", beforeCapture)
            saveCapture("native-prewarm.png", prewarmCapture)
            beforeCapture?.recycle(); prewarmCapture?.recycle()
            compose.mainClock.autoAdvance = true
        }
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
                assertFalse(anchor.modalPresent)
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
                assertFalse(anchor.modalPresent || anchor.hidden || anchor.contact.modal)
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
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Reversal preserves the presented origin at the intent", before, preparation.progress, .0001f)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse("Reopen prepares the moving origin before taking ownership", anchor.modalPresent)
                assertFalse(anchor.hidden)
                // C1 preserves velocity at the intent; a finite frame may continue
                // toward the source before turning. The shared Engine contracts
                // check the exact phase and bound-edge kinematics on both APIs.
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


package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMorphTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelledShapeClockRetainsItsBodyUntilANewOwnerFinishesNormally() {
        val state = GlassBodyState()
        var present by mutableStateOf(true)
        var contact by mutableFloatStateOf(1f)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            if (present) rememberGlassBody({ 0f }, contact = { contact }, state = state)
        } }
        try {
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { present = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val interrupted = state.output.value
            assertNotEquals("Disposing the clock cannot reset shared deformation", GlassShape(), interrupted)
            assertFalse("The cancelled clock was still converging", state.momentum.settled)
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("Cancellation retains displacement and does not complete the old target", interrupted, state.output.value)
            assertEquals(interrupted, state.momentum.shape)
            compose.runOnIdle { contact = 0f; present = true }
            compose.mainClock.advanceTimeBy(5000)
            compose.waitForIdle()
            assertTrue(state.momentum.settled)
            assertEquals("Normal completion publishes exact rest", GlassShape(), state.output.value)
            assertEquals(GlassShape(), state.momentum.shape)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun popupChildShadowTravelsAndFadesWithItsOwner() {
        var moved by mutableStateOf(false)
        var visible by mutableStateOf(true)
        lateinit var popupView: android.view.View
        val outsideShadows = GlassShadowGroup()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides
                io.github.anastaci1a.localica.navigation.GlassAppearance(shadowStrength = 1f),
                LocalSurfaceShadows provides outsideShadows, LocalBottomShadows provides outsideShadows) {
                GlassMorphPopup(true, rememberGlassAnchor(), {}, fitContent = false) {
                    popupView = androidx.compose.ui.platform.LocalView.current
                    Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White).testTag("moving-shadow-scene")) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            translationX = if (moved) 140.dp.toPx() else 0f; alpha = if (visible) 1f else 0f
                        }) {
                            LiquidGlassSurface(Modifier.offset(30.dp, 40.dp).size(80.dp, 40.dp)) {}
                        }
                        // Outside the transformed/alpha-controlled child: proves
                        // the requested state reached the same captured frame.
                        PresentationMarker(Modifier.offset(16.dp, 16.dp)) { if (!visible) 2 else if (moved) 1 else 0 }
                    }
                }
            }
        } }
        fun darkness(left: Int): Float {
            val node = compose.onNodeWithTag("moving-shadow-scene")
            val stage = if (!visible) 2 else if (moved) 1 else 0
            // Stay inside the rounded popup clip, away from the shadow sample regions.
            val marker = (16 * compose.density.density).toInt() + 2
            val pixels = capturePresentedPopupPixels(compose, node, stage,
                androidx.compose.ui.unit.IntOffset(marker, marker)) { popupView }
            val density = compose.density.density
            assertTrue("Sample region must stay inside the popup", (left + 50) * density < pixels.width)
            var dark = 0f; var count = 0
            for (y in (82 * density).toInt()..(90 * density).toInt())
                for (x in (left * density).toInt()..((left + 50) * density).toInt()) {
                    dark += 1f - pixels[x, y].red; count++
                }
            return (dark / count).also {
                android.util.Log.i("GlassMorphProbe", "shadow stage=$stage moved=$moved visible=$visible left=$left darkness=$it")
            }
        }
        val original = darkness(45)
        assertTrue("Modal descendants cannot join a shadow group in another window", outsideShadows.entries.isEmpty())
        assertTrue("Control casts a visible shadow", original > .01f)
        compose.runOnIdle { moved = true }
        assertTrue("No shadow remains at the old location", darkness(45) < .002f)
        assertTrue("Shadow follows the translated control", darkness(185) > .01f)
        compose.runOnIdle { visible = false }
        assertTrue("Parent alpha also removes the child's shadow", darkness(185) < .002f)
    }

    @Test fun dismissalReleasesInputPromptlyAndRespectsRefusedRequests() {
        var open by mutableStateOf(false)
        var acceptDismiss by mutableStateOf(true)
        var refused = 0
        var presses = 0
        lateinit var anchor: GlassAnchor
        lateinit var modalWindow: android.view.Window
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            anchor = rememberGlassAnchor()
            Box(Modifier.align(Alignment.BottomEnd).padding(24.dp).size(CompactControlSize)
                .glassAnchor(anchor).testTag("input-trigger"))
            Box(Modifier.align(Alignment.TopStart).padding(24.dp).size(80.dp)
                .testTag("immediate-action").clickable { presses++ }) { Text("Next") }
            GlassMorphPopup(open, anchor, { if (acceptDismiss) open = false else refused++ }) {
                val view = androidx.compose.ui.platform.LocalView.current
                SideEffect { modalWindow = (view.parent as androidx.compose.ui.window.DialogWindowProvider).window }
                Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Dismiss me") }
            }
        } } }
        val inset = anchor.bounds.top - compose.onNodeWithTag("input-trigger").fetchSemanticsNode().boundsInRoot.top
        val target = compose.onNodeWithTag("immediate-action").fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        assertEquals("An invisible native dim layer must not occlude touches during return", 0,
            modalWindow.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        compose.mainClock.autoAdvance = false
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.click(target.x.toInt(), (target.y + inset).toInt())
        compose.waitUntil(3000) { !open }
        // The dismissal event requests the handoff before a Compose frame. Android
        // still needs a traversal to apply that window change to hit testing.
        assertTrue(modalWindow.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        compose.mainClock.advanceTimeBy(32)
        device.click(target.x.toInt(), (target.y + inset).toInt())
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(3000) { presses == 1 }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { acceptDismiss = false; open = true }
        compose.waitForIdle()
        device.click(target.x.toInt(), (target.y + inset).toInt())
        compose.waitUntil(3000) { refused == 1 }
        compose.waitForIdle()
        assertTrue(open)
        assertEquals("A refused dismissal cannot leave a busy editor untouchable", 0,
            modalWindow.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    @Test fun returnedTriggerKeepsTheSameLowDampingRebound() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides
                io.github.anastaci1a.localica.navigation.GlassAppearance(shapeDamping = 2f)) {
                Box(Modifier.fillMaxSize()) {
                    anchor = rememberGlassAnchor()
                    LiquidGlassSurface(Modifier.align(Alignment.BottomEnd).padding(24.dp)
                        .size(CompactControlSize).glassAnchor(anchor)) { }
                    GlassMorphPopup(open, anchor, { open = false }) {
                        Box(Modifier.fillMaxWidth().height(220.dp)) { Text("Persistent surface") }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = true }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { open = false }
        var previous = anchor.contact.body.value
        var returned = false
        repeat(180) {
            if (!returned) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val current = anchor.contact.body.value
                if (!anchor.modalPresent) {
                    assertTrue("The body must not reset its depth at the window handoff",
                        kotlin.math.abs(current.depth - previous.depth) < .03f)
                    assertTrue("The body must not reset its strain at the window handoff",
                        kotlin.math.abs(current.strain - previous.strain) < .03f)
                    assertFalse("Low-damping return continues settling in the trigger", anchor.contact.state.momentum.settled)
                    returned = true
                }
                previous = current
            }
        }
        assertTrue("Closing must complete without waiting for decorative settling", returned)
        compose.mainClock.advanceTimeBy(12000)
        compose.waitForIdle()
        assertTrue("The shared clock eventually sleeps", anchor.contact.state.momentum.settled)
        assertEquals(0f, anchor.contact.body.value.depth, .001f)
        assertEquals(0f, anchor.contact.body.value.strain, .001f)
    }

    @Test fun recordedTriggersReturnWhileStationaryAndClosingWindowsReleaseInput() {
        var open by mutableStateOf(false)
        var presses = 0
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            val recorded = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize().recordFrostedBackdrop(recorded)) {
                anchor = rememberGlassAnchor()
                Canvas(Modifier.fillMaxSize()) { drawRect(Color.White) }
                // The assertion samples actual screen pixels; system navigation
                // scrims must not cover this synthetic trigger on edge-to-edge hosts.
                Canvas(Modifier.align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(24.dp).size(104.dp, 64.dp)
                    .glassAnchor(anchor).testTag("stationary-trigger")) { drawRect(Color.Red) }
                Box(Modifier.align(Alignment.TopStart).padding(24.dp).size(80.dp).testTag("next-action").clickable { presses++ }) {
                    Text("Next action")
                }
                GlassMorphPopup(open, anchor, { open = false }) { Box(Modifier.fillMaxWidth().height(240.dp)) { Text("Options") } }
            }
        } }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun awaitRedTrigger(stage: String) {
            var last: android.graphics.Bitmap? = null
            var evidence = "No screenshot sampled"
            try {
                compose.waitUntil(5000) {
                    last?.recycle()
                    val screenshot = requireNotNull(automation.takeScreenshot()).also { last = it }
                    val bounds = anchor.bounds
                    val x = bounds.center.x.toInt(); val y = bounds.center.y.toInt()
                    val pixel = screenshot.getPixel(x, y)
                    evidence = "stage=$stage bounds=$bounds sample=($x,$y) pixel=${Integer.toHexString(pixel)} " +
                        "screen=${screenshot.width}x${screenshot.height} hidden=${anchor.hidden} " +
                        "restoredDrawn=${anchor.restoredDrawn} modalPresent=${anchor.modalPresent}"
                    android.graphics.Color.red(pixel) > 240 && android.graphics.Color.green(pixel) < 20
                }
            } catch (failure: Throwable) {
                android.util.Log.e("GlassMorphProbe", evidence)
                last?.let { screenshot ->
                    // Keep evidence in both the device output and captured logcat;
                    // no CI workflow change is needed to retrieve this synthetic scene.
                    val bytes = java.io.ByteArrayOutputStream().also {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
                    val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "trigger-$stage.png")
                    runCatching { file.writeBytes(bytes) }
                    if (bytes.size <= 256 * 1024) {
                        val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(3000)
                        chunks.forEachIndexed { index, chunk -> android.util.Log.e("GlassMorphProbe", "PNG $stage ${index + 1}/${chunks.size} $chunk") }
                    }
                }
                throw failure
            } finally { last?.recycle() }
        }
        awaitRedTrigger("before-open")
        val windowInset = anchor.bounds.top - compose.onNodeWithTag("stationary-trigger").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-morph-surface").assertExists()
        val target = compose.onNodeWithTag("next-action").fetchSemanticsNode().boundsInRoot.center
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.click(target.x.toInt(), (target.y + windowInset).toInt())
        compose.waitUntil(5000) { presses == 1 }
        // Geometry now settles to subpixel precision before the window handoff.
        compose.mainClock.advanceTimeUntil(2000) { !anchor.modalPresent }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
        // Screen capture does not invalidate the Compose tree. A semantics-only assertion
        // would miss a trigger that exists but stays absent from its recorded parent.
        awaitRedTrigger("after-return")
        compose.mainClock.autoAdvance = true
    }

    @Test fun oneSurfaceExpandsFromTheTriggerAndReturnsBeforeRestoringIt() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            anchor = rememberGlassAnchor()
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFFB4D8C8)) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                LiquidGlassSurface(Modifier.align(Alignment.BottomEnd).padding(24.dp).size(CompactControlSize)
                    .glassAnchor(anchor).clickable { open = true }.testTag("trigger")) { }
                GlassMorphPopup(open, anchor, { open = false }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Create playlist")
                        Spacer(Modifier.height(80.dp))
                        Text("Import playlist")
                    }
                }
            }
        } } }
        assertNotNull("Direct surfaces must carry their backing into the returning morph", anchor.source)
        val start = compose.onNodeWithTag("trigger").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            val openingStart = compose.mainClock.currentTime
            compose.runOnIdle { open = true }
            // Native Dialog registration needs Android frames as well as Compose frames.
            // Yield between frames without moving the original 176 ms sampling deadline.
            try {
                compose.waitUntil(5000) {
                    if (compose.mainClock.currentTime - openingStart < 176) compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().size == 1
                }
            } finally {
                android.util.Log.i("GlassMorphProbe", "opening registration elapsed=${compose.mainClock.currentTime - openingStart} progress=${anchor.contact.expansion.value} modal=${anchor.modalPresent}")
            }
            val registrationElapsed = compose.mainClock.currentTime - openingStart
            assertTrue("Registration must not advance beyond the intermediate sample", registrationElapsed <= 176)
            if (registrationElapsed < 176) compose.mainClock.advanceTimeBy(176 - registrationElapsed)
            compose.waitForIdle()
            val middle = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
            assertTrue("The original trigger is hidden throughout the morph", anchor.hidden)
            assertTrue("The original circle grows into the menu", middle.width > start.width)
            compose.mainClock.advanceTimeBy(1000)
            val expanded = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
            assertTrue("Opening passes through an intermediate width", expanded.width > middle.width)
            compose.onNodeWithText("Create playlist").assertIsDisplayed()
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(176)
            assertTrue("Do not expose a second surface during the return", anchor.hidden)
            compose.onNodeWithTag("glass-morph-surface").assertExists()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
            assertFalse(anchor.hidden)
            compose.onNodeWithTag("trigger").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun unreadyAndDisposedWindowsCannotSkipNativePreparation() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var popupWindow: android.view.Window? = null
        data class Mount(val window: android.view.Window, val clockMs: Long,
            val hidden: Boolean, val shadowOwned: Boolean, val expansion: Float)
        val mounts = mutableListOf<Mount>()
        val disposed = mutableListOf<android.view.Window>()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            Box(Modifier.fillMaxSize()) {
                anchor = rememberGlassAnchor()
                Box(Modifier.align(Alignment.BottomEnd).padding(24.dp).size(CompactControlSize)
                    .glassAnchor(anchor).testTag("preparation-trigger"))
                GlassMorphPopup(open, anchor, { open = false }) {
                    val view = androidx.compose.ui.platform.LocalView.current
                    val window = (view.parent as androidx.compose.ui.window.DialogWindowProvider).window
                    DisposableEffect(window) {
                        popupWindow = window
                        mounts += Mount(window, compose.mainClock.currentTime,
                            anchor.hidden, anchor.shadowInModal, anchor.contact.expansion.value)
                        onDispose {
                            disposed += window
                            if (popupWindow === window) popupWindow = null
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Measured menu") }
                }
            }
        } } }
        compose.mainClock.autoAdvance = false
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        fun awaitWindow(): android.view.Window {
            repeat(12) {
                popupWindow?.let { return it }
                frame()
            }
            error("The native dialog did not mount within twelve controlled frames")
        }
        fun assertUnready(reason: String) {
            assertFalse("$reason: the trigger stays visible during preparation", anchor.hidden)
            assertFalse("$reason: the trigger still owns its shadow", anchor.shadowInModal)
            assertEquals("$reason: expansion cannot precede preparation", 0f, anchor.contact.expansion.value, 0f)
        }
        fun awaitReady(window: android.view.Window) {
            repeat(12) {
                if (!anchor.hidden) frame()
            }
            assertSame("Preparation belongs to this native window", window, popupWindow)
            assertTrue("The prepared window hides its trigger", anchor.hidden)
            assertTrue("The prepared window takes its shadow", anchor.shadowInModal)
            assertEquals("Reduced motion expands only after preparation", 1f, anchor.contact.expansion.value, 0f)
        }
        try {
            compose.runOnIdle { open = true }
            val firstWindow = awaitWindow()
            assertUnready("First native mount")
            assertEquals(1, mounts.size)
            assertSame(firstWindow, mounts.single().window)
            assertFalse("A native window must not mount already owning its trigger", mounts.single().hidden)
            assertFalse(mounts.single().shadowOwned)
            assertEquals(0f, mounts.single().expansion, 0f)

            // Cancel after native registration but before either measured frame.
            // The actual trigger remains present throughout this unready lifetime.
            compose.runOnIdle { open = false }
            frame()
            assertUnready("Cancelled unready window")
            assertSame(firstWindow, popupWindow)
            compose.runOnIdle { open = true }
            frame()
            assertSame("Unready cancellation/reopen retains the existing dialog", firstWindow, popupWindow)
            assertUnready("First unready-reopen frame")
            awaitReady(firstWindow)
            assertTrue("First readiness follows the native mount by at least two frames",
                compose.mainClock.currentTime - mounts.single().clockMs >= 32)

            compose.runOnIdle { open = false }
            repeat(12) { if (popupWindow != null) frame() }
            assertNull("The closed native window must actually dispose", popupWindow)
            assertEquals(listOf(firstWindow), disposed)
            assertFalse(anchor.modalPresent)
            assertFalse(anchor.hidden)
            assertFalse(anchor.shadowInModal)

            compose.runOnIdle { open = true }
            val replacement = awaitWindow()
            assertNotSame("A fully disposed native window cannot be reused", firstWindow, replacement)
            assertEquals(2, mounts.size)
            assertSame(replacement, mounts.last().window)
            assertUnready("Replacement native mount")
            assertFalse("Disposed readiness cannot hide a newly mounted trigger", mounts.last().hidden)
            assertFalse(mounts.last().shadowOwned)
            assertEquals(0f, mounts.last().expansion, 0f)
            frame()
            assertUnready("First replacement preparation frame")
            awaitReady(replacement)
            assertTrue("Replacement readiness follows its own two measured frames",
                compose.mainClock.currentTime - mounts.last().clockMs >= 32)
        } finally {
            compose.runOnIdle { open = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun interruptedMorphKeepsItsSurfaceAndRestoresTheTriggerOnce() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            anchor = rememberGlassAnchor()
            Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(CompactControlSize).glassAnchor(anchor).testTag("reversal-trigger"))
            GlassMorphPopup(open, anchor, { open = false }) {
                Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Reversible menu") }
            }
        } } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = true }
        compose.mainClock.advanceTimeBy(176)
        compose.onNodeWithTag("glass-morph-surface").assertExists()
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(96)
        assertTrue(anchor.hidden)
        compose.runOnIdle { open = true }
        compose.mainClock.advanceTimeBy(96)
        assertTrue("Reopening cannot reveal a duplicate trigger", anchor.hidden)
        compose.onNodeWithTag("glass-morph-surface").assertExists()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Reversible menu").assertIsDisplayed()
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
        assertFalse(anchor.hidden)
    }

    @Test fun popupSamplesTheCorrectScreenCoordinatesAndNestedControlsSampleItsMaterial() {
        var open by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        lateinit var material: FrostedBackdrop
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            Box(Modifier.fillMaxSize()) {
                val anchor = rememberGlassAnchor()
                source = rememberFrostedBackdrop(Unit, dark = true)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Red, size = Size(size.width / 2, size.height))
                    drawRect(Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                }
                val list = rememberFrostedBackdrop(Unit, dark = true)
                Canvas(Modifier.padding(top = 320.dp).fillMaxWidth().height(100.dp).recordFrostedBackdrop(list)) { drawRect(Color.Green) }
                CompositionLocalProvider(LocalFrostedBackdrop provides list, LocalModalBackdrop provides source) {
                    SideEffect { anchor.source = list }
                    Box(Modifier.align(Alignment.BottomEnd).size(CompactControlSize).glassAnchor(anchor))
                    GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Search) {
                        material = LocalFrostedBackdrop.current!!
                        Box(Modifier.fillMaxWidth().height(200.dp)) {
                            LiquidGlassSurface(Modifier.align(Alignment.Center).size(CompactControlSize).testTag("nested-lens")) { }
                        }
                    }
                }
            }
        } } }
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        // Unused backdrops intentionally skip readback; opening the popup acquires
        // its sampling lease. Wait for the older-device pixels after that point.
        if (android.os.Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        assertNotSame("Nested controls cannot resample the original scene", source, material)
        // Compose cannot capture Dialog roots before API 28. Read the real screen
        // using the material's global origin, also exercising cross-window alignment.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        // Compose idleness precedes the dialog's first SurfaceFlinger frame on some
        // software renderers. Require its darkened material before testing the blur.
        compose.waitUntil(10000) {
            val shot = automation.takeScreenshot()
            try {
                val sample = shot.asImageBitmap().toPixelMap()[(material.origin.x + material.size.width / 4).toInt(),
                    (material.origin.y + material.size.height / 3).toInt()]
                sample.red < .85f && sample.red > sample.blue + .2f
            } finally { shot.recycle() }
        }
        val screenshot = automation.takeScreenshot()
        val pixels = screenshot.asImageBitmap().toPixelMap()
        val left = pixels[(material.origin.x + material.size.width / 4).toInt(), (material.origin.y + material.size.height / 3).toInt()]
        val right = pixels[(material.origin.x + material.size.width * 3 / 4).toInt(), (material.origin.y + material.size.height / 3).toInt()]
        assertTrue("Correctly positioned red backdrop: $left", left.red > left.blue + .2f)
        assertTrue("Correctly positioned blue backdrop: $right", right.blue > right.red + .2f)
        val nearBoundary = pixels[(material.origin.x + material.size.width / 2 - 4).toInt(), (material.origin.y + material.size.height / 3).toInt()]
        assertTrue("Scatter pixels above the list's recorded bounds: $nearBoundary", nearBoundary.blue > .03f)
        compose.onNodeWithTag("nested-lens").assertIsDisplayed()
    }
}

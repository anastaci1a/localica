package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

@OptIn(ExperimentalLayoutApi::class)
class SearchImeMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val name = TestName()
    private var visible by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private var keyboardAllowed by mutableStateOf(true)
    private var text by mutableStateOf("")
    private var extraContentHeight by mutableIntStateOf(0)
    private lateinit var anchor: GlassAnchor
    private var popupView: android.view.View? = null
    private var keyboardSettled = true
    private var motionSpeed = 1f
    private val samples = mutableListOf<SearchMorphSample>()
    private val keyboardSetup = mutableListOf<String>()
    private val motionTimeline = mutableListOf<String>()

    private val controls = SearchImeRetirementControls()
    private val nativeProbes = mutableListOf<SearchImeRetirementProbe>()

    // Always call on the UI thread; use the same clock as native/input callbacks.
    private fun phase(label: String) {
        motionTimeline += "${SystemClock.uptimeMillis()} fixturePhase [$label] visible=$visible " +
            "mounted=$mounted keyboardAllowed=$keyboardAllowed queryEmpty=${text.isBlank()}"
    }

    @Before fun prepareWindow() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (controls.enabled) motionTimeline += "${SystemClock.uptimeMillis()} controls ${controls.description()}"
            if (controls.nativeTrace) nativeProbes += SearchImeRetirementProbe(it.window.decorView, it.window, "activity") { event ->
                motionTimeline += event
            }
        }
    }

    @After fun recordPlacedFrames() {
        if (controls.nativeTrace) compose.runOnUiThread {
            nativeProbes.forEach { probe ->
                runCatching { probe.close() }.onFailure { motionTimeline += "probeCleanupFailure ${it.javaClass.name}: ${it.message}" }
            }
            nativeProbes.clear()
        }
        val additional = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
        val root = additional?.let { java.io.File(it) } ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        val directory = java.io.File(root, "search-ime-motion").apply { mkdirs() }
        java.io.File(directory, "${name.methodName}.txt").writeText(compose.runOnUiThread { samples.toList() }.joinToString("\n"))
        java.io.File(directory, "${name.methodName}-keyboard-setup.txt").writeText(keyboardSetup.joinToString("\n"))
        java.io.File(directory, "${name.methodName}-timeline.txt").writeText(
            compose.runOnUiThread { motionTimeline.toList() }.joinToString("\n"))
    }

    private fun fixture(showKeyboard: Boolean, reduced: Boolean = false, initialQuery: String = "", deferKeyboard: Boolean = false, initialExtraContentHeight: Int = 0) {
        text = initialQuery
        extraContentHeight = initialExtraContentHeight
        keyboardAllowed = !deferKeyboard
        if (controls.enabled) compose.runOnUiThread { phase("fixture-install showKeyboard=$showKeyboard reduced=$reduced deferKeyboard=$deferKeyboard") }
        compose.setContent { LocalicaTheme {
            val speed = LocalMotionSpeed.current
            SideEffect { motionSpeed = speed }
            val observeTextEditing: (String) -> Unit = { event ->
                val native = popupView?.let { view ->
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                    "view=${System.identityHashCode(view)} attached=${view.isAttachedToWindow} " +
                        "focused=${view.hasWindowFocus()} nativeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                        "nativeHeight=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}"
                }
                motionTimeline += "${SystemClock.uptimeMillis()} textEditing $event nativeObservation=$native"
            }
            CompositionLocalProvider(LocalTextEditingObservation provides observeTextEditing.takeIf { controls.inputObservation },
                LocalReducedMotion provides reduced,
                LocalSearchMorphObserver provides {
                    samples += it
                    motionTimeline += "${SystemClock.uptimeMillis()} $it"
                }) {
                anchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    ListSearchPill("Search fixture", { if (controls.enabled) phase("pill-open-request"); visible = true }, Modifier.align(Alignment.TopCenter)
                        .padding(top = 80.dp, start = 20.dp, end = 20.dp), anchor)
                }
                if (mounted) ListSearchWindow(visible, anchor, text.isNotBlank(), { if (controls.enabled) phase("popup-dismiss-callback"); visible = false }) {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    val focusedWindow = LocalWindowInfo.current.isWindowFocused
                    val ready = LocalSearchOpeningReady.current
                    val view = LocalView.current
                    if (controls.nativeTrace) DisposableEffect(view) {
                        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                        val probe = SearchImeRetirementProbe(view, requireNotNull(window), "popup") { event ->
                            motionTimeline += event
                        }.also { nativeProbes += it }
                        onDispose { probe.close() }
                    }
                    val density = LocalDensity.current
                    val ime = WindowInsets.ime.getBottom(density)
                    val source = WindowInsets.imeAnimationSource.getBottom(density)
                    val target = WindowInsets.imeAnimationTarget.getBottom(density)
                    SideEffect { popupView = view; keyboardSettled = ime == target && source == target }
                    LaunchedEffect(visible, focusedWindow, ready) {
                        if (controls.enabled) phase("focus-effect windowFocused=$focusedWindow")
                        if (visible && focusedWindow) {
                            withFrameNanos { }
                            if (controls.enabled) phase("opening-ready-request")
                            ready?.invoke(true)
                            if (showKeyboard) {
                                // The delayed fixture releases input only after observing
                                // the fallback endpoint, independent of CI wall-clock load.
                                snapshotFlow { keyboardAllowed }.first { it }
                                if (controls.enabled) phase("request-focus")
                                focus.requestFocus()
                                if (controls.enabled) phase("request-keyboard-show")
                                keyboard?.show()
                                if (controls.enabled) phase("request-keyboard-show-returned")
                            } else { if (controls.enabled) phase("request-keyboard-hide-no-input"); keyboard?.hide() }
                        }
                    }
                    CompositionLocalProvider(LocalEmptySearchBack provides ({ if (controls.enabled) phase("empty-search-back-callback"); visible = false }).takeIf { text.isBlank() }) {
                        Column {
                            GlassSearchField(text, { text = it }, "Search fixture", fieldModifier = Modifier.focusRequester(focus))
                            Spacer(Modifier.height(extraContentHeight.dp))
                        }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
    }

    private fun advanceUntil(
        wallTimeoutMillis: Long = 5000,
        virtualTimeoutMillis: Long = Long.MAX_VALUE,
        predicate: () -> Boolean,
    ) {
        val start = compose.mainClock.currentTime
        val wallStart = SystemClock.uptimeMillis()
        try {
            compose.waitUntil(wallTimeoutMillis) {
                compose.mainClock.advanceTimeBy(32)
                val satisfied = compose.runOnUiThread(predicate)
                if (!satisfied && compose.mainClock.currentTime - start >= virtualTimeoutMillis)
                    throw ComposeTimeoutException("Animation exceeded its ${virtualTimeoutMillis}ms virtual budget")
                satisfied
            }
        } catch (timeout: ComposeTimeoutException) {
            val state = compose.runOnUiThread {
                "visible=$visible modal=${anchor.modalPresent} progress=${anchor.contact.expansion.value} " +
                    "running=${anchor.contact.expansion.isRunning} keyboardSettled=$keyboardSettled " +
                    "placedFrames=${samples.size} last=${samples.lastOrNull()}"
            }
            throw AssertionError("Search readiness timed out after ${SystemClock.uptimeMillis() - wallStart}ms wall " +
                "and ${compose.mainClock.currentTime - start}ms virtual " +
                "(budgets: wall=$wallTimeoutMillis, virtual=$virtualTimeoutMillis): $state", timeout)
        }
    }

    private fun opened() {
        if (controls.enabled) compose.runOnUiThread { phase("opened-start") }
        // Shape starts independently of native input. Give each independent spring
        // its calculated frame budget, not a longer undiagnosed overall wait.
        advanceUntil { anchor.contact.expansion.isRunning || anchor.contact.expansion.value == 1f }
        val shapeMillis = compose.runOnUiThread {
            val expansion = anchor.contact.expansion
            springDuration(expansion.value, 1f, expansion.velocity)
        }
        advanceUntil(wallTimeoutMillis = 20000, virtualTimeoutMillis = shapeMillis + 256L) {
            anchor.contact.expansion.value == 1f && !anchor.contact.expansion.isRunning
        }
        // Hardware/refused IMEs use the bounded real-time fallback. Do not charge
        // those native frames to the independent position spring's virtual budget.
        advanceUntil { samples.lastOrNull()?.let { it.placementBottom != null || it.flight == null } == true }
        val positionMillis = compose.runOnUiThread {
            val sample = samples.last()
            // A full screen of travel bounds the start distance. The critical
            // spring retains velocity on retarget, with one extra frame allowance.
            springDuration(0f, maxOf(sample.destination.bottom, sample.bounds.bottom), pixels = true)
        }
        var placed: SearchMorphSample? = null
        advanceUntil(wallTimeoutMillis = 20000, virtualTimeoutMillis = positionMillis + 256L) {
            samples.lastOrNull()?.takeIf { sample ->
                sample.opening && listOf(
                    sample.bounds.left - sample.destination.left,
                    sample.bounds.top - sample.destination.top,
                    sample.bounds.right - sample.destination.right,
                    sample.bounds.bottom - sample.destination.bottom,
                ).all { kotlin.math.abs(it) <= 2f }
            }?.also { placed = it } != null
        }
        // Native insets keep advancing while the Compose clock is paused. Assert
        // the exact placed frame observed by the UI-thread predicate, not a later
        // frame whose new keyboard destination may legitimately be in flight.
        requireNotNull(placed).let {
            assertRect("Opening reaches its placed endpoint", it.destination, it.bounds)
        }
    }

    private fun springDuration(from: Float, to: Float, velocity: Float = 0f, pixels: Boolean = false): Long =
        (TargetBasedAnimation(if (pixels) searchBottomSpring(motionSpeed) else glassMorphSpring(motionSpeed), Float.VectorConverter, from, to,
            AnimationVector1D(velocity)).durationNanos + 999_999L) / 1_000_000L

    private fun closed() = advanceUntil { !anchor.modalPresent }

    private fun assertRect(message: String, expected: Rect, actual: Rect) {
        assertEquals("$message left", expected.left, actual.left, 2f)
        assertEquals("$message top", expected.top, actual.top, 2f)
        assertEquals("$message right", expected.right, actual.right, 2f)
        assertEquals("$message bottom", expected.bottom, actual.bottom, 2f)
    }

    private fun between(from: Rect, to: Rect, fraction: Float): Rect {
        fun part(a: Float, b: Float) = a + (b - a) * fraction
        return Rect(part(from.left, to.left), part(from.top, to.top),
            part(from.right, to.right), part(from.bottom, to.bottom))
    }

    private fun verifyOpening(home: Rect, reduced: Boolean) {
        val opening = compose.runOnUiThread { samples.filter { it.opening && it.progress > .001f } }
        assertTrue("Observe actual placed opening frames", opening.isNotEmpty())
        if (!reduced) assertTrue("Opening has intermediate frames", opening.any { it.progress < .99f })
        opening.forEach { sample ->
            if (reduced) assertEquals(1f, sample.progress, 0f)
            sample.flight?.let { flight ->
                val owned = requireNotNull(sample.openingOwnedBounds)
                assertRect("One opening rectangle owns size and travel at "+sample.progress, owned, sample.bounds)
                assertNotNull("Expansion does not wait for native IME travel", sample.placementBottom)

            }
        }
        assertRect("Opening reaches the keyboard anchor", opening.last().destination, opening.last().bounds)
        val view = requireNotNull(popupView)
        compose.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
            assertTrue("A docked keyboard must really occlude the content",
                insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
        }
        assertEquals("Native input view must be visible, not only the navigation strip", "true",
            Regex("\\bmIsInputViewShown=(true|false)\\b").find(shell("dumpsys -t 2 input_method"))?.groupValues?.get(1))
    }

    private fun verifyReturn(from: Rect, home: Rect, reduced: Boolean) {
        val all = compose.runOnUiThread { samples.toList() }
        val returning = all.filter { !it.opening }
        assertTrue("Observe actual placed return frames", returning.isNotEmpty())
        val positiveImeCapture = requireNotNull(all.lastOrNull { it.opening && it.ime > 0 })
        assertRect("Return captures the keyboard-visible placement", positiveImeCapture.bounds, from)
        if (!reduced) {
            assertTrue("Return has intermediate frames", returning.any { it.progress in .01f.. .99f })
            // Compose 1.8.2 preserves the source during the native animation and
            // resets source/target onEnd. Current insets may already be zero when
            // a closing window loses IME control, before that animation finishes.
            val overlap = returning.indexOfFirst {
                it.progress < .99f && (it.ime > 0 || it.imeSource > it.imeTarget)
            }
            assertTrue("Return moves before the native keyboard hide settles", overlap >= 0)
            // A closing popup can lose IME control before disposal. Its cached current
            // inset may rebound after onEnd, even though the active host is hidden.
            // Control release alone is insufficient: require real native window closure
            // and terminal zero insets on the surviving, focused activity as well.
            assertTrue("Observe native hide control release after moving overlap",
                returning.drop(overlap + 1).any { it.imeSource == 0 && it.imeTarget == 0 })
            awaitNativeHideEnd()
        }
        all.drop(all.indexOfLast { it.opening && it.ime > 0 } + 1)
            .filter { it.opening && it.imeTarget == 0 && it.progress == 1f }.forEach {
            assertRect("No keyboard-following frame before return ownership", from, it.bounds)
        }
        returning.forEach { sample ->
            assertRect("Captured expanded close start", from, requireNotNull(sample.flight).from)
            assertRect("Direct return independent of IME at ${sample.progress}",
                between(home, from, sample.progress), sample.bounds)
        }
        compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
    }

    private fun awaitNativeHideEnd() {
        var nativeWindow: Boolean? = null
        var focused = false
        var visible = true
        var bottom: Int? = null
        try {
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(32)
                compose.runOnUiThread {
                    val host = compose.activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(host)
                    focused = host.isAttachedToWindow && host.hasWindowFocus()
                    visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) != false
                    bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
                }
                nativeWindow = nativeImeWindowVisible(shell("dumpsys -t 2 input_method"))
                nativeWindow == false && focused && !visible && bottom == 0
            }
        } finally {
            keyboardSetup += "${SystemClock.uptimeMillis()} native-hide-end window=$nativeWindow " +
                "hostFocused=$focused hostImeVisible=$visible hostImeBottom=$bottom"
        }
    }

    private fun exerciseDismissal(outside: Boolean, reduced: Boolean = false) = withDockedKeyboard {
        fixture(true, reduced)
        try {
            val home = compose.runOnUiThread { anchor.bounds }
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            opened()
            // Opening no longer waits for native onEnd. Dismissal specifically
            // needs the editor's settled-IME hide detector armed before Back.
            awaitDockedKeyboard()
            opened()
            verifyOpening(home, reduced)
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            val raised = compose.runOnUiThread { samples.last().bounds }
            android.util.Log.i("SearchImeMotionTest", "Native dismissal requested outside=$outside uptime=${SystemClock.uptimeMillis()}")
            if (outside) {
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(raised.left > 2f)
                assertTrue(device.click(2, raised.center.y.toInt()))
            } else tracedSystemBack()
            android.util.Log.i("SearchImeMotionTest", "Native dismissal input returned uptime=${SystemClock.uptimeMillis()}")
            closed()
            verifyReturn(raised, home, reduced)
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun independentOpeningAndEmptyNativeBackHaveOneDirectReturn() = exerciseDismissal(outside = false)
    @Test fun outsidePointerDismissalDoesNotFollowTheKeyboardDown() = exerciseDismissal(outside = true)
    @Test fun reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome() = exerciseDismissal(outside = false, reduced = true)

    @Test fun absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness() = withNativeAnimations {
        fixture(false)
        try {
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            advanceUntil { anchor.modalPresent }
            compose.runOnUiThread { visible = false }
            closed()
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            opened()
            assertTrue("Absent keyboard still opens", compose.runOnUiThread { visible })
            compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
            val raised = compose.runOnUiThread { samples.last().bounds }
            compose.runOnUiThread { samples.clear(); visible = false }
            advanceUntil { anchor.contact.expansion.value in .2f.. .8f }
            val partial = compose.runOnUiThread { samples.last().bounds }
            val partialSurface = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
            compose.runOnUiThread { visible = true }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertRect("First reopening placement cannot borrow the previous opening bottom",
                partialSurface, compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot)
            opened()
            val reopening = compose.runOnUiThread { samples.filter { it.opening && it.flight != null } }
            assertTrue("Observe retained-window reopening", reopening.isNotEmpty())
            assertRect("Reopen from the rendered partial return", partial, requireNotNull(reopening.first().flight).from)
            assertRect("Reopen reaches the same absent-IME endpoint", raised, compose.runOnUiThread { samples.last().bounds })
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun disposingWhileKeyboardPreparationIsPendingRestoresThePill() = withNativeAnimations {
        fixture(false)
        try {
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            advanceUntil { anchor.modalPresent }
            compose.runOnUiThread { mounted = false }
            advanceUntil { !anchor.modalPresent && !anchor.hidden }
            compose.onNodeWithTag("list-search-pill").assertIsDisplayed()
            compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun shapeStartsBeforeKeyboardAndNativeArrivalDoesNotWaitForShapeCompletion() = withDockedKeyboard {
        fixture(true, deferKeyboard = true)
        try {
            val home = compose.runOnUiThread { anchor.bounds }
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            advanceUntil { samples.any { it.progress in .05f.. .35f && it.bounds.height > home.height + 2f } }
            val before = compose.runOnUiThread { samples.last() }
            assertEquals("The delayed IME has not appeared", 0, before.ime)
            assertNotNull("Accepted no-target fallback already moves while expanding", before.placementBottom)
            assertTrue("The same opening already moves away from the pill", before.bounds.top > home.top + 2f)
            compose.runOnUiThread { if (controls.enabled) phase("release-delayed-keyboard"); keyboardAllowed = true }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            // Native insets advance on real time while the controlled morph is
            // paused. Then one frame must let position start before p reaches one.
            compose.waitUntil(5000) {
                compose.runOnUiThread {
                    popupView?.let(ViewCompat::getRootWindowInsets)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { it > 0 } == true
                }
            }
            advanceUntil(virtualTimeoutMillis = 256) {
                samples.any { it.ime > 0 && it.progress < 1f && it.placementBottom != null }
            }
            val placed = compose.runOnUiThread { samples.last { it.placementBottom != null } }
            assertTrue("Travel starts while shape is incomplete", placed.progress < 1f)
            opened()
            verifyOpening(home, false)
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard() = withDockedKeyboard {
        fixture(true, initialExtraContentHeight = 1000)
        try {
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            opened()
            awaitDockedKeyboard()
            opened()
            val opening = compose.runOnUiThread { samples.filter { it.opening } }
            opening.filter { it.surfaceOpacity == 0f }.forEach {
                assertEquals("Window preparation cannot advance the visible shape", 0f, it.progress, 0f)
            }
            // Native window origin measurement precedes the prepared surface handoff.
            // Its hidden setup layouts are not rendered keyboard-anchor frames.
            val frames = opening.filter { it.surfaceOpacity > 0f && it.ime > 0 && it.imeTarget > 0 }
            assertTrue("Observe real keyboard arrival with bounded tall content", frames.isNotEmpty())
            assertTrue("Observe visible intermediate shape frames at the keyboard anchor",
                frames.any { it.progress in .001f.. .99f })
            val finalAnchor = frames.last().destination.bottom + frames.last().imeTarget
            frames.forEach {
                assertEquals("Target bottom cannot inherit oversized intermediate foreground height: $it",
                    finalAnchor, it.destination.bottom + it.imeTarget, 2f)
            }
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun completedShapeCanResizeBeforeDelayedKeyboardPlacementWithoutAStaleEndpoint() = withDockedKeyboard {
        fixture(true, reduced = true, deferKeyboard = true)
        try {
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            advanceUntil { samples.any { it.opening && it.progress == 1f } }
            val original = compose.runOnUiThread { samples.last() }
            assertEquals("The editor has not requested its keyboard", 0, original.ime)
            compose.runOnUiThread { if (controls.enabled) phase("resize-and-release-delayed-keyboard"); extraContentHeight = 72; keyboardAllowed = true }
            compose.mainClock.advanceTimeByFrame()
            opened()
            awaitDockedKeyboard()
            advanceUntil {
                samples.lastOrNull()?.let { it.bounds.height > original.bounds.height + 2f &&
                    kotlin.math.abs(it.bounds.bottom - it.destination.bottom) <= 2f &&
                    kotlin.math.abs(it.bounds.height - it.destination.height) <= 2f } == true
            }
            val placed = compose.runOnUiThread { samples.last() }
            assertRect("Late size and keyboard share the final owned endpoint", placed.destination, placed.bounds)
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun lateNativeImeCannotSnapAtTheOpeningToPlacementHandoff() = withDockedKeyboard {
        fixture(true, deferKeyboard = true)
        try {
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            opened()
            assertEquals("Fallback finishes before any native keyboard request", 0, compose.runOnUiThread { samples.last().ime })
            // Endpoint proximity does not prove that the opening owner has handed
            // off. Keep input deferred until the phase this regression exercises.
            val endpoint = compose.runOnUiThread { samples.last() }
            advanceUntil(virtualTimeoutMillis = springDuration(0f,
                maxOf(endpoint.destination.bottom, endpoint.bounds.bottom), pixels = true) + 256L) {
                samples.lastOrNull()?.let { it.opening && it.progress == 1f && it.flight == null && it.ime == 0 } == true
            }
            compose.runOnUiThread { samples.last() }.let {
                assertRect("Fallback handoff completes before releasing native input", it.destination, it.bounds)
            }
            compose.runOnUiThread { if (controls.enabled) phase("release-delayed-keyboard"); keyboardAllowed = true }
            awaitDockedKeyboard()
            repeat(40) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
            val all = compose.runOnUiThread { samples.toList() }
            val lastFlight = all.indexOfLast { it.flight != null && it.progress > 0f }
            assertTrue("Record the bounded-fallback opening", lastFlight >= 0)
            val firstPlacement = all.drop(lastFlight + 1).first { it.flight == null }
            assertRect("Endpoint ownership changes without a snap", all[lastFlight].destination, firstPlacement.bounds)
            assertTrue("Late keyboard changes placement through real intermediate frames",
                all.drop(lastFlight + 1).map { it.bounds }.distinct().size > 2)
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel() = withDockedKeyboard {
        fixture(true, initialQuery = "Keep this")
        try {
            compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
            opened()
            awaitDockedKeyboard()
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            compose.runOnUiThread { samples.clear() }
            tracedSystemBack()
            advanceUntil {
                val insets = popupView?.let(ViewCompat::getRootWindowInsets)
                keyboardSettled && insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
            }
            repeat(40) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
            assertTrue("First Back only ends nonempty editing", compose.runOnUiThread { visible })
            compose.onNode(hasSetTextAction()).assertTextContains("Keep this").assertIsNotFocused()
            val positions = compose.runOnUiThread { samples.filter { it.opening }.map { it.bounds }.distinct() }
            assertTrue("Retained panel recenters through intermediate rectangles", positions.size > 2)
            tracedSystemBack()
            closed()
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private fun awaitDockedKeyboard() {
        if (controls.enabled) compose.runOnUiThread { phase("await-docked-keyboard-start") }
        advanceUntil {
            val insets = popupView?.let(ViewCompat::getRootWindowInsets)
            keyboardSettled && insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        assertEquals("Native input view must be shown", "true",
            Regex("\\bmIsInputViewShown=(true|false)\\b").find(shell("dumpsys -t 2 input_method"))?.groupValues?.get(1))
    }

    private fun prepareDockedKeyboard() {
        if (controls.enabled) compose.runOnUiThread { phase("native-warmup-start enabled=${controls.nativeWarmup}") }
        if (controls.nativeWarmup) prepareNativeDockedKeyboard(compose, keyboardSetup, ::shell)
        if (controls.enabled) compose.runOnUiThread { phase("native-warmup-returned enabled=${controls.nativeWarmup}") }
    }

    private fun tracedSystemBack() {
        if (controls.enabled) compose.runOnUiThread { phase("system-back-request") }
        pressSystemBack()
        if (controls.enabled) compose.runOnUiThread { phase("system-back-returned") }
    }

    private fun withDockedKeyboard(block: () -> Unit) = withNativeAnimations {
        val key = "show_ime_with_hard_keyboard"
        val original = shell("settings get secure $key")
        check(original == "null" || original.matches(Regex("-?[0-9]+")))
        try {
            shell("settings put secure $key 1")
            assertEquals("1", shell("settings get secure $key"))
            keyboardSetup += "${SystemClock.uptimeMillis()} configured $key=1 (original=$original)"
            prepareDockedKeyboard()
            block()
        } finally {
            if (original == "null") shell("settings delete secure $key") else shell("settings put secure $key $original")
            assertEquals("Restore native keyboard policy", original, shell("settings get secure $key"))
        }
    }

    private fun withNativeAnimations(block: () -> Unit) {
        // API26's full-suite runner normally disables system animations. These
        // fixtures explicitly exercise overlapping native IME and app motion.
        val scales = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
            .associateWith { shell("settings get global $it") }
        check(scales.values.all { it == "null" || it.toFloatOrNull() != null })
        try {
            scales.keys.forEach {
                shell("settings put global $it 1")
                assertEquals(1f, shell("settings get global $it").toFloat(), 0f)
            }
            block()
        } finally {
            scales.forEach { (name, value) ->
                if (value == "null") shell("settings delete global $name") else shell("settings put global $name $value")
                assertEquals("Restore native animation scale $name", value, shell("settings get global $name"))
            }
        }
    }
}

package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
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

    @Before fun prepareWindow() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After fun recordPlacedFrames() {
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
        compose.setContent { LocalicaTheme {
            val speed = LocalMotionSpeed.current
            SideEffect { motionSpeed = speed }
            CompositionLocalProvider(LocalReducedMotion provides reduced,
                LocalSearchMorphObserver provides {
                    samples += it
                    motionTimeline += "${SystemClock.uptimeMillis()} $it"
                }) {
                anchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    ListSearchPill("Search fixture", { visible = true }, Modifier.align(Alignment.TopCenter)
                        .padding(top = 80.dp, start = 20.dp, end = 20.dp), anchor)
                }
                if (mounted) ListSearchWindow(visible, anchor, text.isNotBlank(), { visible = false }) {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    val focusedWindow = LocalWindowInfo.current.isWindowFocused
                    val ready = LocalSearchOpeningReady.current
                    val view = LocalView.current
                    val density = LocalDensity.current
                    val ime = WindowInsets.ime.getBottom(density)
                    val source = WindowInsets.imeAnimationSource.getBottom(density)
                    val target = WindowInsets.imeAnimationTarget.getBottom(density)
                    SideEffect { popupView = view; keyboardSettled = ime == target && source == target }
                    LaunchedEffect(visible, focusedWindow, ready) {
                        if (visible && focusedWindow) {
                            withFrameNanos { }
                            ready?.invoke(true)
                            if (showKeyboard) {
                                // The delayed fixture releases input only after observing
                                // the fallback endpoint, independent of CI wall-clock load.
                                snapshotFlow { keyboardAllowed }.first { it }
                                focus.requestFocus(); keyboard?.show()
                            } else keyboard?.hide()
                        }
                    }
                    CompositionLocalProvider(LocalEmptySearchBack provides ({ visible = false }).takeIf { text.isBlank() }) {
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
                val shapeTarget = requireNotNull(sample.openingShapeDestination)
                val remaining = 1f - flight.startingProgress
                val fraction = if (remaining <= .0001f) 1f else
                    ((sample.progress - flight.startingProgress) / remaining).coerceIn(0f, 1f)
                val shape = between(flight.from, shapeTarget, fraction)
                val height = minOf(shape.height, sample.openingHeightLimit)
                val top = sample.placementBottom?.minus(height) ?: flight.from.top
                assertRect("Independent opening shape/bottom at ${sample.progress}",
                    Rect(shape.left, top, shape.right, top + height), sample.bounds)
                if (sample.placementBottom == null)
                    assertEquals("Before keyboard travel, the pill top stays anchored", home.top, sample.bounds.top, 2f)
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
            assertTrue("The observed native hide reaches its end after moving overlap",
                returning.drop(overlap + 1).any { it.ime == 0 && it.imeSource == 0 && it.imeTarget == 0 })
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
            } else pressSystemBack()
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
            assertNull("Keyboard travel has not started", before.placementBottom)
            assertEquals("Shape expands downward from the pill", home.top, before.bounds.top, 2f)
            compose.runOnUiThread { keyboardAllowed = true }
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
            compose.runOnUiThread { extraContentHeight = 72; keyboardAllowed = true }
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
            compose.runOnUiThread { keyboardAllowed = true }
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
            pressSystemBack()
            advanceUntil {
                val insets = popupView?.let(ViewCompat::getRootWindowInsets)
                keyboardSettled && insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
            }
            repeat(40) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
            assertTrue("First Back only ends nonempty editing", compose.runOnUiThread { visible })
            compose.onNode(hasSetTextAction()).assertTextContains("Keep this").assertIsNotFocused()
            val positions = compose.runOnUiThread { samples.filter { it.opening }.map { it.bounds }.distinct() }
            assertTrue("Retained panel recenters through intermediate rectangles", positions.size > 2)
            pressSystemBack()
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
        lateinit var editor: EditText
        lateinit var root: FrameLayout
        lateinit var insetObserver: android.view.View
        val observation = SearchImeHandoffObservation()
        val input = requireNotNull(compose.activity.getSystemService(InputMethodManager::class.java))
        compose.runOnUiThread {
            root = FrameLayout(compose.activity).apply { isFocusableInTouchMode = true }
            insetObserver = createSearchImeObserver(compose.activity, observation)
            root.addView(insetObserver, FrameLayout.LayoutParams(0, 0))
            editor = EditText(compose.activity).apply { setSingleLine() }
            root.addView(editor, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            compose.activity.setContentView(root)
            ViewCompat.requestApplyInsets(insetObserver)
            editor.requestFocus()
        }
        fun record(label: String) {
            keyboardSetup += "${SystemClock.uptimeMillis()} $label " + compose.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(editor)
                "focused=${editor.isFocused} windowFocused=${editor.hasWindowFocus()} " +
                    "visible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                    "ime=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                    "navigation=${insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom} " +
                    "appliedIme=${observation.applied?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                    "animatedIme=${observation.animated?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                    "animations=${observation.animationCount}"
            }
        }
        fun awaitNativeInputView(shown: Boolean, timeoutMillis: Long) {
            var nextShowRequest = 0L
            try {
                compose.waitUntil(timeoutMillis) {
                    val placed = compose.runOnUiThread {
                        val insets = ViewCompat.getRootWindowInsets(editor) ?: return@runOnUiThread false
                        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                        if (shown) insets.isVisible(WindowInsetsCompat.Type.ime()) && ime > navigation &&
                            observation.applied?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                            observation.animationCount == 0
                        else !editor.isFocused && observation.complete(editor.hasWindowFocus(), insets)
                    }
                    if (shown && !placed && SystemClock.uptimeMillis() >= nextShowRequest) {
                        // A request rejected under the old policy need not replay
                        // when its observer arrives. Retry only this setup editor.
                        val requested = compose.runOnUiThread { input.showSoftInput(editor, 0) }
                        record("native-show-request=$requested")
                        nextShowRequest = SystemClock.uptimeMillis() + 1000L
                    }
                    // InputMethodService retains mIsInputViewShown after hiding
                    // its native window, so check it only while shown. Wait for the
                    // native hide's end too: removing the warmup window
                    // at its first zero inset can lend the still-hiding IME to the popup.
                    placed && (!shown || Regex("\\bmIsInputViewShown=(true|false)\\b")
                        .find(shell("dumpsys -t 2 input_method"))?.groupValues?.get(1) == "true")
                }
            } catch (timeout: ComposeTimeoutException) {
                record("native-input-view-$shown-timeout")
                val state = keyboardSetup.last()
                keyboardSetup += shell("dumpsys -t 2 input_method")
                throw AssertionError("Native keyboard setup did not reach shown=$shown: $state", timeout)
            }
            record("native-input-view-$shown-settled")
        }
        var failure: Throwable? = null
        try {
            record("installed-native-editor")
            compose.waitUntil(5000) {
                compose.runOnUiThread {
                    editor.isAttachedToWindow && editor.hasWindowFocus() && editor.isFocused && input.isActive(editor)
                }
            }
            // A shell setting write can notify a background IME after Android's
            // 10-second observer delay (AOSP Settings fix 632fd439248). Prove the
            // real input view and occlusion here, before measuring search motion.
            awaitNativeInputView(shown = true, timeoutMillis = 15000)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                compose.runOnUiThread {
                    root.requestFocus()
                    input.hideSoftInputFromWindow(editor.windowToken, 0)
                }
                awaitNativeInputView(shown = false, timeoutMillis = 5000)
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else {
                    failure = cleanup
                    throw cleanup
                }
            } finally {
                try {
                    compose.runOnUiThread {
                        disposeSearchImeObserver(insetObserver)
                        (root.parent as? ViewGroup)?.removeView(root)
                        assertEquals("The Compose test rule requires an empty activity content host", 0,
                            compose.activity.findViewById<ViewGroup>(android.R.id.content).childCount)
                    }
                } catch (removal: Throwable) {
                    if (failure != null) failure.addSuppressed(removal) else throw removal
                }
            }
        }
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

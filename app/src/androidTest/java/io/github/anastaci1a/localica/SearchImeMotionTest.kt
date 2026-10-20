package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
    private lateinit var anchor: GlassAnchor
    private var popupView: android.view.View? = null
    private var keyboardSettled = true
    private val samples = mutableListOf<SearchMorphSample>()

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
    }

    private fun fixture(showKeyboard: Boolean, reduced: Boolean = false, initialQuery: String = "", deferKeyboard: Boolean = false) {
        text = initialQuery
        keyboardAllowed = !deferKeyboard
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced,
                LocalSearchMorphObserver provides { samples += it }) {
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
                        GlassSearchField(text, { text = it }, "Search fixture", fieldModifier = Modifier.focusRequester(focus))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
    }

    private fun advanceUntil(predicate: () -> Boolean) = compose.waitUntil(5000) {
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread(predicate)
    }

    private fun opened() = advanceUntil {
        anchor.contact.expansion.value == 1f && !anchor.contact.expansion.isRunning &&
            samples.lastOrNull()?.let { it.opening && it.progress == 1f } == true
    }

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
        val destination = opening.first().destination
        opening.forEach { sample ->
            assertTrue("Opening starts only after final native IME placement: $sample",
                sample.ime > 0 && sample.ime == sample.imeSource && sample.ime == sample.imeTarget)
            if (reduced) assertEquals(1f, sample.progress, 0f)
            assertRect("One opening destination", destination, sample.destination)
            assertRect("Direct opening at ${sample.progress}", between(home, destination, sample.progress), sample.bounds)
        }
        assertRect("No second leg after opening", destination, opening.last().bounds)
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
        val returning = compose.runOnUiThread { samples.filter { !it.opening } }
        assertTrue("Observe actual placed return frames", returning.isNotEmpty())
        if (!reduced) {
            assertTrue("Return has intermediate frames", returning.any { it.progress in .01f.. .99f })
            assertTrue("Return starts while the keyboard is still hiding",
                returning.any { it.progress < .99f && it.ime > 0 })
        }
        compose.runOnUiThread { samples.filter { it.opening && it.imeTarget == 0 && it.progress == 1f } }.forEach {
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
            verifyOpening(home, reduced)
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            val raised = compose.runOnUiThread { samples.last().bounds }
            if (outside) {
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(raised.left > 2f)
                assertTrue(device.click(2, raised.center.y.toInt()))
            } else pressSystemBack()
            closed()
            verifyReturn(raised, home, reduced)
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun openingAndEmptyNativeBackHaveOneDirectFlight() = exerciseDismissal(outside = false)
    @Test fun outsidePointerDismissalDoesNotFollowTheKeyboardDown() = exerciseDismissal(outside = true)
    @Test fun reducedMotionResolvesTheKeyboardThenSnapsDirectlyHome() = exerciseDismissal(outside = false, reduced = true)

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
            compose.runOnUiThread { visible = true }
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

    private fun withDockedKeyboard(block: () -> Unit) = withNativeAnimations {
        val key = "show_ime_with_hard_keyboard"
        val original = shell("settings get secure $key")
        check(original == "null" || original.matches(Regex("-?[0-9]+")))
        try {
            shell("settings put secure $key 1")
            assertEquals("1", shell("settings get secure $key"))
            // The IME's private cached policy need not refresh before an editor
            // binds. Assert the setting here and its real input view/occlusion in
            // each fixture after focus, rather than polling that inactive cache.
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

package io.github.anastaci1a.localica

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalLayoutApi::class)
class SearchImeMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var visible by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private lateinit var anchor: GlassAnchor
    private var reduced by mutableStateOf(false)
    private var startedAt = 0L
    private var imeSource = 0
    private var imeTarget = 0
    private var ime = 0
    private var imeSettled = false
    private var phase = "opening"
    private var lastGeometry = ""

    @Before fun edgeToEdge() { compose.activityRule.scenario.onActivity {
        WindowCompat.setDecorFitsSystemWindows(it.window, false)
        it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }

    private fun fixture(showKeyboard: Boolean) {
        startedAt = android.os.SystemClock.uptimeMillis()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
            anchor = remember { GlassAnchor() }
            Box(Modifier.fillMaxSize()) {
                ListSearchPill("Search test", { visible = true }, Modifier.align(Alignment.TopCenter)
                    .padding(top = 100.dp, start = 20.dp, end = 20.dp), anchor)
            }
            if (mounted) ListSearchWindow(visible, anchor, false, { visible = false }) {
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                val report = LocalSearchOpeningFocus.current
                val windowFocused = LocalWindowInfo.current.isWindowFocused
                val density = LocalDensity.current
                val current = WindowInsets.ime.getBottom(density)
                val source = WindowInsets.imeAnimationSource.getBottom(density)
                val target = WindowInsets.imeAnimationTarget.getBottom(density)
                SideEffect { ime = current; imeSource = source; imeTarget = target; imeSettled = current > 0 && current == source && current == target }
                LaunchedEffect(windowFocused) {
                    if (windowFocused) {
                        withFrameNanos { }
                        if (showKeyboard) { focus.requestFocus(); keyboard?.show() }
                        else keyboard?.hide()
                        report?.invoke()
                    }
                }
                CompositionLocalProvider(LocalEmptySearchBack provides { visible = false }) {
                    GlassSearchField("", {}, "Search test", fieldModifier = Modifier.focusRequester(focus))
                }
            }
        } } }
    }

    private fun surface(): Rect? {
        val node = compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().singleOrNull() ?: return null
        val view = (node.root as ViewRootForTest).view
        val location = IntArray(2)
        compose.runOnUiThread {
            view.getLocationOnScreen(location)
            val nativeIme = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom
            val fullWindow = if (android.os.Build.VERSION.SDK_INT >= 30)
                view.context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.toString()
                else "legacy"
            val geometry = "$phase root=${view.rootView.width}x${view.rootView.height} view=${view.width}x${view.height}" +
                " origin=${location.toList()} config=${view.resources.configuration.screenWidthDp}x${view.resources.configuration.screenHeightDp}" +
                " window=$fullWindow ime=$nativeIme source=$imeSource target=$imeTarget settled=$imeSettled" +
                " elapsed=${android.os.SystemClock.uptimeMillis() - startedAt} p=${anchor.contact.expansion.value} bounds=${node.boundsInRoot}"
            if (geometry != lastGeometry) android.util.Log.i("SearchImeMotion", geometry)
            lastGeometry = geometry
        }
        return node.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
    }

    @Test fun openingWaitsForImeAndEmptyBackReturnsDirectlyWhileImeHides() {
        fixture(true)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                surface()
                val moving = compose.runOnUiThread { anchor.contact.expansion.value > .001f }
                if (moving) assertTrue("Initial flight must use settled keyboard geometry", imeSettled)
                compose.runOnUiThread { anchor.contact.expansion.value >= .999f && !anchor.contact.expansion.isRunning }
            }
            assertTrue("Native keyboard really opened", ime > 0)
            val raised = surface()!!
            val home = compose.runOnUiThread { anchor.bounds }
            assertTrue("Fixture has a measurable return flight", raised.top > home.top + 20f)
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            phase = "closing"
            pressSystemBack() // Empty search closes with exactly one native Back.
            val returnFlight = SearchMorphFlight(raised, 1f, opening = false)
            var samples = 0
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                surface()?.let { rect ->
                    val progress = compose.runOnUiThread { anchor.contact.expansion.value.coerceIn(0f, 1f) }
                    val expected = returnFlight.rectangle(progress, home)
                    assertEquals("Return y is independent of live IME geometry (ime=$ime)", expected.top, rect.top, 3f)
                    assertEquals("Return x follows the same flight", expected.left, rect.left, 3f)
                    samples++
                }
                compose.runOnUiThread { !anchor.modalPresent }
            }
            assertTrue("Sampled actual return frames", samples > 1)
            compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun absentKeyboardHasBoundedOpeningAndPendingOpenCanBeCanceled() {
        fixture(false)
        phase = "absent-keyboard"
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread { visible = false }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { !anchor.modalPresent }
            }
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { anchor.contact.expansion.value >= .999f && !anchor.contact.expansion.isRunning }
            }
            assertEquals("Fixture did not require a software keyboard", 0, ime)
            compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }
    @Test fun disposingPendingOpeningRestoresTheOriginalPill() {
        fixture(false)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread { mounted = false }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { !anchor.modalPresent && !anchor.hidden }
            }
            compose.onNodeWithTag("list-search-pill").assertIsDisplayed()
            compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun reducedMotionStillResolvesKeyboardTargetBeforeShowingThePopup() {
        reduced = true
        fixture(true)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                surface()
                val progress = compose.runOnUiThread { anchor.contact.expansion.value }
                if (progress > 0f) {
                    assertTrue("Reduced motion still resolves the native keyboard target", imeSettled)
                    assertEquals(1f, progress, 0f)
                }
                progress == 1f
            }
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            pressSystemBack()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { !anchor.modalPresent }
            }
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

}

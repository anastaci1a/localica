package io.github.anastaci1a.localica

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import kotlin.math.roundToInt

/** Real search IME -> native hide/search retirement -> new popup -> one native Back.
 * API36 additionally uses a physical edge swipe in verified gesture navigation.
 * No callback invocation, repeated Back, semantic dismissal or screenshot readiness. */
@OptIn(ExperimentalLayoutApi::class)
class PopupImeBackTest {
    private val compose = localicaAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val events = mutableListOf<String>()
    private lateinit var rootView: View
    private var searchView: View? = null
    private var popupView: View? = null

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    // Apply native policy before Activity creation; restore after Activity disposal.
    private val nativePolicy = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val keyboardKey = "show_ime_with_hard_keyboard"
                val originalKeyboard = shell("settings get secure $keyboardKey")
                check(originalKeyboard == "null" || originalKeyboard.matches(Regex("-?[0-9]+")))
                val gesture = "com.android.internal.systemui.navbar.gestural"
                fun activeNavigation() = Regex("(?m)^\\[x] (com\\.android\\.internal\\.systemui\\.navbar\\.[A-Za-z0-9_.]+)$")
                    .find(shell("cmd overlay list --user 0"))?.groupValues?.get(1)
                // The emulator can use its base navigation resource without an enabled overlay.
                val originalNavigation = if (Build.VERSION.SDK_INT >= 29) activeNavigation() else null
                val originalMode = shell("settings get secure navigation_mode")
                var failure: Throwable? = null
                try {
                    shell("settings put secure $keyboardKey 1")
                    assertEquals("1", shell("settings get secure $keyboardKey"))
                    if (Build.VERSION.SDK_INT >= 29) {
                        shell("cmd overlay enable-exclusive --user 0 --category $gesture")
                        val deadline = SystemClock.uptimeMillis() + 5000
                        while (shell("settings get secure navigation_mode") != "2" && SystemClock.uptimeMillis() < deadline)
                            SystemClock.sleep(50)
                        assertEquals(shell("cmd overlay list --user 0"), gesture, activeNavigation())
                        assertEquals("Native gesture navigation must be active", "2", shell("settings get secure navigation_mode"))
                    }
                    events += "nativePolicy api=${Build.VERSION.SDK_INT} navigation=${shell("settings get secure navigation_mode")}"
                    base.evaluate()
                } catch (caught: Throwable) { failure = caught }
                finally {
                    fun restore(action: () -> Unit) { try { action() } catch (caught: Throwable) {
                        if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                    } }
                    restore {
                        if (Build.VERSION.SDK_INT >= 29) {
                            if (originalNavigation != null)
                                shell("cmd overlay enable-exclusive --user 0 --category $originalNavigation")
                            else shell("cmd overlay disable --user 0 $gesture")
                            val deadline = SystemClock.uptimeMillis() + 5000
                            while (shell("settings get secure navigation_mode") != originalMode && SystemClock.uptimeMillis() < deadline)
                                SystemClock.sleep(50)
                            assertEquals(originalNavigation, activeNavigation())
                            assertEquals(originalMode, shell("settings get secure navigation_mode"))
                        }
                    }
                    restore {
                        if (originalKeyboard == "null") shell("settings delete secure $keyboardKey")
                        else shell("settings put secure $keyboardKey $originalKeyboard")
                        assertEquals(originalKeyboard, shell("settings get secure $keyboardKey"))
                    }
                }
                failure?.let { throw it }
            }
        }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(nativePolicy).around(compose)

    @Test fun systemBackAfterHiddenSearchImeDismissesBothPopupTypes() = exercise(edgeGesture = false)
    @Test fun edgeBackAfterHiddenSearchImeDismissesBothPopupTypes() = exercise(edgeGesture = true)

    private fun nativeTap(node: SemanticsNodeInteraction) {
        compose.awaitExpectedWindowFocus(node)
        val semantic = node.fetchSemanticsNode()
        val view = (semantic.root as ViewRootForTest).view
        val origin = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(origin) }
        assertTrue(device.click(origin[0] + semantic.boundsInRoot.center.x.roundToInt(),
            origin[1] + semantic.boundsInRoot.center.y.roundToInt()))
        compose.waitForIdle()
    }

    private fun awaitIme(shown: Boolean) {
        compose.waitUntil(20_000) {
            val ready = compose.runOnUiThread {
                val view = searchView?.takeIf { it.isAttachedToWindow && it.hasWindowFocus() } ?: rootView
                val insets = ViewCompat.getRootWindowInsets(view)
                view.hasWindowFocus() && insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == shown &&
                    (!shown || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            }
            ready && nativeImeWindowVisible(shell("dumpsys -t 2 input_method")) == shown
        }
        events += "${SystemClock.uptimeMillis()} nativeImeShown=$shown"
    }

    private fun exercise(edgeGesture: Boolean) {
        var searchOpen by mutableStateOf(false)
        var popupOpen by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var dismissals = 0
        var keyboardSettled = false
        lateinit var searchAnchor: GlassAnchor
        lateinit var popupAnchor: GlassAnchor
        var failure: Throwable? = null
        try {
            compose.activityRule.scenario.onActivity {
                WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            prepareNativeDockedKeyboard(compose, events, ::shell)
            compose.setContent { if (mounted) LocalicaTheme {
                val root = LocalView.current
                SideEffect { rootView = root }
                searchAnchor = rememberGlassAnchor()
                popupAnchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize().testTag("ime-back-background")) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        ListSearchPill("Search before popup", { searchOpen = true }, anchor = searchAnchor)
                        GlassActionButton(AppIcon.ActionEllipsis, "Open IME Back popup",
                            Modifier.glassAnchor(popupAnchor).testTag("ime-back-popup-trigger")) { popupOpen = true }
                    }
                }
                ListSearchWindow(searchOpen, searchAnchor, false, { searchOpen = false }) {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    val focused = LocalWindowInfo.current.isWindowFocused
                    val ready = LocalSearchOpeningReady.current
                    val view = LocalView.current
                    val density = LocalDensity.current
                    val ime = WindowInsets.ime.getBottom(density)
                    val source = WindowInsets.imeAnimationSource.getBottom(density)
                    val target = WindowInsets.imeAnimationTarget.getBottom(density)
                    SideEffect { searchView = view; keyboardSettled = focused && ime > 0 && ime == source && ime == target }
                    LaunchedEffect(searchOpen, focused, ready) {
                        if (searchOpen && focused) {
                            withFrameNanos { }
                            ready?.invoke(true)
                            focus.requestFocus(); keyboard?.show()
                        }
                    }
                    CompositionLocalProvider(LocalEmptySearchBack provides { searchOpen = false }) {
                        GlassSearchField("", {}, "Search before popup", fieldModifier = Modifier.focusRequester(focus))
                    }
                }
                val close = { dismissals++; popupOpen = false }
                val content: @Composable () -> Unit = {
                    val view = LocalView.current
                    SideEffect { popupView = view }
                    Text("Native Back popup", Modifier.padding(24.dp).testTag("ime-back-popup-body"))
                }
                if (dialog) {
                    if (popupOpen) GlassDialog(close, content = content)
                } else GlassMenu(popupOpen, popupAnchor, close) { content() }
            } }
            for (asDialog in listOf(false, true)) {
                compose.runOnIdle { dialog = asDialog }
                val label = "${if (asDialog) "dialog" else "menu"}-${if (edgeGesture) "edge" else "system"}"
                nativeTap(compose.onNodeWithTag("list-search-pill"))
                awaitIme(true)
                compose.waitUntil(5000) { compose.runOnUiThread { keyboardSettled } }
                compose.waitForIdle()
                events += "${SystemClock.uptimeMillis()} $label Compose IME settled"
                compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
                pressSystemBack()
                awaitIme(false)
                // Drain queued editing/return effects as well as advancing animation frames.
                compose.waitUntil(5000) { compose.runOnIdle { !searchOpen && !searchAnchor.modalPresent } }
                compose.awaitExpectedWindowFocus(compose.onNodeWithTag("ime-back-popup-trigger"))
                nativeTap(compose.onNodeWithTag("ime-back-popup-trigger"))
                val body = compose.onNodeWithTag("ime-back-popup-body").assertIsDisplayed()
                compose.awaitExpectedWindowFocus(body)
                compose.runOnIdle {
                    val view = requireNotNull(popupView)
                    assertTrue("Actual popup owns a native window", view.parent is DialogWindowProvider)
                    assertNotSame(rootView, view)
                }
                val before = compose.runOnIdle { dismissals }
                val auto = compose.mainClock.autoAdvance
                compose.mainClock.autoAdvance = false
                try {
                    events += "${SystemClock.uptimeMillis()} $label requesting one native Back"
                    if (edgeGesture && Build.VERSION.SDK_INT >= 29) {
                        assertTrue(device.swipe(1, device.displayHeight / 2, device.displayWidth / 2,
                            device.displayHeight / 2, 30))
                    } else pressSystemBack() // Legacy API26 has no gesture-navigation overlay.
                    compose.waitUntil(5000) { compose.runOnUiThread { dismissals > before } }
                    compose.runOnUiThread {
                        assertEquals("$label must receive exactly one native dismissal", before + 1, dismissals)
                        assertFalse(popupOpen)
                    }
                    events += "${SystemClock.uptimeMillis()} $label native dismissal acknowledged"
                } finally { compose.mainClock.autoAdvance = auto }
                compose.waitForIdle()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface", true).fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(5000) { compose.runOnUiThread { popupView?.isAttachedToWindow == false } }
                compose.awaitExpectedWindowFocus(compose.onNodeWithTag("ime-back-popup-trigger"))
                events += "${SystemClock.uptimeMillis()} $label native popup retired"
            }
        } catch (caught: Throwable) { failure = caught }
        finally {
            fun finish(action: () -> Unit) { try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            } }
            finish {
                val directory = nativeEvidenceDirectory("popup-ime-back", instrumentation.targetContext.getExternalFilesDir(null))
                File(directory, "${if (edgeGesture) "edge" else "system"}-api${Build.VERSION.SDK_INT}.txt")
                    .writeText(events.joinToString("\n", postfix = "\n") +
                        "searchOpen=$searchOpen popupOpen=$popupOpen keyboardSettled=$keyboardSettled dismissals=$dismissals\n" + "failure=$failure\n" +
                        shell("dumpsys -t 2 input_method") + "\n" + shell("dumpsys -t 2 window windows"))
            }
            finish { compose.mainClock.autoAdvance = true; compose.runOnIdle { mounted = false }; compose.waitForIdle() }
        }
        failure?.let { throw it }
    }
}

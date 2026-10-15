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
    private var popupView: android.view.View? = null
    private val samples = mutableListOf<Pair<String, SearchMorphSample>>()
    private fun record(sample: SearchMorphSample) {
        samples += phase to sample
        val view = popupView
        val window = (view?.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val rootInsets = view?.let(androidx.core.view.ViewCompat::getRootWindowInsets)
        val rootIme = rootInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom
        val visibleIme = rootInsets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
        val imeRects = if (android.os.Build.VERSION.SDK_INT >= 35)
            view?.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.ime()) else null
        val decorIme = window?.decorView?.let(androidx.core.view.ViewCompat::getRootWindowInsets)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom
        android.util.Log.i("SearchImeMotion", "layout phase=$phase elapsed=${android.os.SystemClock.uptimeMillis() - startedAt}" +
            " rootIme=$rootIme imeVisible=$visibleIme imeRects=$imeRects decorIme=$decorIme focus=${view?.hasWindowFocus()}" +
            " softInput=${window?.attributes?.softInputMode} flags=${window?.attributes?.flags}" +
            " rootToken=${view?.windowToken} dialogToken=${window?.decorView?.windowToken} $sample")
    }

    // Fixed read-only commands; emit only allowlisted state values, never editor text or raw dumps.
    private fun keyboardDiagnostics(label: String, view: android.view.View? = popupView) {
        val nativeState = compose.runOnUiThread {
            val configuration = compose.activity.resources.configuration
            val nativeInsets = view?.let(androidx.core.view.ViewCompat::getRootWindowInsets)
            "keyboard-state $label keyboard=${configuration.keyboard}" +
                " keyboardHidden=${configuration.keyboardHidden} hardKeyboardHidden=${configuration.hardKeyboardHidden}" +
                " attached=${view?.isAttachedToWindow} focused=${view?.hasWindowFocus()}" +
                " nativeIme=${nativeInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}" +
                " visible=${nativeInsets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}"
        }
        android.util.Log.i("SearchImeMotion", nativeState)
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val state = Regex("""\b(mInputShown|mShowRequested|mShowExplicitlyRequested|mShowForced|mShowInputRequested|mInputViewShown|mIsInputViewShown|mWindowVisible|mWindowWasVisible|mImeWindowVis|mShowImeWithHardKeyboard|mHardwareKeyboardAvailable|mHasSurface|mViewVisibility|mPolicyVisibility|mPolicyVisibilityAfterAnim|isOnScreen|isVisible|isReadyForDisplay|mSurfaceShown|mAnimatingExit|mGivenInsetsPending)=(true|false|0x[0-9a-fA-F]+|[0-9]+)\b""")
        for (command in listOf("settings get secure show_ime_with_hard_keyboard", "dumpsys input_method", "dumpsys window windows")) {
            runCatching {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                    .bufferedReader().use { reader ->
                        var relevantWindow = false
                        val fields = mutableListOf<String>()
                        reader.lineSequence().take(6000).forEach { line ->
                            if (command.startsWith("settings")) {
                                val value = line.trim().takeIf { it == "null" || it.matches(Regex("[0-9]+")) } ?: "unavailable"
                                if (fields.isEmpty()) fields += "show_ime_with_hard_keyboard=$value"
                            } else {
                                if (command == "dumpsys window windows" && line.trimStart().startsWith("Window #")) {
                                    relevantWindow = line.contains("InputMethod") || line.contains("io.github.anastaci1a.localica")
                                    if (relevantWindow && fields.size < 80) fields += if (line.contains("InputMethod")) "IME-window" else "fixture-window"
                                }
                                if (command == "dumpsys input_method" || relevantWindow) {
                                    state.findAll(line).forEach { if (fields.size < 80) fields += it.value }
                                }
                            }
                        }
                        android.util.Log.i("SearchImeMotion", "keyboard-state $label $command ${fields.joinToString(" ").ifEmpty { "no-allowlisted-fields" }}")
                    }
            }.onFailure { android.util.Log.w("SearchImeMotion", "keyboard-state $label $command unavailable (${it.javaClass.simpleName})") }
        }
    }

    private fun screenshotEvidence() = runCatching {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@runCatching
        try {
            val bytes = java.io.ByteArrayOutputStream().also {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            val name = "ime-${if (reduced) "reduced" else "normal"}"
            android.util.Log.i("SearchImeMotion", "screenshot $name ${screenshot.width}x${screenshot.height} bytes=${bytes.size}")
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").writeBytes(bytes)
            // Synthetic fixture only. Keep the native IME/window scene in captured CI logs.
            if (bytes.size <= 512 * 1024) {
                val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(3000)
                chunks.forEachIndexed { index, chunk -> android.util.Log.i("SearchImeMotion", "PNG $name ${index + 1}/${chunks.size} $chunk") }
            }
        } finally { screenshot.recycle() }
    }.onFailure { android.util.Log.w("SearchImeMotion", "Native screenshot evidence unavailable", it) }

    @Before fun edgeToEdge() { compose.activityRule.scenario.onActivity {
        WindowCompat.setDecorFitsSystemWindows(it.window, false)
        it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }

    private fun fixture(showKeyboard: Boolean) {
        startedAt = android.os.SystemClock.uptimeMillis()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides reduced, LocalSearchMorphObserver provides ::record) {
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
                val view = androidx.compose.ui.platform.LocalView.current
                SideEffect { popupView = view }
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
            val dialog = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val decorInsets = dialog?.decorView?.let(androidx.core.view.ViewCompat::getRootWindowInsets)
            val nativeFocus = view.findFocus()
            val geometry = "windowFlags=${dialog?.attributes?.flags} softInput=${dialog?.attributes?.softInputMode}" +
                " decorIme=${decorInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}" +
                " rootFocus=${view.hasWindowFocus()} focused=${nativeFocus?.javaClass?.simpleName}" +
                " rootToken=${view.windowToken} dialogToken=${dialog?.decorView?.windowToken} " +
                "$phase root=${view.rootView.width}x${view.rootView.height} view=${view.width}x${view.height}" +
                " origin=${location.toList()} config=${view.resources.configuration.screenWidthDp}x${view.resources.configuration.screenHeightDp}" +
                " window=$fullWindow ime=$nativeIme source=$imeSource target=$imeTarget settled=$imeSettled" +
                " elapsed=${android.os.SystemClock.uptimeMillis() - startedAt} p=${anchor.contact.expansion.value} bounds=${node.boundsInRoot}"
            if (geometry != lastGeometry) android.util.Log.i("SearchImeMotion", geometry)
            lastGeometry = geometry
        }
        return node.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
    }

    @Test fun plainNativeEditorProvidesDockedImeControl() {
        var editor: android.widget.EditText? = null
        compose.setContent {
            androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                android.widget.EditText(context).also {
                    editor = it
                    it.isSingleLine = true
                    it.hint = "Synthetic IME control"
                }
            }, modifier = Modifier.fillMaxWidth())
        }
        try {
            compose.waitUntil(5000) {
                compose.runOnUiThread { editor?.let { it.isAttachedToWindow && it.hasWindowFocus() } == true }
            }
            compose.runOnUiThread {
                val editor = requireNotNull(editor)
                editor.requestFocus()
                val manager = editor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                manager.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            compose.waitUntil(5000) {
                compose.runOnUiThread {
                    androidx.core.view.ViewCompat.getRootWindowInsets(requireNotNull(editor))
                        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?.let { it > 0 } == true
                }
            }
        } finally {
            keyboardDiagnostics("plain-native-control", editor)
            compose.runOnUiThread {
                editor?.let { editor ->
                    val manager = editor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    manager.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
            }
        }
    }

    @Test fun openingWaitsForImeAndEmptyBackReturnsDirectlyWhileImeHides() {
        fixture(true)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { anchor.contact.expansion.value == 1f && !anchor.contact.expansion.isRunning &&
                    samples.lastOrNull()?.second?.progress == 1f }
            }
            keyboardDiagnostics(if (reduced) "reduced-endpoint" else "normal-endpoint")
            screenshotEvidence()
            assertTrue("Docked-keyboard fixture requires a measured occluding IME; inspect native screenshot/visibility if absent", ime > 0)
            val opening = compose.runOnUiThread { samples.filter { it.first == "opening" }.map { it.second }.filter { it.progress > .001f } }
            assertTrue("Recorded actual opening layouts", opening.size > 1)
            opening.forEach { sample -> assertTrue("Initial flight must use settled keyboard geometry: $sample",
                sample.ime > 0 && sample.ime == sample.imeSource && sample.ime == sample.imeTarget) }
            val raised = surface()!!
            val home = compose.runOnUiThread { anchor.bounds }
            assertTrue("Fixture has a measurable return flight", raised.top > home.top + 20f)
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
            }
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            phase = "closing"
            pressSystemBack() // Empty search closes with exactly one native Back.
            val returnFlight = SearchMorphFlight(raised, 1f, opening = false)
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { !anchor.modalPresent }
            }
            val closing = compose.runOnUiThread { samples.filter { it.first == "closing" }.map { it.second } }
            assertTrue("Sampled actual return frames", closing.size > 1)
            closing.forEach { sample ->
                val expected = returnFlight.rectangle(sample.progress, home)
                assertEquals("Return y is independent of live IME geometry: $sample", expected.top, sample.bounds.top, 3f)
                assertEquals("Return x follows the same flight: $sample", expected.left, sample.bounds.left, 3f)
            }
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
                compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { !anchor.modalPresent }
            }
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { anchor.contact.expansion.value == 1f && !anchor.contact.expansion.isRunning &&
                    samples.lastOrNull()?.second?.progress == 1f }
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
                compose.mainClock.advanceTimeBy(64)
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
                compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { samples.any { it.second.progress == 1f } }
            }
            keyboardDiagnostics(if (reduced) "reduced-endpoint" else "normal-endpoint")
            screenshotEvidence()
            assertTrue("Docked-keyboard fixture requires a measured occluding IME; inspect native screenshot/visibility if absent", ime > 0)
            val shown = compose.runOnUiThread { samples.map { it.second }.filter { it.progress > 0f } }
            assertTrue("Recorded the reduced-motion endpoint", shown.isNotEmpty())
            shown.forEach { sample ->
                assertTrue("Reduced motion still resolves the native keyboard target: $sample",
                    sample.ime > 0 && sample.ime == sample.imeSource && sample.ime == sample.imeTarget)
                assertEquals(1f, sample.progress, 0f)
            }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
            }
            surface()
            compose.awaitExpectedWindowFocus(compose.onNode(hasSetTextAction()))
            pressSystemBack()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { !anchor.modalPresent }
            }
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

}

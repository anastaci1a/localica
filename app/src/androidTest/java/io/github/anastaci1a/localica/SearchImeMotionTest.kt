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
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.ClassRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

@OptIn(ExperimentalLayoutApi::class)
class SearchImeMotionTest {
    companion object {
        private fun nativeShell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

        // Own policy before Activity creation and restore it only after every Activity is disposed.
        // A method-body finally can reconfigure the IME while its editor/Back callbacks still live.
        @JvmField @ClassRule val nativePolicies: TestRule = object : TestRule {
            override fun apply(base: Statement, description: Description) = object : Statement() {
                override fun evaluate() {
                    val settings = listOf("secure show_ime_with_hard_keyboard", "global animator_duration_scale",
                        "global transition_animation_scale", "global window_animation_scale")
                    val changed = linkedMapOf<String, String>()
                    val events = mutableListOf<String>()
                    var failure: Throwable? = null
                    try {
                        for (setting in settings) {
                            val original = nativeShell("settings get $setting")
                            check(original == "null" || original.toFloatOrNull() != null)
                            if (original.toFloatOrNull() != 1f) {
                                changed[setting] = original
                                nativeShell("settings put $setting 1")
                            }
                            assertEquals("Native policy precedes Activity creation", 1f,
                                nativeShell("settings get $setting").toFloat(), 0f)
                            events += "${SystemClock.uptimeMillis()} before-activities $setting=1 original=$original"
                        }
                        base.evaluate()
                    } catch (caught: Throwable) { failure = caught }
                    finally {
                        for ((setting, original) in changed.entries.reversed()) {
                            try {
                                if (original == "null") nativeShell("settings delete $setting")
                                else nativeShell("settings put $setting $original")
                                assertEquals("Restore native policy after Activity disposal", original,
                                    nativeShell("settings get $setting"))
                                events += "${SystemClock.uptimeMillis()} after-activities $setting=$original"
                            } catch (caught: Throwable) {
                                if (failure == null) failure = caught else failure.addSuppressed(caught)
                            }
                        }
                        try {
                            val directory = nativeEvidenceDirectory("search-ime-policy",
                                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
                            java.io.File(directory, "class-policy-api${android.os.Build.VERSION.SDK_INT}.txt")
                                .writeText(events.joinToString("\n", postfix = "\n") + "failure=$failure\n")
                        } catch (caught: Throwable) {
                            if (failure == null) failure = caught else failure.addSuppressed(caught)
                        }
                    }
                    failure?.let { throw it }
                }
            }
        }
    }

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
    private var nativeActionReturnGate: java.util.concurrent.CountDownLatch? = null
    private var nativeReturnProbe: NativeReturnProbe? = null
    private val nativeReturnFrames = mutableListOf<Pair<SearchMorphSample, NativeReturnEvidence>>()

    private data class NativeReturnEvidence(
        val uptime: Long,
        val virtual: Long,
        val requested: Long?,
        val progressAt: Long?,
        val attached: Boolean,
        val focused: Boolean,
        val ime: Int,
        val navigation: Int,
        val targetVisible: Boolean?,
        val targetIme: Int?,
        val activeHideIds: List<Int>,
    ) {
        // The popup may release its insets before Android applies the hide to the
        // surviving host. That host is still unfocused until the popup retires.
        // A return placed in this interval is already ahead of the native hide;
        // requiring onStart/focus would reject an independently faster return.
        val precedesNativeHide: Boolean get() = requested != null && uptime >= requested &&
            attached && targetVisible == true && targetIme != null && targetIme > navigation

        val overlapsNativeHide: Boolean get() = requested != null && progressAt != null &&
            progressAt >= requested && uptime >= progressAt && attached && focused &&
            targetVisible == false && targetIme == 0 && ime > navigation && activeHideIds.isNotEmpty()
    }

    // Observe the surviving host on every API: a released legacy popup can report
    // zero before this host receives the native hide. Only API 30+ installs platform
    // animation callbacks; synthetic legacy callbacks cannot prove native overlap.
    // This visible zero-size decor sibling never takes input or replaces Compose listeners.
    // A callback can change native frame synchronization: this is not timing-equivalent
    // evidence to the uninstrumented baseline, and cannot establish optical acceptance.
    private inner class NativeReturnProbe : android.view.View(compose.activity) {
        private var requested: Long? = null
        private var progressAt: Long? = null
        private val preparedAfterRequest = mutableSetOf<WindowInsetsAnimationCompat>()
        private val activeHides = mutableSetOf<WindowInsetsAnimationCompat>()
        private var progressed = emptySet<WindowInsetsAnimationCompat>()
        private var animatedIme = 0
        private var animatedNavigation = 0

        init {
            isFocusable = false
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) ViewCompat.setWindowInsetsAnimationCallback(this,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0 && requested != null &&
                            SystemClock.uptimeMillis() >= requireNotNull(requested)) preparedAfterRequest += animation
                        record("prepare", animation)
                    }
                    override fun onStart(animation: WindowInsetsAnimationCompat,
                        bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            // Never borrow a previous animation's positive current inset.
                            progressed = emptySet()
                            progressAt = null
                            val target = ViewCompat.getRootWindowInsets(this@NativeReturnProbe)
                            if (animation in preparedAfterRequest && requested != null &&
                                SystemClock.uptimeMillis() >= requireNotNull(requested) &&
                                target?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                                target.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0) {
                                // Bind hide direction to this identity after its end-state apply.
                                activeHides += animation
                            }
                        }
                        record("start", animation)
                        return bounds
                    }
                    override fun onProgress(insets: WindowInsetsCompat,
                        runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                        progressed = runningAnimations.filter { it in activeHides }.toSet()
                        progressAt = SystemClock.uptimeMillis().takeIf { progressed.isNotEmpty() }
                        animatedIme = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        animatedNavigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                        record("progress")
                        return insets
                    }
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        preparedAfterRequest -= animation
                        activeHides -= animation
                        progressed = progressed - animation
                        if (progressed.isEmpty()) progressAt = null
                        record("end", animation)
                    }
                })
        }

        private fun reset() {
            preparedAfterRequest.clear()
            activeHides.clear()
            progressed = emptySet()
            progressAt = null
            animatedIme = 0
            animatedNavigation = 0
        }
        fun arm() {
            reset()
            requested = SystemClock.uptimeMillis()
            record("dismissal-armed")
        }
        fun snapshot(): NativeReturnEvidence {
            val target = ViewCompat.getRootWindowInsets(this)
            return NativeReturnEvidence(SystemClock.uptimeMillis(), compose.mainClock.currentTime,
                requested, progressAt, isAttachedToWindow, hasWindowFocus(), animatedIme,
                if (android.os.Build.VERSION.SDK_INT >= 30) animatedNavigation
                else target?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0,
                target?.isVisible(WindowInsetsCompat.Type.ime()),
                target?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                progressed.filter { it in activeHides }.map { System.identityHashCode(it) })
        }
        private fun record(phase: String, animation: WindowInsetsAnimationCompat? = null) {
            val evidence = snapshot()
            motionTimeline += "${evidence.uptime} nativeReturn phase=$phase " +
                "animation=${animation?.let { System.identityHashCode(it) }} type=${animation?.typeMask} $evidence"
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) reset()
            record("focus=$hasWindowFocus")
        }
        override fun onDetachedFromWindow() {
            reset()
            record("detach")
            super.onDetachedFromWindow()
        }
        fun dispose() {
            if (android.os.Build.VERSION.SDK_INT >= 30) ViewCompat.setWindowInsetsAnimationCallback(this, null)
            reset()
            requested = null
            (parent as? android.view.ViewGroup)?.removeView(this)
        }
    }

    @Before fun prepareWindow() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After fun recordPlacedFrames() {
        try {
            if (::anchor.isInitialized) {
                motionTimeline += "${SystemClock.uptimeMillis()} fixture-cleanup-start"
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { visible = false }
                compose.waitUntil(5000) { compose.runOnIdle { !anchor.modalPresent } }
                compose.waitUntil(5000) { compose.runOnUiThread { popupView?.isAttachedToWindow != true } }
                // Drain editing/return effects before releasing the native IME owner or policy.
                compose.waitForIdle()
                if (compose.runOnUiThread { samples.any { it.ime > 0 || it.nativeIme > 0 } }) awaitNativeHideEnd()
                else compose.waitUntil(5000) { compose.runOnIdle {
                    val host = compose.activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(host)
                    host.hasWindowFocus() && insets?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
                } }
                motionTimeline += "${SystemClock.uptimeMillis()} fixture-native-owner-retired"
            }
        } finally {
            val additional = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
            val root = additional?.let { java.io.File(it) } ?: requireNotNull(compose.activity.getExternalFilesDir(null))
            val directory = java.io.File(root, "search-ime-motion").apply { mkdirs() }
            java.io.File(directory, "${name.methodName}.txt").writeText(compose.runOnUiThread { samples.toList() }.joinToString("\n"))
            java.io.File(directory, "${name.methodName}-keyboard-setup.txt").writeText(keyboardSetup.joinToString("\n"))
            java.io.File(directory, "${name.methodName}-timeline.txt").writeText(
                compose.runOnUiThread { motionTimeline.toList() }.joinToString("\n"))
        }
    }

    private fun fixture(showKeyboard: Boolean, reduced: Boolean = false, initialQuery: String = "", deferKeyboard: Boolean = false, initialExtraContentHeight: Int = 0) {
        text = initialQuery
        extraContentHeight = initialExtraContentHeight
        keyboardAllowed = !deferKeyboard
        compose.setContent { LocalicaTheme {
            val speed = LocalMotionSpeed.current
            SideEffect { motionSpeed = speed }
            CompositionLocalProvider(LocalTextEditingObservation provides { event ->
                val native = popupView?.let { view ->
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                    "view=${System.identityHashCode(view)} attached=${view.isAttachedToWindow} " +
                        "focused=${view.hasWindowFocus()} nativeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                        "nativeHeight=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}"
                }
                motionTimeline += "${SystemClock.uptimeMillis()} textEditing $event nativeObservation=$native"
            }, LocalReducedMotion provides reduced,
                LocalSearchMorphObserver provides {
                    samples += it
                    if (!it.opening && it.progress in .01f.. .99f) nativeActionReturnGate?.countDown()
                    motionTimeline += "${SystemClock.uptimeMillis()} $it"
                    nativeReturnProbe?.snapshot()?.let { evidence ->
                        nativeReturnFrames += it to evidence
                        motionTimeline += "${evidence.uptime} nativeReturn phase=placed " +
                            "sample=${samples.lastIndex} progress=${it.progress} opening=${it.opening} $evidence"
                    }
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
                    val hiddenImeConsumed by rememberUpdatedState(ime == 0 && source == 0 && target == 0)
                    SideEffect { popupView = view; keyboardSettled = ime == target && source == target }
                    LaunchedEffect(visible, focusedWindow, ready) {
                        if (visible && focusedWindow) {
                            withFrameNanos { }
                            ready?.invoke(true)
                            if (showKeyboard) {
                                // The delayed fixture releases input only after observing
                                // the fallback endpoint, independent of CI wall-clock load.
                                snapshotFlow { keyboardAllowed }.first { it }
                                // The warmup editor's hide can reach this new popup after attach.
                                // Consume it before focus can arm the measured editor's hide detector.
                                do {
                                    withFrameNanos { }
                                    val native = ViewCompat.getRootWindowInsets(view)
                                    val hidden = native?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                                        native.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
                                } while (!hiddenImeConsumed || !hidden)
                                motionTimeline += "${SystemClock.uptimeMillis()} measured-editor-focus-after-native-and-compose-hide"
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
                // Native callbacks can queue editing/animation effects after the frame tick.
                // Drain them before sampling, rather than reading an older placed frame.
                val satisfied = compose.runOnIdle(predicate)
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
            val nativeFrames = compose.runOnUiThread { nativeReturnFrames.toList() }
            val overlap = returning.indexOfFirst { sample ->
                // A released popup can report all-zero insets before the surviving
                // activity's native hide has even started. Count independently observed
                // native evidence only on this exact intermediate placed return frame.
                val hostOverlap = sample.progress >= .01f && nativeFrames.any { (placed, evidence) ->
                    placed === sample && (evidence.precedesNativeHide || evidence.overlapsNativeHide)
                }
                sample.progress < .99f && (sample.ime > 0 || sample.imeSource > sample.imeTarget || hostOverlap)
            }
            assertTrue("Return moves before the native keyboard hide settles", overlap >= 0)
            // The independently clocked return can dispose the popup before native onEnd.
            // Its retired inset snapshots cannot witness that later control release:
            // require native window closure and terminal zero insets on the focused host.
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

    private fun exerciseDismissal(outside: Boolean, reduced: Boolean = false, awaitFirstReturn: Boolean = false) = withDockedKeyboard {
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
            if (outside) compose.runOnUiThread {
                nativeReturnProbe = NativeReturnProbe().also { probe ->
                    (compose.activity.window.decorView as android.view.ViewGroup)
                        .addView(probe, 0, android.view.ViewGroup.LayoutParams(0, 0))
                    ViewCompat.requestApplyInsets(probe)
                    probe.arm()
                }
            }
            android.util.Log.i("SearchImeMotionTest", "Native dismissal requested outside=$outside uptime=${SystemClock.uptimeMillis()}")
            val returnGate = java.util.concurrent.CountDownLatch(1).takeIf { awaitFirstReturn }
            compose.runOnUiThread { nativeActionReturnGate = returnGate }
            nativeDismissal {
                if (outside) {
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    assertTrue(raised.left > 2f)
                    assertTrue(device.click(2, raised.center.y.toInt()))
                } else pressSystemBack()
                returnGate?.let { gate ->
                    assertTrue("Native input completion must not starve the first placed return frame",
                        gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                android.util.Log.i("SearchImeMotionTest", "Native dismissal input returned uptime=${SystemClock.uptimeMillis()}")
            }
            verifyReturn(raised, home, reduced)
        } finally {
            compose.runOnUiThread {
                nativeActionReturnGate?.countDown()
                nativeActionReturnGate = null
                nativeReturnProbe?.dispose()
                nativeReturnProbe = null
                visible = false
            }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun nativeDismissal(action: () -> Unit) = runBlocking {
        // Android input/IME use wall time; our return spring uses the paused Compose clock.
        // Idle waits can span native frames, so let them advance the spring during this phase.
        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = true
        try { supervisorScope {
            val input = async(Dispatchers.IO) { action() }
            var failure: Throwable? = null
            try { closed() } catch (caught: Throwable) { failure = caught }
            finally {
                compose.runOnUiThread { nativeActionReturnGate?.countDown() }
                try { input.await() } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
            failure?.let { throw it }
        } } finally { compose.mainClock.autoAdvance = previousAutoAdvance }
    }

    @Test fun nativeInputCompletionDoesNotStarveTheFirstPlacedReturnFrame() =
        exerciseDismissal(outside = true, awaitFirstReturn = true)

    @Test fun nativeKeyboardPolicyRemainsOwnedUntilTheEditorWindowRetires() {
        try {
            withDockedKeyboard {
                fixture(true)
                compose.onNodeWithTag("list-search-pill").performTouchInput { click() }
                opened()
                awaitDockedKeyboard()
                opened()
            }
            assertTrue("The measured editor window has not retired yet",
                compose.runOnUiThread { popupView?.isAttachedToWindow == true })
            assertEquals("Keep keyboard policy until the editing window retires", "1",
                shell("settings get secure show_ime_with_hard_keyboard"))
            for (setting in listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale"))
                assertEquals("Keep native animation policy until the editing window retires", 1f,
                    shell("settings get global $setting").toFloat(), 0f)
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun independentOpeningAndEmptyNativeBackHaveOneDirectReturn() = exerciseDismissal(outside = false)
    @Test fun outsidePointerDismissalDoesNotFollowTheKeyboardDown() = exerciseDismissal(outside = true)
    @Test fun reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome() = exerciseDismissal(outside = false, reduced = true)

    @Test fun absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness() {
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

    @Test fun disposingWhileKeyboardPreparationIsPendingRestoresThePill() {
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
            nativeDismissal { pressSystemBack() }
        } finally {
            compose.runOnUiThread { visible = false }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun shell(command: String): String = nativeShell(command)

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

    private fun prepareDockedKeyboard() = prepareNativeDockedKeyboard(compose, keyboardSetup, ::shell)

    private fun withDockedKeyboard(block: () -> Unit) {
        assertEquals("Class policy owns the native editor", "1", shell("settings get secure show_ime_with_hard_keyboard"))
        prepareDockedKeyboard()
        block()
    }

}

package io.github.anastaci1a.localica

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Give native popups the recorded scene contract used by the application host. */
@Composable
private fun RepeatTestScene(content: @Composable () -> Unit) {
    LocalicaTheme {
        val fitEvidence: ((String) -> Unit)? = remember {
            if (androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("repeatFitEvidence") == "true") {
                { message: String -> android.util.Log.i("RepeatFitEvidence", message); Unit }
            } else null
        }
        val source = rememberFrostedBackdrop(Unit, dark = false)
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source,
                LocalRepeatFitEvidence provides fitEvidence,
                content = content)
        }
    }
}

class RepeatOptionsEnvelopeTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val root = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: instrumentation.targetContext.cacheDir
        val directory = java.io.File(root, "repeat386").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { java.io.File(directory, "$name.png").outputStream().use {
            assertTrue("Capture real native pixels", bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    private fun childText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("repeat-section-dialog")))
    private fun open(section: RepeatSection) {
        compose.onNodeWithText(section.title).performScrollTo().performClick()
        compose.onNodeWithTag("repeat-section-dialog").assertIsDisplayed()
    }
    private fun backCloses(tag: String) {
        val started = android.os.SystemClock.uptimeMillis()
        pressSystemBack()
        // GLOBAL_ACTION_BACK acknowledges submission, not completion on the UI thread.
        // Require that this single event actually dismisses the expected native panel.
        compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        android.util.Log.i("RepeatBack", "Single native Back closed $tag after "+
            "${android.os.SystemClock.uptimeMillis() - started}ms")
    }
    private fun closeChild(action: String = "Select") {
        childText(action).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("repeat-section-dialog").fetchSemanticsNodes().isEmpty() }
    }
    private fun choose(section: RepeatSection, label: String) {
        open(section); childText(label).performClick(); closeChild()
    }
    private fun envelope() = listOf("repeat-options-dialog", "repeat-options-body", "repeat-options-tabs")
        .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot } +
        listOf("Cancel", "Apply").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
    private fun assertNoChildScroll() {
        val descendant = hasTestTag("repeat-section-dialog") or hasAnyAncestor(hasTestTag("repeat-section-dialog"))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and descendant).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and descendant).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and descendant).assertCountEquals(0)
    }
    private fun assertUnclipped(text: String) {
        val node = childText(text)
        node.assertIsDisplayed()
        val pane = compose.onNodeWithTag("repeat-section-dialog").getUnclippedBoundsInRoot()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$text fits horizontally", bounds.left >= pane.left && bounds.right <= pane.right)
        assertTrue("$text fits vertically", bounds.top >= pane.top && bounds.bottom <= pane.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        // Merged button/switch nodes retain the text-layout action from their label.
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$text has a real text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals("Read the actual displayed label", text, layout.layoutInput.text.text)
            assertFalse("$text retains every line", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertFalse("$text must not ellipsize", layout.isLineEllipsized(line))
                assertTrue("$text line fits its placed width: "+
                    "left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} size=${layout.size}",
                    layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width)
                assertTrue("$text line fits its placed height", layout.getLineTop(line) >= 0f &&
                    layout.getLineBottom(line) <= layout.size.height)
            }
            assertEquals("$text has no truncated suffix", text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    @Test fun nativeParentEnvelopeStaysFixedAndChildTouchDoesNotScrollOrCommit() {
        var open by mutableStateOf(true)
        var settings by mutableStateOf(QueueSettings())
        compose.setContent { RepeatTestScene {
            if (open) RepeatOptionsDialog(settings, { open = false }, { settings = it(settings) })
        } }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
        val original = envelope()
        capture("simple-parent")
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(original, envelope())
        capture("advanced-parent")
        for (section in RepeatSection.entries) {
            compose.onNodeWithText(section.title).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            open(section)
            val node = compose.onNodeWithTag("repeat-section-dialog")
            val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
            assertTrue("Use the actual native child host", view.parent is DialogWindowProvider)
            assertNoChildScroll()
            compose.onNodeWithText("Repeat modes").assertDoesNotExist()
            capture("child-${section.name}")
            val before = node.getUnclippedBoundsInRoot()
            node.performTouchInput { swipeUp(durationMillis = 120) }
            assertEquals(before, node.getUnclippedBoundsInRoot())
            closeChild("Cancel")
            assertEquals(original, envelope())
            compose.runOnIdle { assertEquals(QueueSettings(), settings) }
        }
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertFalse(open); assertEquals(AdvancedRepeat(), settings.advancedRepeat) }
    }

    @Test fun childSelectStagesEveryFieldAndParentApplyPreservesOtherSettings() {
        var open by mutableStateOf(true)
        var settings by mutableStateOf(QueueSettings())
        var submissions = 0
        var pending: ((QueueSettings) -> QueueSettings)? = null
        compose.setContent { RepeatTestScene {
            if (open) RepeatOptionsDialog(settings, { open = false }, { pending = it; submissions++ })
        } }
        compose.onNodeWithText("Repeat song").performClick()
        compose.onNodeWithText("Advanced").performClick()
        choose(RepeatSection.Song, SongEnd.NextPaused.label)
        open(RepeatSection.Queue)
        childText("Reset current song to the first song").performClick()
        childText("Reshuffle when shuffle is enabled").performClick()
        childText(QueueEnd.Repeat.label).performClick()
        assertNoChildScroll()
        closeChild()
        compose.runOnIdle { assertEquals(0, submissions); assertEquals(QueueSettings(), settings) }
        compose.onNodeWithText("Simple").performClick()
        compose.onNodeWithText("Repeat song").assertIsSelected()
        compose.onNodeWithText("Advanced").performClick()
        open(RepeatSection.Queue)
        childText("Reset current song to the first song").assertIsOn()
        childText("Reshuffle when shuffle is enabled").assertIsOff()
        childText(QueueEnd.Repeat.label).assertIsSelected()
        closeChild("Cancel")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            settings = pending!!(settings.copy(shuffle = true, preloadNext = false))
            assertEquals(1, submissions)
            assertEquals(QueueSettings(shuffle = true, preloadNext = false, repeat = QueueRepeat.All,
                advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused, queueEnd = QueueEnd.Repeat,
                    resetToFirst = true, reshuffle = false)), settings)
        }
    }

    @Test fun nativeChildBackAndCancelDiscardOnlyChildDraftAndParentBackDiscardsAllDrafts() {
        var visible by mutableStateOf(true)
        val initial = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused))
        var settings by mutableStateOf(initial)
        var submissions = 0
        compose.setContent { RepeatTestScene {
            ActionTextButton({ visible = true }) { Text("Open Repeat") }
            if (visible) RepeatOptionsDialog(settings, { visible = false }, { settings = it(settings); submissions++ })
        } }
        choose(RepeatSection.Song, SongEnd.Repeat.label)
        open(RepeatSection.Queue)
        childText(QueueEnd.Repeat.label).performClick()
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-section-dialog"))
        backCloses("repeat-section-dialog")
        compose.onNodeWithTag("repeat-section-dialog").assertDoesNotExist()
        compose.onNodeWithTag("repeat-options-dialog").assertIsDisplayed()
        open(RepeatSection.Queue)
        childText(QueueEnd.Stop.label).assertIsSelected()
        childText("Queue-end options apply after you turn off repeating the current song.").assertIsDisplayed()
        capture("queue-while-song-repeats")
        closeChild("Cancel")
        open(RepeatSection.Song)
        childText(SongEnd.Repeat.label).assertIsSelected()
        childText(SongEnd.Stop.label).performClick()
        closeChild("Cancel")
        open(RepeatSection.Song)
        childText(SongEnd.Repeat.label).assertIsSelected()
        closeChild("Cancel")
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
        backCloses("repeat-options-dialog")
        compose.onNodeWithTag("repeat-options-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submissions); assertEquals(initial, settings) }
        compose.onNodeWithText("Open Repeat").performClick()
        open(RepeatSection.Song)
        childText(SongEnd.NextPaused.label).assertIsSelected()
        closeChild("Cancel")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun nativeNormalMotionReopenDuringExitUsesOnlyFreshParentAndChildDrafts() {
        var visible by mutableStateOf(true)
        val initial = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused))
        var settings by mutableStateOf(initial)
        var submissions = 0
        compose.setContent { RepeatTestScene {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                if (visible) RepeatOptionsDialog(settings, { visible = false }, { settings = it(settings); submissions++ })
            }
        } }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
        val envelope = envelope()
        val first = (compose.onNodeWithTag("repeat-options-dialog").fetchSemanticsNode().root as ViewRootForTest).view
        choose(RepeatSection.Song, SongEnd.Repeat.label)
        choose(RepeatSection.Queue, QueueEnd.Repeat.label)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("repeat-options-dialog").fetchSemanticsNodes().isEmpty()
            }
            compose.runOnUiThread {
                assertTrue(first.isAttachedToWindow)
                val window = (first.parent as DialogWindowProvider).window
                assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                visible = true
            }
            compose.mainClock.autoAdvance = true
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
            compose.waitUntil(5000) { compose.runOnUiThread { !first.isAttachedToWindow } }
            assertEquals(envelope, envelope())
            open(RepeatSection.Song)
            childText(SongEnd.NextPaused.label).assertIsSelected()
            closeChild("Cancel")
            open(RepeatSection.Queue)
            childText(QueueEnd.Stop.label).assertIsSelected()
            closeChild("Cancel")
            compose.onNodeWithText("Simple").performClick()
            compose.onNodeWithText("Repeat song").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.runOnIdle {
                assertEquals(1, submissions)
                assertEquals(initial.copy(repeat = QueueRepeat.One, advancedRepeat = null), settings)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun nativeChildResizeReportsImpossibleHeightWithoutLosingItsDraft() {
        compose.setContent { RepeatTestScene {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                RepeatSectionDialog(RepeatSection.Queue, AdvancedRepeat(), {}, {})
            }
        } }
        val node = compose.onNodeWithTag("repeat-section-dialog")
        compose.awaitExpectedWindowFocus(node)
        childText(QueueEnd.Repeat.label).performClick()
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        val window = (view.parent as DialogWindowProvider).window
        val width = window.attributes.width; val height = window.attributes.height
        val originalHeight = compose.runOnUiThread { view.height }
        try {
            compose.runOnIdle { window.setLayout(width, (220 * view.resources.displayMetrics.density).roundToInt()) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("repeat-section-insufficient-space").fetchSemanticsNodes().isNotEmpty() }
            assertNoChildScroll()
            assertUnclipped("More space needed")
            assertUnclipped("Enlarge the window or reduce text size.")
            assertUnclipped("Cancel")
            childText(QueueEnd.Repeat.label).assertDoesNotExist()
            compose.runOnIdle { window.setLayout(width, height) }
            compose.waitUntil(5000) { compose.runOnUiThread { view.height == originalHeight } }
            childText(QueueEnd.Repeat.label).assertIsSelected()
            assertNoChildScroll()
        } finally { compose.runOnIdle { window.setLayout(width, height) } }
    }

    @Test fun compactParentEnvelopeSurvivesModeFontAndWindowChangesWithReachableReset() {
        var width by mutableStateOf(360.dp); var height by mutableStateOf(640.dp)
        var font by mutableFloatStateOf(1f); var advanced by mutableStateOf(false); var generation by mutableIntStateOf(0)
        compose.setContent { RepeatTestScene {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font),
                LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(width).height(height)) {
                    key(generation) { RepeatOptionsDialog(QueueSettings(advancedRepeat = if (advanced) AdvancedRepeat() else null), {}, {}) }
                }
            }
        } }
        for ((w, h, scale) in listOf(Triple(360.dp, 640.dp, 1f), Triple(400.dp, 240.dp, 1.5f), Triple(220.dp, 320.dp, 2f))) {
            var size: androidx.compose.ui.unit.IntSize? = null
            for (initialMode in listOf(false, true)) {
                compose.runOnIdle { width = w; height = h; font = scale; advanced = initialMode; generation++ }
                val dialog = compose.onNodeWithTag("repeat-options-dialog")
                if (size == null) size = dialog.fetchSemanticsNode().size else assertEquals(size, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Advanced").performScrollTo().performClick()
                compose.onNodeWithText(RepeatSection.Song.title).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(RepeatSection.Queue.title).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Reset").performScrollTo().performClick()
                compose.onNodeWithText("Simple").performScrollTo().performClick()
                compose.onNodeWithText("Play next song").performScrollTo().assertIsSelected()
                compose.onNodeWithText("Apply").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
                assertEquals(size, dialog.fetchSemanticsNode().size)
            }
        }
    }

    /** Resize the actual native child window; no inline host or fabricated density. */
    private fun resizeNativeChild(width: Dp, height: Dp) {
        val node = compose.onNodeWithTag("repeat-section-dialog")
        compose.awaitExpectedWindowFocus(node)
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        assertTrue(view.parent is DialogWindowProvider)
        val window = (view.parent as DialogWindowProvider).window
        val density = view.resources.displayMetrics.density
        val widthPx = (width.value * density).roundToInt()
        val heightPx = (height.value * density).roundToInt()
        compose.runOnIdle { window.setLayout(widthPx, heightPx) }
        compose.waitUntil(5000) {
            compose.runOnUiThread { view.width == widthPx && view.height == heightPx }
        }
        compose.runOnUiThread {
            assertEquals("Actual native width", widthPx, window.decorView.width)
            assertEquals("Actual native height; configure a display tall enough for this test", heightPx, window.decorView.height)
            val origin = IntArray(2).also(view::getLocationOnScreen)
            @Suppress("DEPRECATION")
            val display = android.util.DisplayMetrics().also {
                view.context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
            }
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            val safeInsets = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val context = "origin=${origin.contentToString()} requested=${widthPx}x${heightPx} " +
                "decor=${window.decorView.width}x${window.decorView.height} view=${view.width}x${view.height} " +
                "physical=${display.widthPixels}x${display.heightPixels} safeInsets=$safeInsets " +
                "imeInsets=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}"
            android.util.Log.i("RepeatNativeWindow", context)
            assertTrue("Native window is wholly on screen; $context", origin[0] >= 0 && origin[1] >= 0 &&
                origin[0] + widthPx <= display.widthPixels && origin[1] + heightPx <= display.heightPixels)
        }
    }

    private fun explicitlySelected(method: String): Boolean =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("class").orEmpty()
            .split(',').any { it.substringAfter('#', "") == method }

    /** Lead-configured profiles are separate focused runs, like APK compatibility phases. */
    private fun physicalPhoneFont2Sections(width: Dp, height: Dp) {
        val dimensions = "${width.value.toInt()}x${height.value.toInt()}"
        val method = "native${dimensions}PhoneFont2SectionsFitSafeInsetsWithoutWindowResize"
        val profile = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("repeatPhoneProfile")
        org.junit.Assume.assumeTrue("Requires the lead-configured $dimensions phone/font2 focused run",
            explicitlySelected(method) || profile == dimensions)
        nativeFont2Sections(width, height, resizeWindow = false)
    }

    @Suppress("DEPRECATION")
    private fun requireExactWindowDisplay(width: Dp, height: Dp) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val display = android.util.DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(display)
        val fits = display.widthPixels / display.density >= width.value && display.heightPixels / display.density >= height.value
        val method = "native${width.value.toInt()}x${height.value.toInt()}Font2SectionsExposeEveryControlAndStageDrafts"
        val actualFont = context.resources.configuration.fontScale
        if (explicitlySelected(method)) {
            assertTrue("Configure a display large enough for this exact native window", fits)
            assertEquals("Configure Android's actual font scale for a native dialog", 2f, actualFont, 0f)
        } else org.junit.Assume.assumeTrue("Exact native window profile requires sufficient display space and actual Android font scale 2", fits && actualFont == 2f)
    }

    @Suppress("DEPRECATION")
    private fun assertPhysicalPhone(width: Dp, height: Dp) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val display = android.util.DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(display)
        assertEquals("Configure this method's actual phone width", width.value, display.widthPixels / display.density, .5f)
        assertEquals("Configure this method's actual phone height including system bars", height.value,
            display.heightPixels / display.density, .5f)
        assertEquals("Use Android's actual font scale, not a fabricated density", 2f, context.resources.configuration.fontScale, 0f)
    }

    private fun assertProductionChildSafeBounds(width: Dp, height: Dp) {
        assertPhysicalPhone(width, height)
        val node = compose.onNodeWithTag("repeat-section-dialog")
        compose.awaitExpectedWindowFocus(node)
        val semantics = node.fetchSemanticsNode()
        val view = (semantics.root as ViewRootForTest).view
        assertTrue("Use the ordinary native child host", view.parent is DialogWindowProvider)
        val window = (view.parent as DialogWindowProvider).window
        compose.runOnUiThread {
            val density = view.resources.displayMetrics.density
            assertEquals("Production window stays within this phone", width.value, window.decorView.width / density, .5f)
            assertEquals("Production height includes this phone's system bars", height.value, window.decorView.height / density, .5f)
            val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view))
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            assertTrue("Phone case retains real status/navigation or gesture insets", insets.top > 0 && insets.bottom > 0)
            val margin = 12f * density // The production centered popup's shared outer margin.
            val panel = semantics.boundsInRoot
            assertTrue("Panel clears the actual left inset and shared margin", panel.left >= insets.left + margin - 1f)
            assertTrue("Panel clears the actual right inset and shared margin", panel.right <= view.width - insets.right - margin + 1f)
            assertTrue("Panel clears the actual top inset and shared margin", panel.top >= insets.top + margin - 1f)
            assertTrue("Panel clears the actual bottom inset and shared margin", panel.bottom <= view.height - insets.bottom - margin + 1f)
        }
    }

    private fun assertNativeControls(section: RepeatSection, fontScale: Float) {
        compose.onNodeWithTag("repeat-section-insufficient-space").assertDoesNotExist()
        assertNoChildScroll()
        val choices = if (section == RepeatSection.Song) SongEnd.entries.map { it.label } else QueueEnd.entries.map { it.label }
        val toggles = if (section == RepeatSection.Queue) listOf("Reset current song to the first song",
            "Reshuffle when shuffle is enabled") else emptyList()
        val warning = if (section == RepeatSection.Queue) listOf(
            "Queue-end options apply after you turn off repeating the current song.") else emptyList()
        val labels = listOf(section.title) + toggles + choices + warning + listOf("Cancel", "Select")
        labels.forEach { label ->
            assertUnclipped(label)
            val layouts = mutableListOf<TextLayoutResult>()
            childText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { assertEquals("Preserve system font scaling", fontScale, it.layoutInput.density.fontScale, 0f) }
        }
        val bounds = labels.map { childText(it).getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b ->
            assertTrue("Distinct labels and actions never overlap", a.right <= b.left || b.right <= a.left ||
                a.bottom <= b.top || b.bottom <= a.top)
        } }
        (toggles + choices + listOf("Cancel", "Select")).forEach { label ->
            val node = childText(label).fetchSemanticsNode()
            val target = node.boundsInRoot
            val nativeDensity = (node.root as ViewRootForTest).view.resources.displayMetrics.density
            // Compare physical extents before converting edges separately to dp;
            // a 144px target at density 3 must not lose height by cancellation.
            val minimum = 48f * nativeDensity
            assertTrue("$label keeps a 48dp touch target; actual=$target density=$nativeDensity",
                target.width >= minimum && target.height >= minimum)
            childText(label).assertHasClickAction()
        }
        choices.forEach { childText(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)) }
        toggles.forEach { childText(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)) }
    }

    private fun nativeFont2Sections(width: Dp, height: Dp, resizeWindow: Boolean = true) {
        if (resizeWindow) requireExactWindowDisplay(width, height) else assertPhysicalPhone(width, height)
        val initial = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Repeat))
        var settings by mutableStateOf(initial)
        var submissions = 0
        var visible by mutableStateOf(true)
        compose.setContent { RepeatTestScene {
            // Native child windows derive density/font scaling from Android resources.
            CompositionLocalProvider(LocalReducedMotion provides true) {
                if (visible) RepeatOptionsDialog(settings, { visible = false }, { settings = it(settings); submissions++ })
            }
        } }
        fun openNative(section: RepeatSection) {
            open(section)
            if (resizeWindow) resizeNativeChild(width, height) else assertProductionChildSafeBounds(width, height)
        }
        openNative(RepeatSection.Song)
        capture("native-${width.value.toInt()}x${height.value.toInt()}-font2-song-$resizeWindow")
        assertNativeControls(RepeatSection.Song, 2f)
        SongEnd.entries.forEach { childText(it.label).performClick(); childText(it.label).assertIsSelected() }
        childText(SongEnd.NextPaused.label).performClick()
        closeChild("Cancel")
        openNative(RepeatSection.Song)
        childText(SongEnd.Repeat.label).assertIsSelected()
        childText(SongEnd.Stop.label).performClick()
        backCloses("repeat-section-dialog")
        openNative(RepeatSection.Song)
        childText(SongEnd.Repeat.label).assertIsSelected()
        childText(SongEnd.NextPaused.label).performClick()
        closeChild()
        openNative(RepeatSection.Song)
        childText(SongEnd.NextPaused.label).assertIsSelected()
        childText(SongEnd.Repeat.label).performClick()
        closeChild()
        openNative(RepeatSection.Queue)
        capture("native-${width.value.toInt()}x${height.value.toInt()}-font2-queue-warning-$resizeWindow")
        assertNativeControls(RepeatSection.Queue, 2f)
        QueueEnd.entries.forEach { childText(it.label).performClick(); childText(it.label).assertIsSelected() }
        childText("Reset current song to the first song").performClick()
        childText("Reshuffle when shuffle is enabled").performClick()
        closeChild("Cancel")
        openNative(RepeatSection.Queue)
        childText(QueueEnd.Stop.label).assertIsSelected()
        childText("Reset current song to the first song").assertIsOff()
        childText("Reshuffle when shuffle is enabled").assertIsOn()
        childText(QueueEnd.NextQueue.label).performClick()
        backCloses("repeat-section-dialog")
        openNative(RepeatSection.Queue)
        childText(QueueEnd.Stop.label).assertIsSelected()
        childText(QueueEnd.Repeat.label).performClick()
        childText("Reset current song to the first song").performClick()
        childText("Reshuffle when shuffle is enabled").performClick()
        closeChild()
        compose.runOnIdle { assertEquals(0, submissions); assertEquals(initial, settings) }
        openNative(RepeatSection.Queue)
        childText(QueueEnd.Repeat.label).assertIsSelected()
        childText("Reset current song to the first song").assertIsOn()
        childText("Reshuffle when shuffle is enabled").assertIsOff()
        assertNativeControls(RepeatSection.Queue, 2f)
        closeChild("Cancel")
        compose.onNodeWithText("Apply").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(visible)
            assertEquals(1, submissions)
            assertEquals(initial.copy(repeat = QueueRepeat.One, advancedRepeat = AdvancedRepeat(
                songEnd = SongEnd.Repeat, queueEnd = QueueEnd.Repeat, resetToFirst = true, reshuffle = false)), settings)
        }
    }

    @Test fun native360x640Font2SectionsExposeEveryControlAndStageDrafts() = nativeFont2Sections(360.dp, 640.dp)

    @Test fun native400x800Font2SectionsExposeEveryControlAndStageDrafts() = nativeFont2Sections(400.dp, 800.dp)

    @Test fun native360x640PhoneFont2SectionsFitSafeInsetsWithoutWindowResize() =
        physicalPhoneFont2Sections(360.dp, 640.dp)

    @Test fun native400x800PhoneFont2SectionsFitSafeInsetsWithoutWindowResize() =
        physicalPhoneFont2Sections(400.dp, 800.dp)

    @Test fun shortLargeFontChildrenRefuseClippingAndLeaveSettingsUntouched() {
        var section by mutableStateOf(RepeatSection.Song); var generation by mutableIntStateOf(0)
        var submissions = 0
        compose.setContent { RepeatTestScene {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(400.dp).height(240.dp)) {
                    key(generation) { RepeatSectionDialog(section, AdvancedRepeat(), {}, { submissions++ }) }
                }
            }
        } }
        for (target in RepeatSection.entries) {
            compose.runOnIdle { section = target; generation++ }
            assertNoChildScroll()
            compose.onNodeWithTag("repeat-section-insufficient-space").assertIsDisplayed()
            assertUnclipped("More space needed"); assertUnclipped("Enlarge the window or reduce text size."); assertUnclipped("Cancel")
            childText("Select").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, submissions) }
        }
    }
    @Test fun resetClearsBothModeDraftsButDoesNotSubmitUntilApply() {
        var settings by mutableStateOf(QueueSettings())
        var submissions = 0
        compose.setContent { RepeatTestScene {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                RepeatOptionsDialog(settings, {}, { settings = it(settings); submissions++ })
            }
        } }
        compose.onNodeWithText("Repeat song").performClick()
        compose.onNodeWithText("Advanced").performClick()
        choose(RepeatSection.Song, SongEnd.Stop.label)
        open(RepeatSection.Queue)
        childText("Reset current song to the first song").performClick()
        childText("Reshuffle when shuffle is enabled").performClick()
        childText(QueueEnd.Repeat.label).performClick()
        closeChild()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Simple").performClick()
        compose.onNodeWithText("Play next song").assertIsSelected()
        compose.onNodeWithText("Advanced").performClick()
        open(RepeatSection.Song); childText(SongEnd.Next.label).assertIsSelected(); closeChild("Cancel")
        open(RepeatSection.Queue)
        childText(QueueEnd.Stop.label).assertIsSelected()
        childText("Reset current song to the first song").assertIsOff()
        childText("Reshuffle when shuffle is enabled").assertIsOn()
        closeChild("Cancel")
        compose.runOnIdle { assertEquals(0, submissions); assertEquals(QueueSettings(), settings) }
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(1, submissions); assertEquals(AdvancedRepeat(), settings.advancedRepeat) }
    }

    @Test fun nativeOpeningShowsMovingSurfaceBeforeMakingControlsInteractive() {
        var visible by mutableStateOf(false)
        lateinit var dialogs: GlassDialogs
        compose.setContent { RepeatTestScene {
            dialogs = checkNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides false) {
                ActionTextButton({ visible = true }) { Text("Open Repeat") }
                if (visible) RepeatOptionsDialog(QueueSettings(), { visible = false }, {})
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("Open Repeat").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { dialogs.entries.isNotEmpty() && dialogs.entries.single().anchor.contact.expansion.value > 0f }
            }
            val progress = compose.runOnUiThread { dialogs.entries.single().anchor.contact.expansion.value }
            assertTrue("Observe an actual moving opening frame", progress > 0f && progress < .85f)
            compose.onNodeWithTag("glass-morph-surface").assertExists()
            compose.onNodeWithText("Apply").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Repeat modes").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        } finally { compose.mainClock.autoAdvance = true }
    }

}

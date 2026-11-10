package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Actual scoped popup, native IME and measured return geometry. */
class SearchReturnTopTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val name = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val songs = (0..79).map { Track("return-search:$it", "Song ${it.toString().padStart(3, '0')}",
        album = "Return album", artist = "Return artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val route = BrowseRoute(MainTab.Songs, key = "return-search-690")
    private val originalStyles = linkedMapOf<BrowseRoute, Pair<BrowseStyle, Boolean>>()
    @After fun restorePreferences() {
        val directory = nativeEvidenceDirectory("search-return-top", instrumentation.targetContext.getExternalFilesDir(null))
        java.io.File(directory, "last-frame-${name.methodName}-api${android.os.Build.VERSION.SDK_INT}.txt")
            .writeText("${scrollProbe.frame}\n")
        val prefs = BrowsePreferences(instrumentation.targetContext)
        originalStyles.forEach { (route, saved) -> prefs.write(route, saved.first, saved.second) }
    }
    private var foldersEmpty by mutableStateOf(false)
    private var queueState by mutableStateOf(QueueUiState(loading = false))
    private val scrollProbe = BrowseScrollFrameProbe()
    private var sample: SearchMorphSample? = null
    private var rtl by mutableStateOf(false)
    private var currentRoute by mutableStateOf(route)
    private var libraryProblem by mutableStateOf<LibraryProblem?>(null)
    private val list get() = compose.onNodeWithTag("browser-list")
    private val arrow get() = compose.onNodeWithContentDescription("Return to top")

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun fixture(popup: Boolean) {
        val prefs = BrowsePreferences(instrumentation.targetContext)
        for (target in listOf(route, currentRoute).distinct()) {
            originalStyles[target] = prefs.read(target) to prefs.overridden(target)
            prefs.write(target, BrowseStyle(quickSearch = popup), false)
        }
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSearchMorphObserver provides { sample = it },
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalBrowseScrollFrameProbe provides scrollProbe) {
                // Every host mounts a route under key(route.key): a route change is a fresh grid.
                key(currentRoute.key) { BrowserScreen(LibraryUiState(LibrarySnapshot(
                    folders = if (foldersEmpty) emptyList() else listOf(FolderSelection("return-search-root", "Music")), tracks = songs), loading = false, problem = libraryProblem),
                    PlaylistState(loading = false), currentRoute, {}, {}, {}, { _, _, _ -> }, null, null, true, false, queueState = queueState) }
            }
        } }
        val first = queueState.library.queues.firstOrNull { it.id == currentRoute.collection?.id }?.entries?.firstOrNull()?.id ?: "return-search:0"
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-$first").fetchSemanticsNodes().isNotEmpty() }
        if (popup) {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("list-search-popup"))
            compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasSetTextAction()).performTextReplacement("Song")
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-return-search:1").fetchSemanticsNodes().size == 1 &&
                    compose.runOnUiThread { sample?.let { it.ime > 0 && it.nativeIme > 0 && it.ime == it.imeTarget } == true }
            }
        }
        compose.waitForIdle()
    }

    private fun hiddenIme() {
        compose.awaitSearchImeHandoff { compose.onNode(hasSetTextAction()).performImeAction() }
        compose.waitUntil(5000) { compose.runOnUiThread { sample?.let { it.ime == 0 && it.nativeIme == 0 } == true } }
        compose.waitForIdle()
    }

    private fun withKeyboard(block: () -> Unit) {
        val original = shell("settings get secure show_ime_with_hard_keyboard")
        val scales = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
            .associateWith { shell("settings get global $it") }
        try {
            scales.keys.forEach { shell("settings put global $it 1") }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            prepareNativeDockedKeyboard(compose, mutableListOf(), ::shell)
            block()
        } finally {
            if (original == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $original")
            compose.awaitSystemImeHardKeyboardPolicy(::shell, (original.toIntOrNull() ?: 0) != 0)
            scales.forEach { (key, value) ->
                if (value == "null") shell("settings delete global $key") else shell("settings put global $key $value")
            }
        }
    }

    @Test fun popupAtVisualTopNeverOffersReturn() = withKeyboard {
        fixture(true)
        arrow.assertDoesNotExist()
        hiddenIme()
        arrow.assertDoesNotExist()
    }

    @Test fun popupTapReachesTopAndRetiresReturnDrawing() = withKeyboard {
        fixture(true); hiddenIme()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
        arrow.assertDoesNotExist()
        compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
    }

    @Test fun popupTapRetainsItsDrawingUntilTopThenUsesNormalExit() = withKeyboard {
        fixture(true); hiddenIme()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            arrow.performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("floating-return-control", true).assertExists()
            arrow.assertExists()
            var frames = 0
            while (compose.onAllNodesWithContentDescription("Return to top").fetchSemanticsNodes().isNotEmpty() && frames++ < 100) {
                compose.mainClock.advanceTimeByFrame()
            }
            arrow.assertDoesNotExist()
            compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
            // Logical dismissal revokes input while the normal fade/rise still draws.
            compose.onNodeWithTag("floating-return-control", true).assertExists()
            compose.mainClock.advanceTimeBy(450)
            compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun popupManualReturnRetiresTheArrow() = withKeyboard {
        fixture(true); hiddenIme()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed()
        list.performScrollToIndex(0)
        arrow.assertDoesNotExist()
        compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
    }

    @Test fun popupReturnHasBottomGapWithNativeIme() = withKeyboard {
        fixture(true)
        assertGap(true)
    }

    @Test fun popupReturnHasBottomGapWithoutNativeIme() = withKeyboard {
        fixture(true); hiddenIme()
        assertGap(false)
    }

    private fun assertGap(ime: Boolean) {
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed()
        val popup = compose.onNodeWithTag("list-search-popup").fetchSemanticsNode().boundsInRoot
        val button = arrow.fetchSemanticsNode().boundsInRoot
        val expected = with(compose.density) { FloatingSurfaceGap.toPx() }
        assertEquals("Inset is the shared floating surface gap; IME=$ime", expected, popup.bottom - button.bottom, 1f)
        val current = compose.runOnUiThread { requireNotNull(sample) }
        assertEquals("Native popup owns the expected keyboard state", ime, current.nativeIme > 0)
        if (ime) {
            val keyboard = instrumentation.uiAutomation.windows.single { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val bounds = android.graphics.Rect().also(keyboard::getBoundsInScreen)
            assertTrue("Popup ends above the actual keyboard: $current / $bounds", current.bounds.bottom <= bounds.top + 1f)
        }
    }

    @Test fun noticeMeasurementTracksNonzeroContentAndNewRouteOwnership() {
        fixture(false)
        // Initial UNKNOWN must become a measured zero before an arrow is offered.
        arrow.assertDoesNotExist()
        compose.runOnIdle { libraryProblem = LibraryProblem.LoadFailed }
        compose.waitForIdle()
        list.performScrollToIndex(1)
        // The notice now has real height: item 1/offset 0 is away from home.
        arrow.assertIsDisplayed()
        list.performScrollToIndex(0)
        arrow.assertDoesNotExist()
        compose.runOnIdle {
            currentRoute = route.copy(key = "return-search-690-new-owner")
            libraryProblem = null
        }
        compose.waitForIdle()
        arrow.assertDoesNotExist()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed().performClick()
        arrow.assertDoesNotExist()
        compose.runOnIdle { libraryProblem = LibraryProblem.ScanFailed }
        list.performScrollToIndex(1)
        arrow.assertIsDisplayed()
        compose.runOnIdle { libraryProblem = null }
        compose.waitForIdle()
        arrow.assertDoesNotExist()
    }

    @Test fun rtlPopupHidesAtTopAfterTapAndKeepsItsNativeImeGap() = withKeyboard {
        rtl = true
        fixture(true)
        arrow.assertDoesNotExist()
        assertGap(true)
        arrow.performClick()
        arrow.assertDoesNotExist()
        hiddenIme()
        assertGap(false)
        arrow.performClick()
        arrow.assertDoesNotExist()
    }

    @Test fun headerAndBrowserExtentsSurviveReturnNewOwnerAndRtl() {
        libraryProblem = LibraryProblem.LoadFailed
        fixture(false)
        val evidence = mutableListOf<String>()
        fun bounds(): Pair<Rect, Rect> {
            val browser = list.fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("browse-full-span", true).fetchSemanticsNode().boundsInRoot
            val serial = scrollProbe.serial
            compose.runOnIdle { scrollProbe.request++ }
            compose.waitUntil(5000) { scrollProbe.serial > serial }
            val frame = requireNotNull(scrollProbe.frame)
            assertEquals("Receipt width equals the actual grid viewport", frame.viewportWidth, frame.noticeWidth)
            assertEquals("Required notice width equals that viewport", frame.viewportWidth.toFloat(), header.width, 0f)
            evidence += "rtl=$rtl route=$currentRoute browser=$browser header=$header frame=$frame"
            return browser to header
        }
        fun unchanged(expected: Pair<Rect, Rect>) {
            val actual = bounds()
            for ((before, after) in listOf(expected.first to actual.first, expected.second to actual.second)) {
                assertEquals("Pass-through measurement preserves width", before.width, after.width, 1f)
                assertEquals("Pass-through measurement preserves height", before.height, after.height, 1f)
                assertEquals("Return preserves top", before.top, after.top, 1f)
            }
        }
        val before = bounds()
        assertTrue("The test must measure a real notice", before.second.height > 0f)
        try {
            list.performScrollToIndex(50)
            arrow.performClick()
            arrow.assertDoesNotExist()
            unchanged(before)
            compose.runOnIdle { currentRoute = route.copy(key = "return-search-extents-new-owner") }
            compose.waitForIdle()
            unchanged(before)
            compose.runOnIdle { rtl = true }
            compose.waitForIdle()
            unchanged(before)
            list.performScrollToIndex(50)
            arrow.performClick()
            unchanged(before)
        } finally {
            val directory = nativeEvidenceDirectory("search-return-top", instrumentation.targetContext.getExternalFilesDir(null))
            java.io.File(directory, "measured-extents-api${android.os.Build.VERSION.SDK_INT}.txt")
                .writeText(evidence.joinToString("\n", postfix = "\n"))
        }
    }

    @Test fun queueAndSongRoutesMeasureTheActualAddFoldersVisibility() {
        val queue = QueueEdits.create(songs, 0, "Notice route")
        queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val queueRoute = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), key = "notice-queue")
        currentRoute = queueRoute
        foldersEmpty = true
        fixture(false)
        arrow.assertDoesNotExist()
        compose.onNodeWithText("Add music folders").assertDoesNotExist()
        repeat(2) {
            compose.runOnIdle { currentRoute = route }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Add music folders").fetchSemanticsNodes().isNotEmpty() }
            list.performScrollToIndex(1)
            arrow.assertIsDisplayed()
            compose.runOnIdle { currentRoute = queueRoute }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-" + queue.entries.first().id).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            arrow.assertDoesNotExist()
            compose.onNodeWithText("Add music folders").assertDoesNotExist()
        }
    }

    @Test fun quickSearchPillShownAtTheTopRevealsItsHeaderOnItsFirstPresentedFrame() {
        fixture(false)
        val evidence = mutableListOf<String>()
        fun frame(): BrowseScrollFrame {
            val serial = scrollProbe.serial
            compose.runOnIdle { scrollProbe.request++ }
            compose.waitUntil(5000) { scrollProbe.serial > serial }
            return requireNotNull(scrollProbe.frame)
        }
        val prefs = BrowsePreferences(instrumentation.targetContext)
        // A bounded recorder of every grid draw, installed before the preference write. Samples are
        // taken on the draw itself (animation-clock frame time plus traversal serial, the composed
        // pill mode, header placement, grid anchor). The probe's pill-mode state is read at draw, so
        // the mode flip invalidates one diagnostic draw of the retained grid in that same frame even
        // when its placed items did not change; the test thread's waits only decide when recording
        // stops. Draw-only invalidation: nothing here measures, scrolls or awaits frames.
        fun pill(on: Boolean, label: String): List<BrowseScrollFrame> {
            val frames = java.util.Collections.synchronizedList(mutableListOf<BrowseScrollFrame>())
            scrollProbe.onFrame = { if (frames.size < 240) frames += it }
            try {
                compose.runOnUiThread { prefs.write(route, BrowseStyle(quickSearch = on), false) }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-pill").fetchSemanticsNodes().isNotEmpty() == on }
                repeat(3) { frame() }
            } finally { scrollProbe.onFrame = null }
            val strip = synchronized(frames) { frames.toList() }
            evidence += strip.map { "$label $it" }
            assertTrue("The strip is consecutive: $strip", strip.zipWithNext().all { (a, b) -> b.serial == a.serial + 1 })
            assertTrue("Every sample carries its frame time: $strip", strip.all { it.frameTime > 0 })
            assertTrue("The strip reaches the new mode: $strip", strip.any { it.searchPill == on })
            return strip
        }
        fun canonicalTop(frame: BrowseScrollFrame) {
            assertEquals(1, frame.index); assertEquals(0, frame.offset); assertNull(frame.headerHeight)
            assertEquals("The zero notice is measured while pinned", frame.viewportWidth, frame.noticeWidth)
        }
        try {
            arrow.assertDoesNotExist()
            val pinned = frame()
            canonicalTop(pinned)
            // Shown at the visual top, the header is visible on the first frame that renders the pill.
            val shown = pill(true, "top-on")
            val first = shown.first { it.searchPill }
            assertEquals("The first presented pill frame is anchored at the header: $first", 0, first.index)
            assertEquals(0, first.offset)
            assertEquals("The shown header is visible from its first frame: $first", 0, requireNotNull(first.headerTop))
            assertTrue(requireNotNull(first.headerHeight) > 0)
            shown.filter { it.searchPill }.forEach {
                assertTrue("No pill frame hides the shown header above the top: $it", it.headerHeight != null && it.headerTop == 0)
            }
            arrow.assertDoesNotExist()
            val revealed = frame()
            assertEquals(0, revealed.index); assertNull("No receipt while the notice is not measured", revealed.noticeWidth)
            // Hidden again, the zero notice is pinned and measured at the canonical top.
            pill(false, "top-off")
            arrow.assertDoesNotExist()
            val restored = frame()
            canonicalTop(restored)
            assertEquals("Row bounds are unchanged by the pinned notice", pinned.rowHeight, restored.rowHeight)
            // A scrolled list keeps its place in every frame while the header grows above it.
            list.performScrollToIndex(50)
            arrow.assertIsDisplayed()
            val scrolled = frame()
            pill(true, "scrolled-on").forEach {
                assertEquals("A scrolled list keeps its index: $it", scrolled.index, it.index)
                assertEquals("A scrolled list keeps its offset: $it", scrolled.offset, it.offset)
            }
            arrow.assertIsDisplayed().performClick()
            compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
            arrow.assertDoesNotExist()
            val top = frame()
            assertEquals(0, top.index); assertTrue(requireNotNull(top.headerHeight) > 0)
            pill(false, "top-off-again")
            arrow.assertDoesNotExist()
            canonicalTop(frame())
            list.performScrollToIndex(50)
            arrow.assertIsDisplayed().performClick()
            compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
            arrow.assertDoesNotExist()
        } finally {
            scrollProbe.onFrame = null
            val directory = nativeEvidenceDirectory("search-return-top", instrumentation.targetContext.getExternalFilesDir(null))
            java.io.File(directory, "search-header-reveal-strip-api${android.os.Build.VERSION.SDK_INT}.txt")
                .writeText(evidence.joinToString("\n", postfix = "\n"))
        }
    }

    @Test fun browseWithEmptyNoticeHidesAtTopAndAfterTap() {
        fixture(false)
        arrow.assertDoesNotExist()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed().performClick()
        compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
        arrow.assertDoesNotExist()
        list.performScrollToIndex(50)
        list.performScrollToIndex(0)
        arrow.assertDoesNotExist()
    }
}

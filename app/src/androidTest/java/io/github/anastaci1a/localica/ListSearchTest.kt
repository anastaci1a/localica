package io.github.anastaci1a.localica

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import kotlinx.coroutines.Dispatchers
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val watchdog = BrowseSchedulerWatchdog.rule()
    private var lastRoot = ""
    private var lastMorph = ""

    @Test fun searchSlotUsesPlacedTravelThroughNativeReadinessAndInterruptedReturn() = slotFrames(false)
    @Test fun reducedSearchSlotUsesTheSameNativePlacementEndpoints() = slotFrames(true)

    private data class SlotFrame(val sample: SearchMorphSample, val rowTop: Float)

    private fun slotFrames(reduced: Boolean) = withDockedSlotKeyboard {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs, key = "search-slot-frames")
        val preferences = BrowsePreferences(context)
        val previous = preferences.read(route)
        preferences.write(route, BrowseStyle(quickSearch = true), false)
        val frames = mutableListOf<SlotFrame>()
        var row: LayoutCoordinates? = null
        var slot = 0f
        val tracks = (0..20).map { Track("slot:$it", "Song ${it.toString().padStart(3, '0')}",
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        try {
            compose.setContent { LocalicaTheme {
                val density = LocalDensity.current
                val slotHeight = listPillSlotHeight()
                val snapshot = remember { LibrarySnapshot(folders = listOf(FolderSelection("slot-root", "Music")), tracks = tracks) }
                SideEffect { slot = with(density) { slotHeight.toPx() } }
                CompositionLocalProvider(LocalReducedMotion provides reduced,
                    LocalSearchMorphObserver provides { sample ->
                        row?.takeIf { it.isAttached }?.let { coordinates ->
                            frames += SlotFrame(sample, coordinates.positionInRoot().y)
                        }
                    }) {
                    Box(Modifier.fillMaxSize()) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, {}, {},
                            { _, _, _ -> }, null, null, true, false, reducedMotion = reduced,
                            queryDispatcher = Dispatchers.Unconfined)
                    }
                }
            } }
            val song = compose.onNodeWithText("Song 000")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 000").fetchSemanticsNodes().isNotEmpty() }
            val baseline = song.fetchSemanticsNode().boundsInRoot.top
            row = song.fetchSemanticsNode().layoutInfo.coordinates
            compose.mainClock.autoAdvance = false
            fun pumpUntil(predicate: () -> Boolean) = compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread(predicate)
            }
            fun last() = frames.lastOrNull()?.sample
            fun open() {
                compose.onNodeWithTag("list-search-pill").performTouchInput { click(center) }
                compose.waitUntil(5000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1
                }
            }
            fun beginReturn() {
                // Native Back first dismisses the keyboard, then the actual popup.
                compose.awaitSearchImeHandoff { pressSystemBack() }
                compose.awaitExpectedWindowFocus(compose.onNodeWithTag("list-search-popup"))
                pressSystemBack()
            }
            open()
            pumpUntil { last()?.let { it.opening && it.placementBottom != null &&
                if (reduced) it.slotFraction == 1f else it.slotFraction in .1f.. .8f } == true }
            val owned = compose.runOnUiThread { requireNotNull(last()) }
            assertTrue("Placement must use this popup's actual focused native IME: $owned", owned.windowFocused && owned.ime > 0 && owned.nativeIme > 0)
            beginReturn()
            if (!reduced) {
                pumpUntil { last()?.let { !it.opening && it.slotFraction > .01f && it.slotFraction < owned.slotFraction } == true }
                // A fresh contact during the existing return must retain the original vacancy.
                open()
            } else {
                compose.waitUntil(5000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty()
                }
                open()
            }
            pumpUntil { last()?.let { it.opening && it.progress >= .999f && it.slotFraction == 1f } == true }
            beginReturn()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty()
            }
            assertEquals("Final restoration keeps the exact original row position", baseline, song.fetchSemanticsNode().boundsInRoot.top, 1.1f)
            val observed = compose.runOnUiThread { frames.toList() }
            assertTrue("Observe real frames before native placement ownership", observed.any { it.sample.opening && it.sample.placementBottom == null && it.sample.flight?.opening == true })
            var wasOpening = false
            var heldFraction = 0f
            for ((sample, top) in observed) {
                val flight = sample.flight
                val bottom = sample.placementBottom
                val travel = sample.slotTravel
                if (sample.opening && !wasOpening) heldFraction = sample.slotFraction
                assertEquals("Row and placed popup must use the same vacancy: $sample", baseline - slot * sample.slotFraction, top, 1.1f)
                if (sample.opening && flight?.opening == true && bottom == null)
                    assertEquals("Shape growth preserves vacancy until native placement owns travel", heldFraction, sample.slotFraction, 0f)
                if (sample.opening && bottom != null && travel != null)
                    assertEquals("Use actual bottom travel, not another timer", travel.fraction(bottom), sample.slotFraction, .0001f)
                if (!sample.opening && flight != null)
                    assertEquals("Reverse the actual captured return flight", sample.slotReturnFrom * (1f - flight.fraction(sample.progress)), sample.slotFraction, .0001f)
                if (reduced) assertTrue("Reduced motion has only placement endpoints", sample.slotFraction == 0f || sample.slotFraction == 1f)
                wasOpening = sample.opening
            }
            if (!reduced) {
                assertTrue(observed.any { it.sample.opening && it.sample.slotFraction in .1f.. .9f })
                assertTrue(observed.any { !it.sample.opening && it.sample.slotFraction in .01f.. .99f })
            }
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.write(route, previous, false)
        }
    }
    private fun withDockedSlotKeyboard(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        val key = "show_ime_with_hard_keyboard"
        val original = shell("settings get secure $key")
        check(original == "null" || original.matches(Regex("-?[0-9]+")))
        val scales = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
            .associateWith { shell("settings get global $it") }
        check(scales.values.all { it == "null" || it.toFloatOrNull() != null })
        val setup = mutableListOf<String>()
        try {
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            shell("settings put secure $key 1")
            assertEquals("1", shell("settings get secure $key"))
            scales.keys.forEach { shell("settings put global $it 1"); assertEquals(1f, shell("settings get global $it").toFloat(), 0f) }
            prepareNativeDockedKeyboard(compose, setup, ::shell)
            block()
        } finally {
            setup.forEach { android.util.Log.i("ListSearchSlotSetup", it) }
            if (original == "null") shell("settings delete secure $key") else shell("settings put secure $key $original")
            assertEquals("Restore native keyboard policy", original, shell("settings get secure $key"))
            scales.forEach { (name, value) ->
                if (value == "null") shell("settings delete global $name") else shell("settings put global $name $value")
                assertEquals("Restore native animation scale $name", value, shell("settings get global $name"))
            }
        }
    }
    private fun trace(message: String) {
        android.util.Log.i("ListSearchReopen", "${android.os.SystemClock.uptimeMillis()} $message")
    }

    private fun awaitSearchFocus() {
        // Window focus arrives outside Compose's animation clock. Assert the editor's
        // state directly: a visible IME isn't guaranteed to appear in the accessibility
        // window list. GlassPressureTest separately exercises IME-owned Back via insets.
        var lastCount = -1
        try {
            compose.waitUntil(5000) {
                val count = compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size
                if (count != lastCount) { trace("focusedEditors=$count root=$lastRoot"); lastCount = count }
                count == 1
            }
        } catch (failure: Throwable) {
            runCatching {
                trace("focusTimeout editors=${compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size} popups=${compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().size} root=$lastRoot")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun dismissSearch() {
        val editing = compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        compose.awaitSearchImeHandoff { if (editing) pressSystemBack() }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("list-search-popup"))
        android.util.Log.i("SearchImeHandoff", "${android.os.SystemClock.uptimeMillis()} phase=before popup Back")
        pressSystemBack()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
        android.util.Log.i("SearchImeHandoff", "${android.os.SystemClock.uptimeMillis()} phase=popup removed")
    }

    @Test fun visibleSearchVacancyRestoresWithoutCumulativeListMovement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Albums)))
        val tracks = (0..30).map { n -> Track("vacancy:$n", "Song $n", album = "Album " + n.toString().padStart(3, '0'),
            fileName = "$n.mp3", relativePath = "$n.mp3") }
        try {
            BrowseSchedulerWatchdog.phase("install list search fixture")
            compose.setContent { LocalicaTheme { CompositionLocalProvider(
                LocalTextEditingObservation provides { trace("text $it") },
                LocalSearchEditTrace provides { trace("editor $it") },
                LocalSearchMorphObserver provides { sample ->
                    val value = "opening=${sample.opening} progressStep=${(sample.progress * 10).toInt()} window=${sample.windowFocused} ime=${sample.ime} nativeIme=${sample.nativeIme} source=${sample.imeSource} target=${sample.imeTarget}"
                    if (lastMorph != value) { lastMorph = value; trace("morph $value") }
                }
            ) {
                val root = LocalView.current
                SideEffect {
                    val value = "activityView=${System.identityHashCode(root)} window=${System.identityHashCode(root.windowToken)} focus=${root.hasWindowFocus()} attached=${root.isAttachedToWindow} shown=${root.isShown}"
                    if (lastRoot != value) { lastRoot = value; trace(value) }
                }
                LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } } }
            BrowseSchedulerWatchdog.phase("await initial album")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            repeat(3) { pass ->
                if (pass == 1) compose.onNodeWithTag("browser-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 20f) }
                val before = compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot
                BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
                awaitSearchFocus()
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                if (pass == 0) {
                    pressSystemBack()
                    compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
                    BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
                    awaitSearchFocus()
                }
                compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Album")
                BrowseSchedulerWatchdog.phase("dismiss list search")
            dismissSearch()
                compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
                assertEquals("Restore both full and partially visible pill space", before,
                    compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            context.getSharedPreferences("interface", 0).edit().clear().commit()
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }

    @Test fun popupKeepsScopeAndBothListPositionsWhenOpeningAResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Albums), reducedMotion = true))
        val tracks = (0..99).map { n ->
            val name = n.toString().padStart(3, '0')
            Track("track:$n", "Song $name", album = "Album $name", fileName = "$n.mp3", relativePath = "$n.mp3")
        }
        try {
            BrowseSchedulerWatchdog.phase("install list search fixture")
            compose.setContent { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } }
            BrowseSchedulerWatchdog.phase("await initial album")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            val pill = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot
            assertTrue("First item rests below the floating search pill", compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot.top >= pill.bottom)
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.scrollToUncoveredItem(hasText("Album 060"))
            val before = compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot
            BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
            awaitSearchFocus()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Album")
            BrowseSchedulerWatchdog.phase("submit list search")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { InstrumentationRegistry.getInstrumentation().uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Album 061"))
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(java.io.File(context.getExternalFilesDir(null), "list-search-popup.png"))
            val resultPosition = compose.onNodeWithText("Album 061").fetchSemanticsNode().boundsInRoot
            BrowseSchedulerWatchdog.phase("open search result")
            compose.onNodeWithText("Album 061").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 061").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
            BrowseSchedulerWatchdog.phase("return to search results")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 061").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertTextContains("Album")
            assertEquals(resultPosition, compose.onNodeWithText("Album 061").fetchSemanticsNode().boundsInRoot)
            BrowseSchedulerWatchdog.phase("dismiss list search")
            dismissSearch()
            assertEquals(before, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
            BrowseSchedulerWatchdog.phase("open list search")
            compose.onNodeWithTag("list-search-pill").performClick()
            awaitSearchFocus()
            compose.onNode(hasSetTextAction()).assertIsFocused().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNode(hasSetTextAction()).performTextReplacement("061")
            BrowseSchedulerWatchdog.phase("submit list search")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.waitUntil(5000) { InstrumentationRegistry.getInstrumentation().uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
            assertEquals(before, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
        } finally {
            context.getSharedPreferences("interface", 0).edit().clear().commit()
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }
}

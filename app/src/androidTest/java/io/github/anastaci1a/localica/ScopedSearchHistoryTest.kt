package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScopedSearchHistoryTest {
    @get:Rule val watchdog = BrowseSchedulerWatchdog.rule()
    // Production dispatch queues effects; do not run scroll restoration inline
    // while the pager is subcomposing during a measure pass.
    @get:Rule val compose = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private lateinit var albumKeys: Map<String, String>
    private val editor get() = compose.onNode(hasSetTextAction())

    private fun fixture(test: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = context.getSharedPreferences("interface", 0)
        val browse = context.getSharedPreferences("browse-style", 0)
        settings.edit().clear().commit(); browse.edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Albums), reducedMotion = true))
        val tracks = (0..79).map { n -> Track("history:$n", "Song $n", album = "Album " + n.toString().padStart(3, '0'),
            fileName = "$n.mp3", relativePath = "$n.mp3") }
        val snapshot = LibrarySnapshot(tracks = tracks)
        albumKeys = io.github.anastaci1a.localica.browse.BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Albums))
            .items.associate { it.title to it.key }
        try {
            BrowseSchedulerWatchdog.phase("install scoped history fixture")
            compose.setContent { androidx.compose.runtime.CompositionLocalProvider(LocalSearchEditTrace provides { event -> android.util.Log.i("SearchEditTrace", event) }) { LocalicaTheme { LocalicaScreen(
                LibraryUiState(snapshot, loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            open()
            test()
        } finally { settings.edit().clear().commit(); browse.edit().clear().commit() }
    }

    private fun open() {
        BrowseSchedulerWatchdog.phase("open scoped search and await editor")
        compose.onNodeWithTag("list-search-pill").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
    }


    private fun submit(text: String) {
        BrowseSchedulerWatchdog.phase("submit $text")
        android.util.Log.i("SearchEditTrace", "before click/replacement requestedLength=${text.length}")
        editor.performClick().performTextReplacement(text)
        android.util.Log.i("SearchEditTrace", "after single replacement requestedLength=${text.length}")
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        editor.assertTextEquals(text)
        compose.awaitSearchImeHandoff {
            editor.assertTextEquals(text)
            editor.performImeAction()
        }
        editor.assertTextEquals(text)
    }

    private fun backTo(text: String) {
        BrowseSchedulerWatchdog.phase("native Back to $text")
        compose.awaitExpectedWindowFocus(editor)
        val view = (editor.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        compose.awaitSearchImeHandoff { pressSystemBack() } // Exactly one native Back per historical transition.
        try {
            compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().size == 1 }
        } catch (failure: Throwable) {
            // The handoff may succeed while the history transition does not. Keep the
            // original assertion failure and inspect the same native window, even if removed.
            fun capture(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(failure::addSuppressed) }
            capture { compose.runOnUiThread {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                android.util.Log.e("ScopedSearchHistory", "${android.os.SystemClock.uptimeMillis()} phase=await history text expected=$text " +
                    "attached=${view.isAttachedToWindow} windowFocus=${view.hasWindowFocus()} " +
                    "ime=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                    "bottom=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}")
            } }
            capture { android.util.Log.e("ScopedSearchHistory", InstrumentationRegistry.getInstrumentation().uiAutomation.windows.joinToString { window ->
                "id=${window.id} type=${window.type} focused=${window.isFocused} active=${window.isActive} package=${window.root?.packageName}"
            }) }
            capture { android.util.Log.e("ScopedSearchHistory", compose.onAllNodes(isRoot()).printToString()) }
            throw failure
        }
        editor.assertTextContains(text).assertIsNotFocused()
        compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
    }

    private fun awaitClosed() {
        BrowseSchedulerWatchdog.phase("await scoped popup removed")
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
        } catch (failure: Throwable) {
            // Inspect the failed close without extra Back input or an extended deadline.
            fun capture(block: () -> Unit) {
                runCatching(block).exceptionOrNull()?.let(failure::addSuppressed)
            }
            capture {
                val editors = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
                editors.forEach { node ->
                    val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
                    val text = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) {
                        androidx.compose.ui.text.AnnotatedString("<absent>")
                    }
                    val focused = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
                    compose.runOnUiThread {
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                        android.util.Log.e("ScopedSearchHistory", "awaitClosed timeout editor=$text focused=$focused " +
                            "attached=${view.isAttachedToWindow} windowFocus=${view.hasWindowFocus()} " +
                            "ime=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                            "bottom=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}")
                    }
                }
            }
            for (unmerged in listOf(false, true)) capture {
                compose.onAllNodes(isRoot(), useUnmergedTree = unmerged).printToString()
                    .chunked(3000).forEachIndexed { index, chunk ->
                        android.util.Log.e("ScopedSearchHistory", "awaitClosed unmerged=$unmerged part=$index $chunk")
                    }
            }
            capture { android.util.Log.e("ScopedSearchHistory", InstrumentationRegistry.getInstrumentation()
                .uiAutomation.windows.joinToString { window ->
                    "id=${window.id} type=${window.type} focused=${window.isFocused} " +
                        "active=${window.isActive} package=${window.root?.packageName}"
                }) }
            throw failure
        }
    }

    private fun scrollToAlbum(title: String) {
        val key = albumKeys.getValue(title)
        var index = -1
        BrowseSchedulerWatchdog.phase("await indexed result $title")
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            index = runCatching { compose.onNodeWithTag("browser-list").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.IndexForKey](key) }.getOrDefault(-1)
            index >= 0
        }
        BrowseSchedulerWatchdog.phase("scroll $title to index $index")
        compose.onNodeWithTag("browser-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex) { it(index) }
        BrowseSchedulerWatchdog.phase("pump indexed scroll $title")
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        BrowseSchedulerWatchdog.phase("verify indexed scroll $title")
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test fun completedQueriesRestoreTheirPositionsAndRootBackClosesWithoutKeyboard() = fixture {
        submit("Album")
        scrollToAlbum("Album 060")
        val firstPosition = compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot
        submit("Album 0")
        scrollToAlbum("Album 040")
        val secondPosition = compose.onNodeWithText("Album 040").fetchSemanticsNode().boundsInRoot
        submit("Album 01")
        backTo("Album 0")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Album 040").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(secondPosition, compose.onNodeWithText("Album 040").fetchSemanticsNode().boundsInRoot)
        backTo("Album")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Album 060").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(firstPosition, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
        pressSystemBack()
        awaitClosed()
    }

    @Test fun draftBackEndsEditingBeforeRestoringCompletedQueryAndEmptyBackCloses() = fixture {
        submit("Album 01")
        editor.performClick().performTextReplacement("Album 02")
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.awaitExpectedWindowFocus(editor)
        compose.awaitSearchImeHandoff { pressSystemBack() }
        editor.assertTextContains("Album 02")
        compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
        backTo("Album 01")
        editor.performClick().performTextReplacement("")
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.awaitExpectedWindowFocus(editor)
        // Match editable content only; the empty field also exposes its search placeholder.
        editor.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        pressSystemBack() // Empty scoped search closes even with completed history.
        awaitClosed()
        open()
        editor.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        pressSystemBack()
        awaitClosed()
    }

    @Test fun outsideTapDismissesWholeHistoryInsteadOfRetracingIt() = fixture {
        submit("Album 01")
        submit("Album 02")
        compose.awaitExpectedWindowFocus(editor)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Search panels have horizontal safe margins; the window scrim owns this point.
        assertTrue("Outside coordinate must miss the search surface",
            compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot.left > 2f)
        assertTrue(device.click(2, device.displayHeight / 2))
        awaitClosed()
        open()
        editor.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        pressSystemBack()
        awaitClosed()
    }
}

package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListSearchTest {
    // Diagnostic comparison: queue effect continuations instead of applying a
    // test recomposition inline when real Default browse work resumes them.
    // Production dispatchers and all assertions remain unchanged.
    @get:Rule val compose = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    private fun awaitSearchFocus() {
        // Window focus arrives outside Compose's animation clock. Assert the editor's
        // state directly: a visible IME isn't guaranteed to appear in the accessibility
        // window list. GlassPressureTest separately exercises IME-owned Back via insets.
        compose.waitUntil(5000) {
            compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1
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
            compose.setContent { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            repeat(3) { pass ->
                if (pass == 1) compose.onNodeWithTag("browser-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 20f) }
                val before = compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("list-search-pill").performClick()
                awaitSearchFocus()
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                if (pass == 0) {
                    pressSystemBack()
                    compose.waitUntil(5000) { compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
                    compose.onNodeWithTag("list-search-pill").performClick()
                    awaitSearchFocus()
                }
                compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Album")
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
            compose.setContent { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            val pill = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot
            assertTrue("First item rests below the floating search pill", compose.onNodeWithText("Album 000").fetchSemanticsNode().boundsInRoot.top >= pill.bottom)
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.scrollToUncoveredItem(hasText("Album 060"))
            val before = compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
            awaitSearchFocus()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Album")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { InstrumentationRegistry.getInstrumentation().uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Album 061"))
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(java.io.File(context.getExternalFilesDir(null), "list-search-popup.png"))
            val resultPosition = compose.onNodeWithText("Album 061").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("Album 061").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 061").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("list-search-popup").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 061").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertTextContains("Album")
            assertEquals(resultPosition, compose.onNodeWithText("Album 061").fetchSemanticsNode().boundsInRoot)
            dismissSearch()
            assertEquals(before, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("list-search-pill").performClick()
            awaitSearchFocus()
            compose.onNode(hasSetTextAction()).assertIsFocused().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNode(hasSetTextAction()).performTextReplacement("061")
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

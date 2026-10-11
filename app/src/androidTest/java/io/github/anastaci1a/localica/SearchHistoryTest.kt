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

class SearchHistoryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun submittedSearchesRestoreQueryResultsAndScrollInReverseOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Songs), reducedMotion = true))
        val tracks = (0..99).map { n -> Track("song:$n", "Common " + n.toString().padStart(3, '0'),
            artist = if (n % 2 == 0) "Even" else "Odd", fileName = "$n.mp3", relativePath = "$n.mp3") }
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                PlaybackState(connected = true), {}, {}, {}, {}, {}, {}) } }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Even")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Common 060"))
            val before = compose.onNodeWithText("Common 060").fetchSemanticsNode().boundsInRoot
            compose.onNode(hasSetTextAction()).performTextReplacement("Odd")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Common 061"))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("Even")
            compose.onNodeWithText("Common 060").assertIsDisplayed()
            assertEquals(before, compose.onNodeWithText("Common 060").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("list-search-pill").assertTextContains("Search Songs")
        } finally { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
}

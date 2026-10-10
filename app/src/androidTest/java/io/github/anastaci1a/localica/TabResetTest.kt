package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsProperties
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

class TabResetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tabTapLeavesSearchAndDetailsAndEasesToTop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Library)))
        val tracks = (0..99).map { n -> Track("song:$n", "Song $n", album = "Album " + n.toString().padStart(3, '0'),
            fileName = "$n.mp3", relativePath = "$n.mp3") }
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                PlaybackState(connected = true), {}, {}, {}, {}, {}, {}) } }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToNode(hasText("Album 060"))
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Library").performClick()
            compose.mainClock.advanceTimeBy(160)
            val during = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Must move without teleporting: $before -> $during", during > 0 && during < before)
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Album 000").assertIsDisplayed() }.isSuccess }
            list.performScrollToNode(hasText("Album 060"))
            compose.onNodeWithText("Album 060").performClick()
            compose.onNodeWithText("Song 60").assertIsDisplayed()
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Album 000").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("060")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Album 000").assertIsDisplayed() }.isSuccess }
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            context.getSharedPreferences("interface", 0).edit().clear().commit()
        }
    }
}

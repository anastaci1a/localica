package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TabResetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun currentTabThenImmediateSongsTapAppliesTheFilter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library)))
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = listOf(Track("song:936", "Reselect song", album = "Reselect album",
                    fileName = "936.mp3", relativePath = "936.mp3"))), loading = false),
                PlaybackState(connected = true), {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false)) } }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitForIdle()
            val tab = compose.onNodeWithContentDescription("Library").fetchSemanticsNode().boundsInRoot.center
            val songs = compose.onNodeWithTag("filter-Songs").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { click(tab); click(songs) }
            compose.onNodeWithTag("filter-Songs").assertIsSelected()
            compose.onNode(hasText("Reselect song") and hasAnyAncestor(hasTestTag("browser-list"))).assertIsDisplayed()
        } finally { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
    private fun awaitFirstAlbum() {
        try {
            compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Album 000").assertIsDisplayed() }.isSuccess }
        } catch (failure: Throwable) {
            compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 20).chunked(2500).forEach { android.util.Log.e("TabReset", it) }
            throw failure
        }
    }
    @Test fun detailsHideTabsAndReturningAllowsTabResetOfSearchAndPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Library)))
        val tracks = (0..99).map { n -> Track("song:$n", "Song $n", album = "Album " + n.toString().padStart(3, '0'),
            fileName = "$n.mp3", relativePath = "$n.mp3") }
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                playlist = PlaylistState(loading = false)) } }
            val list = compose.onNodeWithTag("browser-list")
            compose.scrollToUncoveredItem(hasText("Album 060"))
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Library").performClick()
            compose.mainClock.advanceTimeBy(160)
            val during = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Must move without teleporting: $before -> $during", during > 0 && during < before)
            compose.mainClock.autoAdvance = true
            awaitFirstAlbum()
            repeat(5) {
                compose.scrollToUncoveredItem(hasText("Album 060"))
                compose.onNodeWithText("Album 060").performClick()
                compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Song 60").assertIsDisplayed() }.isSuccess }
                compose.onNodeWithContentDescription("Library").assertDoesNotExist()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Library").performClick()
                awaitFirstAlbum()
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("060")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithContentDescription("Library").performClick()
            awaitFirstAlbum()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            context.getSharedPreferences("interface", 0).edit().clear().commit()
        }
    }
}

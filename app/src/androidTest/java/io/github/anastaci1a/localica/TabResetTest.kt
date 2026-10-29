package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
    private fun awaitFirstAlbum() {
        try {
            compose.fixtureCostPhase("TabReset.await-first-album") { compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Album 000").assertIsDisplayed() }.isSuccess } }
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
            compose.fixtureCostPhase("TabReset.mount") { compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                playlist = PlaylistState(loading = false)) } } }
            val list = compose.onNodeWithTag("browser-list")
            compose.fixtureCostPhase("TabReset.initial-scroll") { compose.scrollToUncoveredItem(hasText("Album 060")) }
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.mainClock.autoAdvance = false
            compose.fixtureCostPhase("TabReset.initial-reset") { compose.onNodeWithContentDescription("Library").performClick() }
            compose.fixtureCostPhase("TabReset.advance-160") { compose.mainClock.advanceTimeBy(160) }
            val during = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Must move without teleporting: $before -> $during", during > 0 && during < before)
            compose.mainClock.autoAdvance = true
            awaitFirstAlbum()
            repeat(5) { cycle ->
                compose.fixtureCostPhase("TabReset.$cycle.scroll") { compose.scrollToUncoveredItem(hasText("Album 060")) }
                compose.fixtureCostPhase("TabReset.$cycle.open") { compose.onNodeWithText("Album 060").performClick() }
                compose.fixtureCostPhase("TabReset.$cycle.await-detail") { compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Song 60").assertIsDisplayed() }.isSuccess } }
                compose.onNodeWithContentDescription("Library").assertDoesNotExist()
                compose.fixtureCostPhase("TabReset.$cycle.back") { compose.onNodeWithContentDescription("Back").performClick() }
                compose.fixtureCostPhase("TabReset.$cycle.reset") { compose.onNodeWithContentDescription("Library").performClick() }
                awaitFirstAlbum()
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            }
            compose.fixtureCostPhase("TabReset.search-open") { compose.onNodeWithContentDescription("Search library").performClick() }
            compose.fixtureCostPhase("TabReset.search-type") { compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("060") }
            compose.fixtureCostPhase("TabReset.search-ime-action") { compose.onNode(hasSetTextAction()).performImeAction() }
            compose.fixtureCostPhase("TabReset.search-reset") { compose.onNodeWithContentDescription("Library").performClick() }
            awaitFirstAlbum()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            context.getSharedPreferences("interface", 0).edit().clear().commit()
        }
    }
}

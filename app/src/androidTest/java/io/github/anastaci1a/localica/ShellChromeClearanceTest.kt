package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * The browse end padding takes the lower chrome from the shell inset. These checks hold it to the real
 * mini player, navigation pill, floating controls and selection panel as they are actually placed.
 */
class ShellChromeClearanceTest {
    private val compose = localicaComposeRule()
    // shell() persists a one-tab bar; restore it so later real-app tests keep their tabs (#722).
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        BrowsePreferenceIsolation(listOf("interface", "browse-style", "library-query"))).around(compose)
    private val tracks = List(40) { Track("shell:$it", "Song ${it.toString().padStart(2, '0')}",
        album = "Shell album", artist = "Artist", trackNumber = it + 1, fileName = "$it.mp3", relativePath = "$it.mp3") }

    private fun shell(tab: MainTab) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(tab), reducedMotion = true))
        val track = tracks.first()
        val playback = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title,
            artist = track.artist, durationMs = 125000)
        compose.setContent { LocalicaTheme {
            LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), playback,
                {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bounds(matcher: SemanticsMatcher): List<Rect> =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }

    private fun assertLastRowClears(lastKey: String, vararg required: SemanticsMatcher) {
        val list = compose.onNodeWithTag("browser-list")
        list.performScrollToIndex(tracks.size)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        val last = compose.onNodeWithTag("media-$lastKey").fetchSemanticsNode().boundsInRoot
        for (matcher in required) assertTrue("Missing real control $matcher", bounds(matcher).isNotEmpty())
        val chrome = listOf(hasTestTag("player-surface"), hasTestTag("tab-pill"), hasTestTag("floating-return-control"),
            hasTestTag("selection-pill"), hasContentDescription("Album actions"))
        for (matcher in chrome) for (control in bounds(matcher))
            assertTrue("Last row $last must clear $matcher at $control", last.bottom <= control.top)
    }

    @Test fun songListEndClearsRealMiniPlayerNavigationAndReturn() {
        shell(MainTab.Songs)
        assertLastRowClears("shell:39", hasTestTag("player-surface"), hasTestTag("tab-pill"), hasTestTag("floating-return-control"))
    }

    @Test fun albumEndClearsRealMiniPlayerNavigationAndOverflow() {
        shell(MainTab.Albums)
        val album = LibraryViews.groups(LibrarySnapshot(tracks = tracks), SongListView.Albums, LibraryQuery()).single()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-Album:${album.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-Album:${album.id}").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Album actions").fetchSemanticsNodes().isNotEmpty() }
        assertLastRowClears("shell:39", hasTestTag("player-surface"), hasContentDescription("Album actions"))
    }

    @Test fun selectionEndClearsRealSelectionPanelAndMiniPlayer() {
        shell(MainTab.Songs)
        compose.awaitRowActions("Song 00").performClick()
        compose.onNodeWithText("Select song").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("selection-pill", true).fetchSemanticsNodes().isNotEmpty()
        }
        assertLastRowClears("shell:39", hasTestTag("selection-pill"), hasTestTag("player-surface"))
    }
}

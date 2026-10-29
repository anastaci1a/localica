package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Rule
import org.junit.Test

class CollectionSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun playlistCatalogSelectionRemainsSelectedAndRemovableInItsDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interfaceStore = context.getSharedPreferences("interface", 0)
        val browseStore = context.getSharedPreferences("browse-style", 0)
        interfaceStore.edit().clear().commit()
        browseStore.edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Playlists), reducedMotion = true))
        val track = Track("selection:song", "Repeated song", fileName = "song.mp3", relativePath = "song.mp3")
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Selection mix"), listOf(track, track))
        try {
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                    PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                    playlist = PlaylistState(listOf(playlist), loading = false))
            } }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-Playlist:${playlist.id}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Actions for Selection mix").performClick()
            compose.onNodeWithText("Select all songs").performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithText("Remove from playlist").assertExists()
            // Selection appears in the underlying activity before the closing menu's
            // native window is necessarily removed. Wait for that explicit handoff
            // before injecting the next physical touch into the activity.
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty()
            }
            compose.awaitExpectedWindowFocus(compose.onNodeWithContentDescription("Collapse selection"))
            compose.onNodeWithContentDescription("Collapse selection").performClick()
            compose.onNodeWithText("Selection: 2").assertIsDisplayed()
            fun clickAboveSelection(node: SemanticsNodeInteraction, count: Int) {
                fun panelBounds() = generateSequence(compose.onNodeWithText("Selection: $count").fetchSemanticsNode()) { it.parent }
                    .first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) }.boundsInRoot
                val panel = panelBounds()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                val overlap = bounds.bottom - panel.top + 16f
                if (overlap > 0f) {
                    compose.onNodeWithTag("browser-list").performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, overlap) }
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                }
                val visible = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                val currentPanel = panelBounds()
                org.junit.Assert.assertTrue("Selection panel must not cover the target: $visible / $currentPanel", visible.bottom < currentPanel.top)
                compose.awaitExpectedWindowFocus(node)
                node.performClick()
            }
            clickAboveSelection(compose.onNodeWithTag("media-Playlist:${playlist.id}"), 2)
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-${playlist.entries.first().id}").fetchSemanticsNodes().isNotEmpty()
            }
            // The real playlist hero can leave only the first occurrence composed.
            // Arrange the song viewport before checking both selected occurrences.
            compose.onNodeWithTag("browser-list").performScrollToKey(playlist.entries.first().id)
            val first = compose.onNodeWithTag("media-${playlist.entries.first().id}")
            val second = compose.onNodeWithTag("media-${playlist.entries.last().id}")
            first.assertIsSelected()
            second.assertIsSelected()
            clickAboveSelection(first, 2)
            first.assertIsNotSelected()
            second.assertIsSelected()
            compose.onNodeWithText("Selection: 1").assertIsDisplayed().performClick()
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            compose.onNodeWithText("Remove from playlist").assertExists()
        } catch (failure: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToLog("CollectionSelection")
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            interfaceStore.edit().clear().commit()
            browseStore.edit().clear().commit()
        }
    }
}

package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyLibraryActionsRemainReachableInShortWindow() {
        var opened = false
        compose.setContent {
            LocalicaTheme {
                Box(Modifier.requiredSize(360.dp, 220.dp)) {
                    BrowserScreen(LibraryUiState(loading = false), PlaylistState(loading = false), BrowseRoute(MainTab.Songs),
                        {}, {}, { opened = true }, { _, _, _ -> }, null, null, true, false)
                }
            }
        }
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Add music folders"))
        compose.onNodeWithText("Add music folders").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun folderRemovalRequiresConfirmationAndDoesNotRemoveFiles() {
        var removed: String? = null
        compose.setContent {
            LocalicaTheme {
                FolderSettings(LibraryUiState(snapshot = LibrarySnapshot(listOf(FolderSelection("tree", "Music"))), loading = false),
                    onDismiss = {}, onChooseFolder = {}, onRemove = { removed = it })
            }
        }
        compose.onNodeWithText("Library and tags").performClick()
        compose.onNodeWithText("Folders to scan").performClick()
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Remove folder").performClick()
        compose.onNodeWithText("Remove Music?").assertIsDisplayed()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Remove folder").performClick()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        compose.runOnIdle { assertEquals("tree", removed) }
    }

    @Test fun tabsSelectScrollableLibraryAndSettingsOpenFolderConfiguration() {
        val tracks = (1..40).map { Track("track:$it", "Track $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        var selected: Track? = null
        var requestedFolder = false
        compose.setContent {
            LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(listOf(FolderSelection("tree", "Music")), tracks), loading = false),
                    PlaybackState(connected = true), onPlay = { selected = it }, onToggle = {}, onSeek = {},
                    onChooseFolder = { requestedFolder = true }, onRescan = {}, onCancelScan = {},
                    playlist = PlaylistState(loading = false))
            }
        }
        compose.onNodeWithText("Your music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Songs").performClick()
        compose.scrollToUncoveredItem(hasText("Track 40"))
        // Diagnostic observation only; keep the original click and callback assertion.
        val targetBounds = compose.onNodeWithText("Track 40").fetchSemanticsNode().boundsInRoot
        val floatingBounds = compose.onAllNodes(hasTestTag("tab-pill") or hasTestTag("player-surface"))
            .fetchSemanticsNodes().map { it.boundsInRoot }
        android.util.Log.i("NavigationTapGeometry", "beforeClick target=$targetBounds center=${targetBounds.center} floating=$floatingBounds selected=$selected")
        compose.onNodeWithText("Track 40").performClick()
        android.util.Log.i("NavigationTapGeometry", "afterClick selected=$selected")
        try {
            compose.runOnIdle { assertEquals(tracks.last(), selected) }
        } catch (failure: AssertionError) {
            fun retain(block: () -> Unit) {
                try { block() } catch (diagnosticFailure: Throwable) {
                    if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                }
            }
            retain {
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val directory = nativeEvidenceDirectory("navigation-click-failure", instrumentation.targetContext.cacheDir)
                java.io.File(directory, "selection.txt").writeText("expected=track:40 selected=$selected\n")
                for (unmerged in listOf(false, true)) retain {
                    java.io.File(directory, "semantics-$unmerged.txt").writeText(
                        compose.onAllNodes(isRoot(), useUnmergedTree = unmerged).printToString(maxDepth = Int.MAX_VALUE))
                }
                retain {
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        java.io.File(directory, "screen.png").outputStream().use {
                            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { screenshot.recycle() }
                }
            }
            throw failure
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        val librarySettings = compose.onNodeWithText("Library and tags")
        compose.awaitExpectedWindowFocus(librarySettings)
        librarySettings.assertIsDisplayed().performClick()
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithText("Folders to scan").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToLog("NavigationSettings")
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        compose.onNodeWithText("Folders to scan").performClick()
        compose.onNodeWithText("Folders to scan").assertIsDisplayed()
        compose.onNodeWithText("Add folder").performClick()
        compose.runOnIdle { assertTrue(requestedFolder) }
    }
}

package io.github.anastaci1a.localica

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.home.PinPreferences
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectionBackTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var session: SessionSelection
    private lateinit var dispatcher: OnBackPressedDispatcher
    private val track = Track("back:song", "Back song", artist = "Back artist", album = "Back album",
        albumArtist = "Back artist", durationMs = 180000, fileName = "back.mp3", relativePath = "back.mp3")

    private enum class Host(val tab: MainTab) {
        Library(MainTab.Library), Songs(MainTab.Songs), Home(MainTab.Home),
        Album(MainTab.Albums), QueueTab(MainTab.Queues), QueueDrawer(MainTab.Songs)
    }
    private enum class Input { System, Gesture }
    private val media = SemanticsMatcher("media row or pinned media") {
        val tag = it.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        tag.startsWith("media-") || tag.startsWith("pin-")
    }

    @Test fun systemBackEndsLibrarySelectionBeforeExit() = exercise(Host.Library, Input.System)
    @Test fun committedGestureEndsLibrarySelectionBeforeExit() = exercise(Host.Library, Input.Gesture)
    @Test fun systemBackEndsSongsSelectionBeforeExit() = exercise(Host.Songs, Input.System)
    @Test fun committedGestureEndsSongsSelectionBeforeExit() = exercise(Host.Songs, Input.Gesture)
    @Test fun systemBackEndsHomeSelectionBeforeExit() = exercise(Host.Home, Input.System)
    @Test fun committedGestureEndsHomeSelectionBeforeExit() = exercise(Host.Home, Input.Gesture)
    @Test fun systemBackEndsDetailSelectionBeforeLeavingTheDetail() = exercise(Host.Album, Input.System)
    @Test fun committedGestureEndsDetailSelectionBeforeLeavingTheDetail() = exercise(Host.Album, Input.Gesture)
    @Test fun systemBackEndsQueueTabSelectionBeforeExit() = exercise(Host.QueueTab, Input.System)
    @Test fun committedGestureEndsQueueTabSelectionBeforeExit() = exercise(Host.QueueTab, Input.Gesture)
    @Test fun systemBackEndsDrawerSelectionBeforeClosingTheDrawer() = exercise(Host.QueueDrawer, Input.System)
    @Test fun committedGestureEndsDrawerSelectionBeforeClosingTheDrawer() = exercise(Host.QueueDrawer, Input.Gesture)

    @Test fun playerBackDoesNotClearTheBrowseSelectionItCovers() = withScreen(Host.Songs, withPlayer = true) {
        selectVisible(Host.Songs)
        openPlayer()
        back(Input.System)
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertTrue("A hidden selection must not intercept player Back", session.active) }
        finishSelection(Host.Songs, Input.System)
    }

    @Test fun popupBackKeepsSelectionForTheFollowingBack() = withScreen(Host.Songs) {
        selectVisible(Host.Songs)
        compose.onNodeWithContentDescription("More selection actions").performClick()
        compose.awaitExpectedWindowFocus(compose.onNodeWithText("Select all"))
        pressSystemBack()
        compose.awaitNoGlassMenu()
        compose.runOnIdle { assertTrue("The popup owns its own Back", session.active) }
        finishSelection(Host.Songs, Input.System)
    }

    private fun exercise(host: Host, input: Input) = withScreen(host) {
        selectVisible(host)
        if (input == Input.Gesture) {
            gesture(cancel = true)
            compose.runOnIdle {
                assertTrue("A cancelled gesture keeps selection", session.active)
                assertEquals(1, session.entries.size)
            }
            compose.onNodeWithText("Close Localica?").assertDoesNotExist()
        }
        finishSelection(host, input)
    }

    private fun finishSelection(host: Host, input: Input) {
        back(input)
        compose.waitUntil(5000) {
            compose.runOnUiThread { !session.active } || exitShown() ||
                host == Host.Album && compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Close Localica?").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("Back ends selection before navigation", session.active)
            assertTrue("Back deselects every occurrence", session.entries.isEmpty())
        }
        compose.waitUntil(5000) {
            compose.onAllNodes(media and isSelected()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("1 selected").fetchSemanticsNodes().isEmpty()
        }
        if (host == Host.Album) compose.onNodeWithContentDescription("Back").assertExists()
        if (host == Host.QueueDrawer) compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed()

        back(input)
        when (host) {
            Host.Album -> {
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Close Localica?").assertDoesNotExist()
                back(input)
                compose.waitUntil(5000) { exitShown() }
            }
            Host.QueueDrawer -> {
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Queue actions").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
                compose.onNodeWithText("Close Localica?").assertDoesNotExist()
            }
            else -> compose.waitUntil(5000) { exitShown() }
        }
    }

    private fun exitShown() = compose.onAllNodesWithText("Close Localica?").fetchSemanticsNodes().isNotEmpty()

    private fun back(input: Input) {
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("browse-background"))
        if (input == Input.System) pressSystemBack() else gesture(cancel = false)
    }

    private fun gesture(cancel: Boolean) {
        // Deliver the AndroidX gesture protocol on both APIs. API 26 has no native
        // gestural-navigation mode; the committed event uses the same dispatcher.
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 120f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 120f, .6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            if (cancel) dispatcher.dispatchOnBackCancelled() else dispatcher.onBackPressed()
        }
    }

    private fun openPlayer() {
        compose.onNodeWithTag("player-surface").performTouchInput { swipeUp() }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun selectVisible(host: Host) {
        compose.waitUntil(5000) { compose.onAllNodes(media).fetchSemanticsNodes().isNotEmpty() }
        if (host != Host.Home) compose.onNodeWithTag("browser-list").performScrollToIndex(0)
        compose.runOnIdle {
            val source = if (host == Host.QueueTab || host == Host.QueueDrawer) MediaRef(MediaKind.Queue, "back-queue") else session.source
            val id = if (source?.kind == MediaKind.Queue) "back-entry" else "back:song"
            session.select(source, listOf(SongRow(id, track)))
        }
        compose.waitUntil(5000) {
            compose.onAllNodes(media and isSelected()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("1 selected").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun withScreen(host: Host, withPlayer: Boolean = false, block: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stores = listOf("interface", "browse-style", "pins").map { context.getSharedPreferences(it, 0) }
        stores.forEach { it.edit().clear().commit() }
        val queue = ListeningQueue(id = "back-queue", name = "Back queue", entries = listOf(QueueEntry("back-entry", track)))
        val queueHost = host == Host.QueueTab || host == Host.QueueDrawer || withPlayer
        val queues = if (queueHost) QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false) else QueueUiState(loading = false)
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(host.tab), confirmExit = true,
            tabHistory = false, reducedMotion = true, expandOnPlay = false))
        if (host == Host.Home) PinPreferences(context).write(listOf(MediaRef(MediaKind.Song, track.id)))
        try {
            compose.setContent {
                session = viewModel()
                dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                LocalicaTheme {
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("back-root", "Music")),
                        tracks = listOf(track)), loading = false), PlaybackState(connected = true),
                        {}, {}, {}, {}, {}, {}, queues = queues, playlist = PlaylistState(loading = false))
                }
            }
            if (host == Host.Album) {
                compose.waitUntil(5000) { compose.onAllNodesWithText("Back album").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Back album").performClick()
                compose.onNodeWithContentDescription("Back").assertExists()
            }
            if (host == Host.QueueDrawer) {
                openPlayer()
                compose.onNodeWithContentDescription("Open queue").performClick()
                compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed()
            }
            block()
        } finally {
            compose.runOnUiThread { if (::session.isInitialized) session.clear() }
            stores.forEach { it.edit().clear().commit() }
        }
    }
}

package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real playlist page/adapters and Room actions; no private media or attached decoder. */
class PlaylistDetailPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("browse-style", 0)
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    @Before fun savePreferences() { savedPreferences = preferences.all.toMap() }
    @After fun restorePreferences() {
        val edit = preferences.edit().clear()
        savedPreferences.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            else -> error("Unsupported saved preference type")
        } }
        assertTrue(edit.commit())
    }
    private val tracks = (0..49).map { Track("playlist:$it", "Song ${it.toString().padStart(3, '0')}",
        id = "track-$it", fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val rows = (0..99).map { SongRow("playlist-occurrence-$it", tracks[it % tracks.size]) }
    private val playlist = ManualPlaylist(id = "detail-470", name = "Playlist detail", entries = rows)
    private val list get() = compose.onNodeWithTag("browser-list")
    private fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private class Fixture(initial: ManualPlaylist, tracks: List<Track>) {
        var mounted by mutableStateOf(true)
        var playlists by mutableStateOf(PlaylistState(listOf(initial), loading = false))
        var library by mutableStateOf(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false))
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, initial.id), initial.name)
        val artwork = BrowseArtworkScope()
        val selection = SessionSelection()
        var background: Color? = null
        var backCalls = 0
        val plays = mutableListOf<List<SongRow>>()
    }
    private fun fixture(initial: ManualPlaylist = playlist, controller: PlaylistController? = null,
        queues: QueueController? = null): Fixture {
        val f = Fixture(initial, tracks)
        BrowsePreferences(context).write(f.route, BrowseStyle(quickSearch = true), true)
        compose.setContent { LocalicaTheme {
            val playlists = controller?.state?.collectAsState()?.value ?: f.playlists
            val queueState = queues?.state?.collectAsState()?.value ?: QueueUiState(loading = false)
            CompositionLocalProvider(LocalBrowseArtwork provides f.artwork, LocalSessionSelection provides f.selection) {
                if (f.mounted) BrowserScreen(f.library, playlists, f.route, {}, {}, {},
                    { entries, index, _ -> assertEquals(0, index); f.plays += entries.toList() }, queues, controller,
                    true, false, queueState = queueState, reducedMotion = true, listBackEnabled = true,
                    queryDispatcher = Dispatchers.Unconfined, onBack = { f.backCalls++ },
                    onAppearance = { _, background -> f.background = background })
            }
        } }
        compose.waitUntil(5000) {
            compose.onAllNodesWithContentDescription("Playlist actions").fetchSemanticsNodes().isNotEmpty()
        }
        return f
    }
    private fun top() {
        list.performScrollToIndex(0)
        compose.waitUntil(5000) { position() == 0f }
    }
    private fun assertTrueTop() {
        assertEquals(0f, position(), 0f)
        assertEquals(list.fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithTag("browse-full-span").fetchSemanticsNode().boundsInRoot.top, 1.1f)
        compose.onNodeWithContentDescription("Play playlist").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shuffle playlist").assertIsDisplayed()
    }
    private fun unmount(f: Fixture) { compose.runOnIdle { f.mounted = false }; compose.waitForIdle() }

    @Test fun canonicalManualAutomaticAndEmptyPlaylistCoversDriveTheRealCollectionPage() {
        val empty = playlist.copy(entries = emptyList())
        val f = fixture(empty)
        try {
            compose.onNodeWithContentDescription("Play playlist").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Shuffle playlist").assertIsNotEnabled()
            compose.onNodeWithText("Albums", substring = false).assertDoesNotExist()
            compose.onNodeWithContentDescription("Empty list").assertExists()
            compose.runOnIdle { assertEquals(NeutralPalette.bottom, f.background) }
            fun prepared(uri: String, color: Color) {
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toArgb()) }
                // A real caller-owned synthetic cover, retained through the ordinary
                // collection visual cache; no passing-color renderer injection.
                f.artwork.keepVisual(uri, PlayerVisual(bitmap.asImageBitmap(),
                    PlayerPalette(color, Color.Black, color, wash = bitmap)))
            }
            val custom = "playlist:manual-cover"
            val manualColor = Color(0xFF102050)
            compose.runOnIdle {
                prepared(custom, manualColor)
                f.playlists = PlaylistState(listOf(empty.copy(coverUri = custom)), loading = false)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("Manual cover also survives an empty playlist", manualColor, f.background) }
            assertCoverPixels(manualColor)
            val currentFirst = tracks.first().copy(uri = "playlist:current-first")
            val automaticColor = Color(0xFF503010)
            compose.runOnIdle {
                prepared(currentFirst.uri, automaticColor)
                f.library = LibraryUiState(LibrarySnapshot(tracks = listOf(currentFirst) + tracks.drop(1)), loading = false)
                f.playlists = PlaylistState(listOf(playlist), loading = false)
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${rows.first().id}").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals("Automatic cover resolves the physical occurrence's current metadata", automaticColor, f.background) }
            assertCoverPixels(automaticColor)
            compose.onNodeWithContentDescription("Play playlist").assertIsEnabled()
            compose.runOnIdle { f.playlists = PlaylistState(listOf(empty), loading = false) }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Empty list").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals("No stale cover survives an empty automatic playlist", NeutralPalette.bottom, f.background) }
        } finally { unmount(f) }
    }

    @Test fun sharedPlayShuffleAndMutablePlaylistMenuPreserveEveryOccurrence() {
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val queueDb = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val queuePreferences = QueuePreferences(context)
        val oldQueuePreferences = queuePreferences.read()
        var f: Fixture? = null
        var primaryFailure: Throwable? = null
        try {
            runBlocking { db.playlists().save(emptyList(), listOf(playlist)) }
            lateinit var controller: PlaylistController
            lateinit var queues: QueueController
            compose.runOnUiThread {
                controller = PlaylistController(db.playlists(), scope)
                queues = QueueController(queueDb.queues(), queuePreferences,
                    (context.applicationContext as LocalicaApplication).library, scope)
            }
            runBlocking { withTimeout(5000) {
                controller.state.first { !it.loading }; queues.state.first { !it.loading }
            } }
            val fixture = fixture(controller = controller, queues = queues).also { f = it }
            compose.onNodeWithContentDescription("Play playlist").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(rows.map { it.id }, fixture.plays.single().map { it.id })
                assertTrue(fixture.plays.single().all { it.sourcePlaylistId == playlist.id })
            }
            compose.onNodeWithContentDescription("Shuffle playlist").performClick()
            compose.waitUntil(5000) { queues.state.value.library.active?.entries?.size == rows.size }
            compose.runOnIdle {
                val entries = requireNotNull(queues.state.value.library.active).entries
                assertEquals(rows.groupingBy { it.track.id }.eachCount(), entries.groupingBy { it.track.id }.eachCount())
                assertEquals(rows.size, entries.map { it.id }.toSet().size)
                assertTrue(entries.all { it.sourcePlaylistId == playlist.id })
                assertEquals(1, fixture.plays.size)
            }
            compose.onNodeWithContentDescription("Sort").performClick()
            compose.onNodeWithText("Reverse", substring = false).performScrollTo().performClick()
            compose.waitUntil(5000) { controller.state.value.playlists.single().entries.map { it.id } == rows.reversed().map { it.id } }
            assertEquals(rows.reversed().map { it.id }, runBlocking { db.playlists().read() }.single().entries.map { it.id })
            compose.onNodeWithText("Revert", substring = false).performScrollTo().performClick()
            compose.waitUntil(5000) { controller.state.value.playlists.single().entries == rows }
            compose.onNodeWithText("Close", substring = false).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-options-dialog").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Playlist actions").performClick()
            compose.onNodeWithText("Select all songs").performScrollTo().performClick()
            compose.waitUntil(5000) { fixture.selection.entries.size == rows.size }
            compose.runOnIdle { assertEquals(rows.map { it.id }, fixture.selection.entries.map { it.row.id }) }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanup(primaryFailure, { f?.let(::unmount) }, { runBlocking { job.cancelAndJoin() } },
                { queuePreferences.write(oldQueuePreferences) }, { queueDb.close() }, { db.close() })
        }
    }

    @Test fun viewQuickSearchToggleRetainsTrueTopReturnAndNativeBackWithoutLeavingThePlaylist() {
        val f = fixture()
        try {
            compose.onNodeWithTag("list-search-pill").assertExists()
            compose.onNodeWithContentDescription("View options").performClick()
            compose.onNodeWithText("Quick-search box", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-options-dialog").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("list-search-pill").assertDoesNotExist()
            top(); assertTrueTop()
            list.performScrollToIndex(65)
            compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
            // The floating control returns to the padded songs boundary, not the hero.
            // Native Back below deliberately retains its separate page-top contract.
            compose.waitForIdle()
            assertEquals("Floating return stops at the padded song boundary",
                list.getUnclippedBoundsInRoot().top.value,
                compose.onNodeWithTag("browser-song-list-start", true).getUnclippedBoundsInRoot().top.value, 1f)
            assertTrue("Hero stays above the viewport", position() > 0f)
            compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
            list.performScrollToIndex(65)
            compose.awaitExpectedWindowFocus(list)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(5000) { position() == 0f }
            assertTrueTop()
            compose.onNodeWithTag("list-search-pill").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, f.backCalls); assertTrue(f.plays.isEmpty()) }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.runOnIdle { assertEquals(1, f.backCalls) }
        } finally { unmount(f) }
    }

    private fun cleanup(primary: Throwable?, vararg actions: () -> Unit) {
        var failure = primary
        actions.forEach { action -> try { action() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
        } }
        if (primary == null) failure?.let { throw it }
    }

    private fun assertCoverPixels(expected: Color) {
        // One fixed static capture per cover stage, never a passing-pixel poll.
        // The middle of the square cover is below the 100dp toolbar gradient and
        // above the header title/actions. This fixture's cover is opaque/constant.
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val x = pixels.width / 2
        val y = (pixels.width * .35f).toInt()
        assertTrue("Cover sample must lie inside the actual native host", y in 0 until pixels.height)
        val actual = pixels[x, y]
        assertEquals("actual playlist cover red at $x,$y", expected.red, actual.red, .04f)
        assertEquals("actual playlist cover green at $x,$y", expected.green, actual.green, .04f)
        assertEquals("actual playlist cover blue at $x,$y", expected.blue, actual.blue, .04f)
    }
}

package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real menus/controller persistence, with no player attached and no audio inputs. */
class ArtistCollectionActionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun artistQueueActionsFollowOneSubsetAllAndChangedAlbumChoices() = actions(MediaKind.Artist, MainTab.Artists, SongListView.Artists)
    @Test fun albumArtistQueueActionsFollowOneSubsetAllAndChangedAlbumChoices() = actions(MediaKind.AlbumArtist, MainTab.AlbumArtists, SongListView.AlbumArtists)

    private fun actions(kind: MediaKind, tab: MainTab, view: SongListView) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val session = SessionSelection()
        val tracks = (0..5).map { Track("scope:$it", "Song $it", artist = "Artist", albumArtist = "Artist",
            album = listOf("First album", "Second album", "Third album")[it / 2],
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        val snapshot = LibrarySnapshot(tracks = tracks)
        val artist = LibraryViews.groups(snapshot, view, LibraryQuery()).single()
        // Queue publication must not mint a new route and discard the chosen album scope.
        val route = BrowseRoute(tab, MediaRef(kind, artist.id), "Artist",
            querySort = TrackSort.Title, queryAscending = true)
        lateinit var controller: QueueController
        try {
            preferences.write(QueueSettings(shuffle = true))
            compose.runOnUiThread { controller = QueueController(db.queues(), preferences,
                (context.applicationContext as LocalicaApplication).library, scope) }
            compose.waitUntil(5000) { !controller.state.value.loading }
            compose.setContent { LocalicaTheme {
                val queueState by controller.state.collectAsState()
                CompositionLocalProvider(LocalSessionSelection provides session) {
                    BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(),
                        route, {}, {}, {}, { _, _, _ -> },
                        controller, null, true, false, queueState = queueState, reducedMotion = true,
                        queryDispatcher = Dispatchers.Unconfined)
                }
            } }
            fun albums(chosen: List<String>) {
                compose.onNode(hasScrollToIndexAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .performScrollToIndex(0)
                val carousel = compose.onNode(hasScrollToIndexAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                carousel.performScrollToNode(hasText("All albums"))
                compose.onNodeWithText("All albums").performClick()
                for ((index, name) in chosen.withIndex()) {
                    carousel.performScrollToNode(hasText(name))
                    val album = compose.onNodeWithText(name)
                    if (index == 0) album.performClick() else album.performTouchInput { longClick() }
                }
            }
            fun openMenu() {
                compose.onNodeWithContentDescription("Artist actions").performClick()
            }
            fun menuClosed() {
                compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
                compose.awaitExpectedWindowFocus(compose.onNodeWithContentDescription("Artist actions"))
            }
            val choices = listOf(emptyList(), listOf("First album"), listOf("First album", "Third album"),
                listOf("Second album"), emptyList())
            for ((case, chosen) in choices.withIndex()) {
                albums(chosen)
                val expected = tracks.filter { chosen.isEmpty() || it.album in chosen }.map { it.id }
                compose.runOnIdle { assertEquals("All/Range scope follows actual filter", expected, session.visible.map { it.track.id }) }
                // The same scoped rows used by the selection panel's All command.
                compose.runOnIdle { session.select(session.source, session.visible) }
                val multiselected = compose.runOnIdle { session.entries.map { it.row.track.id }.also { session.clear() } }
                assertEquals(expected, multiselected)
                for (action in listOf("Play next", "Add to current queue")) {
                    val seed = tracks.first().copy(id = "seed-$case-$action", title = "Seed")
                    val created = CompletableDeferred<Boolean>()
                    compose.runOnUiThread { controller.act(listOf(seed), SongTap.NewQueue) { created.complete(it) } }
                    assertTrue(runBlocking { withTimeout(5000) { created.await() } })
                    val before = controller.state.value.library.active!!
                    openMenu()
                    compose.onNodeWithText(action).performScrollTo().performClick()
                    menuClosed()
                    compose.waitUntil(5000) { controller.state.value.library.active?.entries?.size == expected.size + 1 }
                    val active = controller.state.value.library.active!!
                    assertEquals(before.id, active.id)
                    assertEquals(before.currentEntryId, active.currentEntryId)
                    assertEquals(listOf(seed.id) + expected, active.entries.map { it.track.id })
                    assertEquals(multiselected, active.entries.drop(1).map { it.track.id })
                    assertTrue("Queue actions do not disable traversal shuffle", controller.state.value.settings.shuffle)
                    assertNull(controller.state.value.problem)
                }
                openMenu()
                compose.onNodeWithText("Select all songs").performScrollTo().performClick()
                menuClosed()
                compose.runOnIdle {
                    assertEquals(expected, session.entries.map { it.row.track.id })
                    session.clear()
                }
            }
        } finally {
            runBlocking { job.cancelAndJoin() }
            preferences.write(original)
            db.close()
        }
    }
}

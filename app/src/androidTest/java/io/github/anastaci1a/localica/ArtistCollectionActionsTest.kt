package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
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

    @Test fun settledCarouselSwipesKeepPhysicalAlbumTapsFilteringSongs() =
        actions(MediaKind.Artist, MainTab.Artists, SongListView.Artists, afterScroll = true)

    @Test fun tapDuringCarouselFlingStopsItThenTheNextTapFiltersSongs() =
        actions(MediaKind.Artist, MainTab.Artists, SongListView.Artists, afterScroll = true, duringFling = true)

    private fun actions(kind: MediaKind, tab: MainTab, view: SongListView,
        afterScroll: Boolean = false, duringFling: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val session = SessionSelection()
        val tracks = (0 until if (afterScroll) 16 else 6).map { Track("scope:$it", "Song $it", artist = "Artist", albumArtist = "Artist",
            album = if (afterScroll) "Album ${it / 2}" else listOf("First album", "Second album", "Third album")[it / 2],
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
            compose.setContent { EngineTheme(reduced = !afterScroll) {
                val queueState by controller.state.collectAsState()
                CompositionLocalProvider(LocalSessionSelection provides session) {
                    BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(),
                        route, {}, {}, {}, { _, _, _ -> },
                        controller, null, true, false, queueState = queueState, reducedMotion = !afterScroll,
                        queryDispatcher = Dispatchers.Unconfined)
                }
            } }
            if (afterScroll) {
                val carousel = compose.onNode(hasScrollToIndexAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                carousel.performScrollTo()
                fun position() = carousel.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
                fun visibleAlbum(previous: String = ""): String {
                    val viewport = carousel.fetchSemanticsNode().boundsInRoot
                    return tracks.map { it.album }.distinct().firstOrNull { name ->
                        name != previous && compose.onAllNodesWithText(name).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                            ?.let { it.width >= 112f * compose.density.density && it.height >= 120f * compose.density.density &&
                                it.left >= viewport.left && it.right <= viewport.right } == true
                    } ?: error("Fixture: no different album with a substantial visible touch target")
                }
                if (duringFling) {
                    compose.mainClock.autoAdvance = false
                    try {
                        carousel.performTouchInput {
                            swipeWithVelocity(centerRight - androidx.compose.ui.geometry.Offset(10f, 0f),
                                centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f), endVelocity = 3000f)
                        }
                        compose.mainClock.advanceTimeByFrame()
                        val before = position()
                        compose.mainClock.advanceTimeByFrame()
                        assertTrue("Fixture: the released carousel is still flinging", position() > before)
                        val name = visibleAlbum()
                        compose.onNodeWithText(name).performTouchInput { click() }
                        repeat(4) { compose.mainClock.advanceTimeByFrame() }
                        compose.runOnIdle {
                            assertEquals("The first tap stops the fling without selecting", tracks.map { it.id },
                                session.visible.map { it.track.id })
                        }
                        val stopped = position()
                        repeat(4) { compose.mainClock.advanceTimeByFrame() }
                        assertEquals("The stop tap ends carousel travel", stopped, position(), 0f)
                        compose.onNodeWithText(name).performTouchInput { click() }
                        compose.mainClock.advanceTimeByFrame()
                        compose.runOnIdle {
                            assertEquals("The next tap selects the album and filters songs",
                                tracks.filter { it.album == name }.map { it.id }, session.visible.map { it.track.id })
                        }
                    } finally { compose.mainClock.autoAdvance = true }
                    return
                }
                var previous = ""
                repeat(8) { step ->
                    val before = position()
                    carousel.performTouchInput {
                        if (step < 4) swipeLeft(durationMillis = 200) else swipeRight(durationMillis = 200)
                    }
                    compose.waitForIdle()
                    val after = position()
                    if (step == 0 || step == 4) assertTrue("Fixture: swipe $step moves the carousel", before != after)
                    val name = visibleAlbum(previous)
                    compose.onNodeWithText(name).assertIsDisplayed().performTouchInput { click() }
                    compose.runOnIdle {
                        assertEquals("Settled swipe $step ($before -> $after), tap $name filters songs",
                            tracks.filter { it.album == name }.map { it.id }, session.visible.map { it.track.id })
                    }
                    android.util.Log.i("Carousel977", "swipe=$step before=$before after=$after selected=$name")
                    previous = name
                }
                return
            }
            fun albums(chosen: List<String>) {
                compose.onNode(hasScrollToIndexAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .performScrollToIndex(0)
                val carousel = compose.onNode(hasScrollToIndexAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                // Scroll the outer grid too: an album's nearest scroll parent is only the
                // horizontal carousel, whose bottom can be under the floating actions button.
                carousel.performScrollTo()
                carousel.performScrollToNode(hasText("All albums"))
                compose.onNodeWithText("All albums").performClick()
                for ((index, name) in chosen.withIndex()) {
                    carousel.performScrollToNode(hasText(name))
                    // Finding a lazy item accepts a narrow visible sliver. Bring the actual
                    // touch target into view before long-pressing: with gallery-sized gaps,
                    // the third album's sliver can sit underneath the floating actions button.
                    val album = compose.onNodeWithText(name).performScrollTo()
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

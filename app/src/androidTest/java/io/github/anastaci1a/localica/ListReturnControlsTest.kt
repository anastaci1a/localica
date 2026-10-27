package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual list controls, not a model substitute: query, occurrence, ownership and placement. */
class ListReturnControlsTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (0..99).map { index -> Track("return:$index", "Other song ${index.toString().padStart(3, '0')}",
        fileName = "$index.mp3", relativePath = "$index.mp3") }
    private val list get() = compose.onNodeWithTag("browser-list")
    private fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun row(id: String) = compose.onNodeWithTag("media-$id")
    private fun awaitRow(id: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty()
    }

    @Before @After fun clearBrowsePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }

    private class Fixture(route: BrowseRoute, playing: String?, queues: QueueUiState) {
        var route by mutableStateOf(route)
        var active by mutableStateOf(true)
        var playerOpen by mutableStateOf(false)
        var playing by mutableStateOf(playing)
        var queues by mutableStateOf(queues)
        val selection = SessionSelection()
        var routeChanges = 0
        var playCalls = 0
        var insetPx = 0f
    }

    private fun fixture(route: BrowseRoute, songs: List<Track> = tracks,
        playlists: PlaylistState = PlaylistState(loading = false),
        queues: QueueUiState = QueueUiState(loading = false), playing: String? = null,
        inset: Dp = 0.dp, reducedMotion: Boolean = true): Fixture {
        val fixture = Fixture(route, playing, queues)
        val library = LibraryUiState(LibrarySnapshot(tracks = songs), loading = false)
        compose.setContent { LocalicaTheme {
            fixture.insetPx = with(LocalDensity.current) { inset.toPx() }
            CompositionLocalProvider(LocalSessionSelection provides fixture.selection, LocalBrowseObscuredInset provides inset) {
                Box(Modifier.fillMaxSize().testTag("return-host")) {
                    key(fixture.route.key) { BrowserScreen(library, playlists,
                        fixture.route, { fixture.routeChanges++; fixture.route = it }, {}, {}, { _, _, _ -> fixture.playCalls++ }, null, null,
                        true, fixture.playerOpen, queueState = fixture.queues, playingEntryId = fixture.playing,
                        selectionVisible = fixture.active, reducedMotion = reducedMotion) }
                }
            }
        } }
        return fixture
    }

    @Test fun activatedReturnRemovesItsRetainedHostBeforeTheListFinishes() {
        fixture(BrowseRoute(MainTab.Songs), reducedMotion = false)
        awaitRow(tracks.first().id)
        list.performScrollToIndex(65)
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed()
        compose.onNodeWithTag("floating-return-control", true).assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Return to top").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertTrue("The actual list has not reached its destination", position() > 0f)
            compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
            compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) { position() == 0f }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
    }

    @Test fun ordinaryTopReturnPreservesTheExactFilterAndQuery() {
        val original = BrowseRoute(MainTab.Library, text = "Other", filter = MediaKind.Song)
        val fixture = fixture(original)
        awaitRow(tracks.first().id)
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        list.performScrollToIndex(65)
        assertTrue(position() > 0f)
        compose.onNodeWithContentDescription("Return to top")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll up"))
            .assertHasClickAction().performClick()
        compose.waitUntil(5000) { position() == 0f }
        row(tracks.first().id).assertIsDisplayed()
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, fixture.route); assertEquals(0, fixture.routeChanges) }
    }

    @Test fun inactivePagePlayerAndSelectionRemoveTopReturnWithoutChangingItsPosition() {
        val fixture = fixture(BrowseRoute(MainTab.Songs))
        awaitRow(tracks.first().id)
        list.performScrollToIndex(65)
        val before = position()
        fun assertHiddenAndUnmoved() {
            compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
            assertEquals(before, position(), 0f)
        }
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed()
        compose.runOnIdle { fixture.active = false }
        assertHiddenAndUnmoved()
        compose.runOnIdle { fixture.active = true; fixture.playerOpen = true }
        assertHiddenAndUnmoved()
        compose.runOnIdle { fixture.playerOpen = false; fixture.selection.select(null, listOf(SongRow(tracks.first().id, tracks.first()))) }
        assertHiddenAndUnmoved()
        compose.runOnIdle { fixture.selection.clear() }
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { position() == 0f }
    }

    private fun repeatedQueue(): ListeningQueue {
        val repeated = tracks[4].copy(title = "Repeated current")
        return ListeningQueue(id = "return-queue", name = "Return queue", currentEntryId = "return-entry-90",
            entries = tracks.mapIndexed { index, track -> QueueEntry("return-entry-$index", if (index in listOf(4, 50, 90)) repeated else track) })
    }

    @Test fun activeQueueReturnReattachesToTheExactDuplicateAndLaterChangesFollow() {
        val queue = repeatedQueue()
        val fixture = fixture(BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id)),
            songs = queue.entries.map { it.track }.distinctBy { it.id },
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false), playing = queue.currentEntryId)
        awaitRow(queue.currentEntryId!!)
        row(queue.currentEntryId!!).assertIsDisplayed()
        list.performScrollToIndex(30)
        compose.onNodeWithContentDescription("Current song")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll down")).performClick()
        row("return-entry-90").assertIsDisplayed()
        row("return-entry-4").assertDoesNotExist()
        row("return-entry-50").assertDoesNotExist()
        assertTrue(position() > 0f)
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.runOnIdle { fixture.playing = "return-entry-4" }
        awaitRow("return-entry-4")
        row("return-entry-4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
    }

    @Test fun activeQueueWithFilteredOutCurrentHasNoReturnActionEvenWhenScrolled() {
        val queue = repeatedQueue()
        val original = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), text = "Other")
        val fixture = fixture(original, songs = queue.entries.map { it.track }.distinctBy { it.id },
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false), playing = queue.currentEntryId)
        awaitRow(queue.entries.first().id)
        list.performScrollToIndex(65)
        assertTrue(position() > 0f)
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, fixture.route); assertEquals(0, fixture.routeChanges) }
    }

    @Test fun inactiveQueueUsesTopReturnWithoutActivatingOrClearingItsQuery() {
        val queue = repeatedQueue()
        val original = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), text = "Other")
        val fixture = fixture(original, songs = queue.entries.map { it.track }.distinctBy { it.id },
            queues = QueueUiState(QueueLibrary(listOf(queue), activeId = null), loading = false))
        awaitRow(queue.entries.first().id)
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        list.performScrollToIndex(65)
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { position() == 0f }
        row(queue.entries.first().id).assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, fixture.route); assertEquals(0, fixture.routeChanges); assertEquals(0, fixture.playCalls); assertNull(fixture.playing) }
    }

    @Test fun settledTopAndCurrentReturnShareCollectionUtilityBaselineAboveTheBottomInset() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Aligned collection"), tracks)
        val queue = QueueEdits.create(tracks, 90, "Aligned queue")
        val fixture = fixture(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)),
            playlists = PlaylistState(listOf(playlist), loading = false),
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false), inset = 88.dp)
        awaitRow(playlist.entries.first().id)
        list.performScrollToIndex(65)
        val top = compose.onNodeWithContentDescription("Return to top").fetchSemanticsNode().boundsInRoot
        val utility = compose.onNodeWithContentDescription("Playlist actions").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("return-host").fetchSemanticsNode().boundsInRoot
        assertEquals(utility.center.y, top.center.y, 1f)
        assertEquals(utility.height, top.height, 1f)
        assertEquals(host.bottom - fixture.insetPx, top.bottom, 1f)
        compose.runOnIdle {
            fixture.route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id))
            fixture.playing = queue.entries[90].id
        }
        awaitRow(queue.entries[90].id)
        list.performScrollToIndex(30)
        val current = compose.onNodeWithContentDescription("Current song").fetchSemanticsNode().boundsInRoot
        // Retain this historical method identity; Queue now explicitly adds
        // clearance below its circular control rather than sharing the baseline.
        val queueClearance = with(compose.density) { 12.dp.toPx() }
        assertEquals(utility.center.y - queueClearance, current.center.y, 1f)
        assertEquals(host.bottom - fixture.insetPx - queueClearance, current.bottom, 1f)
    }

    @Test fun currentOccurrenceReturnContinuesAcrossAPlaybackPositionCheckpoint() =
        returnSurvivesPlaybackCheckpoint(currentOccurrence = true)

    @Test fun ordinaryTopReturnContinuesAcrossAnUnrelatedQueueCheckpoint() =
        returnSurvivesPlaybackCheckpoint(currentOccurrence = false)

    private fun returnSurvivesPlaybackCheckpoint(currentOccurrence: Boolean) {
        val queue = repeatedQueue()
        val route = if (currentOccurrence) BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id))
            else BrowseRoute(MainTab.Songs)
        val fixture = fixture(route, songs = queue.entries.map { it.track }.distinctBy { it.id },
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false),
            playing = queue.currentEntryId, reducedMotion = false)
        awaitRow(if (currentOccurrence) queue.currentEntryId!! else tracks.first().id)
        list.performScrollToIndex(if (currentOccurrence) 30 else 75)
        val before = position()
        val label = if (currentOccurrence) "Current song" else "Return to top"
        compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(label).performClick()
            compose.mainClock.advanceTimeBy(128)
            val moving = position()
            assertTrue("The return must already be moving before the checkpoint",
                if (currentOccurrence) moving > before else moving < before && moving > 0f)
            compose.runOnIdle {
                fixture.queues = fixture.queues.copy(library = fixture.queues.library.copy(
                    queues = listOf(queue.copy(positionMs = queue.positionMs + 5000))))
            }
            compose.mainClock.advanceTimeBy(800)
        } finally { compose.mainClock.autoAdvance = true }
        if (currentOccurrence) {
            row(queue.currentEntryId!!).assertIsDisplayed()
            row("return-entry-4").assertDoesNotExist()
            row("return-entry-50").assertDoesNotExist()
        } else {
            compose.waitUntil(5000) { position() == 0f }
            row(tracks.first().id).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(queue.currentEntryId, fixture.playing)
            assertEquals(queue.entries, fixture.queues.library.queues.single().entries)
        }
    }

    @Test fun currentOccurrenceReturnStopsWhenItsPageLosesOwnershipAndDoesNotResume() {
        val queue = repeatedQueue()
        val fixture = fixture(BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id)),
            songs = queue.entries.map { it.track }.distinctBy { it.id },
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false),
            playing = queue.currentEntryId, reducedMotion = false)
        awaitRow(queue.currentEntryId!!)
        row(queue.currentEntryId!!).assertIsDisplayed()
        list.performScrollToIndex(30)
        val before = position()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Current song").performClick()
            compose.mainClock.advanceTimeBy(96)
            assertTrue(position() > before)
            compose.runOnIdle { fixture.active = false }
            compose.mainClock.advanceTimeByFrame()
            val stopped = position()
            compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(800)
            assertEquals(stopped, position(), 0f)
            row(queue.currentEntryId!!).assertDoesNotExist()
            compose.runOnIdle { fixture.active = true }
            compose.mainClock.advanceTimeBy(500)
            assertEquals("Regaining the page cannot resume an obsolete scroll", stopped, position(), 0f)
            compose.onNodeWithContentDescription("Current song").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(800)
            row(queue.currentEntryId!!).assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }


    @Test fun freshListContactCancelsTopReturnBeforeItsReadinessFramesAndFreshClickStillWorks() {
        val original = BrowseRoute(MainTab.Library, filter = MediaKind.Song)
        val fixture = fixture(original)
        awaitRow(tracks.first().id)
        list.performScrollToIndex(65)
        val before = position()
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Return to top").performClick()
            // Browser readiness waits for two layout frames. Do not release either
            // before a new physical contact takes ownership of the list.
            assertEquals(before, position(), 0f)
            list.performTouchInput { down(center); cancel() }
            compose.mainClock.advanceTimeBy(800)
            assertEquals("The old top request must not start after fresh list input", before, position(), 0f)
            compose.runOnIdle { assertEquals(original, fixture.route); assertEquals(0, fixture.routeChanges) }
            compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(800)
            assertEquals(0f, position(), 0f)
            row(tracks.first().id).assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

}

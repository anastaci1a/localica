package io.github.anastaci1a.localica

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
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
        var ordinaryBack = 0
        var topRequest by mutableIntStateOf(0)
        var densityScale by mutableFloatStateOf(1f)
        val scrollProbe = BrowseScrollFrameProbe()
        var insetPx = 0f
        var dragHost: SongDragHost? = null
    }

    private fun fixture(route: BrowseRoute, songs: List<Track> = tracks,
        playlists: PlaylistState = PlaylistState(loading = false),
        queues: QueueUiState = QueueUiState(loading = false), playing: String? = null,
        inset: Dp = 0.dp, reducedMotion: Boolean = true, listBackEnabled: Boolean = false,
        foregroundDrag: Boolean = false): Fixture {
        val fixture = Fixture(route, playing, queues)
        val library = LibraryUiState(LibrarySnapshot(tracks = songs), loading = false)
        compose.setContent { LocalicaTheme {
            if (listBackEnabled) BackHandler { fixture.ordinaryBack++ }
            fixture.insetPx = with(LocalDensity.current) { inset.toPx() }
            val dragHost = if (foregroundDrag) remember { SongDragHost() } else LocalSongDragHost.current
            fixture.dragHost = dragHost
            val nativeDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(nativeDensity.density * fixture.densityScale, nativeDensity.fontScale),
                LocalBrowseScrollFrameProbe provides fixture.scrollProbe,
                LocalSessionSelection provides fixture.selection, LocalBrowseObscuredInset provides inset,
                LocalSongDragHost provides dragHost) {
                Box(Modifier.fillMaxSize().testTag("return-host")) {
                    key(fixture.route.key) { BrowserScreen(library, playlists,
                        fixture.route, { fixture.routeChanges++; fixture.route = it }, {}, {}, { _, _, _ -> fixture.playCalls++ }, null, null,
                        true, fixture.playerOpen, queueState = fixture.queues, playingEntryId = fixture.playing,
                        selectionVisible = fixture.active, reducedMotion = reducedMotion,
                        scrollToTopRequest = fixture.topRequest, listBackEnabled = listBackEnabled) }
                    if (foregroundDrag) ForegroundSongDragOverlay(requireNotNull(dragHost))
                }
            }
        } }
        return fixture
    }

    private fun assertHeaderHome(kind: MediaKind?) {
        val action = when (kind) {
            MediaKind.Album -> "Play album"
            MediaKind.Playlist -> "Play playlist"
            else -> "Play selected albums"
        }
        assertEquals("$kind: action pill and Back share a bottom edge",
            compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot().bottom.value,
            compose.onNodeWithContentDescription(action).getUnclippedBoundsInRoot().bottom.value, 1f)
    }

    @Test fun imageHeaderCollectionsReturnToMeasuredActionHomeAcrossDensities() {
        val songs = tracks.mapIndexed { index, track -> track.copy(album = "Return album", artist = "Return artist",
            albumArtist = "Return artist", trackNumber = index + 1) }
        val snapshot = LibrarySnapshot(tracks = songs)
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Return playlist"), songs)
        val routes = listOf(
            Triple(MainTab.Albums, MediaKind.Album, SongListView.Albums),
            Triple(MainTab.Artists, MediaKind.Artist, SongListView.Artists),
            Triple(MainTab.AlbumArtists, MediaKind.AlbumArtist, SongListView.AlbumArtists)
        ).map { (tab, kind, view) ->
            val group = LibraryViews.groups(snapshot, view, LibraryQuery()).single()
            BrowseRoute(tab, MediaRef(kind, group.id), group.title)
        } + BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), playlist.name)
        val state = fixture(routes.first(), songs, PlaylistState(listOf(playlist), loading = false), inset = 88.dp)
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        for (scale in listOf(.85f, 1f, 1.35f)) for ((index, route) in routes.withIndex()) {
            compose.runOnIdle {
                state.densityScale = scale
                state.route = route.copy(key = route.key + "-$scale")
                prefs.write(route, prefs.read(route).copy(quickSearch = index % 2 == 0,
                    presentation = if (index == 2) Presentation.Standard else Presentation.List,
                    itemsPerRow = 3, listSize = if (index == 3) ListSize.Smallest else ListSize.Normal), false)
            }
            compose.waitUntil(5000) { list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 1f }
            list.performScrollToIndex(65)
            compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
            assertHeaderHome(route.collection?.kind)
            assertTrue("Hero remains above the viewport", position() > 0f)
            compose.runOnIdle { assertEquals(0, state.routeChanges) }
        }
    }

    private fun presentedFrame(fixture: Fixture): BrowseScrollFrame {
        val serial = fixture.scrollProbe.serial
        compose.runOnUiThread { fixture.scrollProbe.request++ }
        compose.waitUntil(5000) { fixture.scrollProbe.serial > serial }
        return requireNotNull(fixture.scrollProbe.frame)
    }

    private fun playlistPixels(fixture: Fixture): () -> Float {
        val header = requireNotNull(presentedFrame(fixture).headerHeight)
        list.performScrollToIndex(1)
        val stride = requireNotNull(presentedFrame(fixture).rowHeight)
        return {
            val frame = presentedFrame(fixture)
            if (frame.index == 0) frame.offset.toFloat()
            else (header + (frame.index - 1) * stride + frame.offset).toFloat()
        }
    }

    @Test fun subviewBackUsesOneMonotonicEaseAllTheWayThroughTheTallHeader() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Continuous Back"), tracks)
        val state = fixture(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)),
            playlists = PlaylistState(listOf(playlist), loading = false), listBackEnabled = true, reducedMotion = false)
        awaitRow(playlist.entries.first().id)
        val headerHeight = compose.onNodeWithTag("browse-full-span", true).fetchSemanticsNode().size.height.toFloat()
        val pixels = playlistPixels(state)
        list.performScrollToIndex(65)
        compose.awaitExpectedWindowFocus(list)
        compose.mainClock.autoAdvance = false
        val samples = mutableListOf(pixels())
        try {
            pressSystemBack()
            repeat(45) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                samples += pixels()
            }
        } finally { compose.mainClock.autoAdvance = true }
        assertEquals("One ease reaches the page top", 0f, samples.last(), .01f)
        assertTrue("No reversal: $samples", samples.zipWithNext().all { (a, b) -> b <= a + .001f })
        val deltas = samples.zipWithNext().map { (a, b) -> a - b }
        val headerFrames = samples.indices.filter { samples[it] in 2f..headerHeight }
        for (index in headerFrames.drop(1)) {
            // The final approach must keep decelerating through the hero; an underestimated
            // header produces a new burst here even if semantic item indices look monotonic.
            if (index >= 2 && index < deltas.size && samples[index] < headerHeight * .8f)
                assertTrue("No second acceleration through the header: $samples",
                    deltas[index] <= deltas[index - 1] + 3f)
        }
        val first = samples.indexOfFirst { it < samples.first() - .001f }
        val last = samples.indexOfLast { it > .001f }
        assertTrue("One bounded 500 ms curve, without a second ease: $samples", last - first <= 33)
        compose.runOnIdle { assertEquals(0, state.ordinaryBack) }
    }

    @Test fun backDuringFloatingReturnRetargetsWithoutASecondReadinessGapOrEase() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Retarget Back"), tracks)
        val fixture = fixture(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)),
            playlists = PlaylistState(listOf(playlist), loading = false), listBackEnabled = true, reducedMotion = false)
        awaitRow(playlist.entries.first().id)
        val pixels = playlistPixels(fixture)
        list.performScrollToIndex(65)
        compose.awaitExpectedWindowFocus(list)
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        val samples = mutableListOf(pixels())
        try {
            compose.onNodeWithContentDescription("Return to top").performClick()
            repeat(12) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); samples += pixels()
            }
            pressSystemBack()
            repeat(33) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); samples += pixels()
            }
        } finally { compose.mainClock.autoAdvance = true }
        assertEquals(0f, samples.last(), .01f)
        assertTrue("Retargeted frames stay monotonic: $samples", samples.zipWithNext().all { (a, b) -> b <= a + .01f })
        assertTrue("Back must keep the running curve without a readiness pause: $samples",
            (12..15).all { samples[it] - samples[it + 1] > 1f })
        assertEquals("Retarget shares the original completion deadline", 0f, samples[38], .01f)
        compose.runOnIdle { assertEquals(0, fixture.ordinaryBack) }
    }

    @Test fun shortAndEmptyPlaylistsNeverOfferAnUnreachableSongReturn() {
        val short = PlaylistEdits.append(ManualPlaylist(name = "One song"), tracks.take(1))
        val empty = ManualPlaylist(name = "Empty")
        val state = fixture(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, short.id)),
            playlists = PlaylistState(listOf(short, empty), loading = false))
        awaitRow(short.entries.first().id)
        list.performScrollToIndex(1)
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        assertTrue("Short content cannot cross its song start", compose.onNodeWithTag("browser-song-list-start", true)
            .getUnclippedBoundsInRoot().top >= list.getUnclippedBoundsInRoot().top)
        compose.runOnIdle { state.route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, empty.id)) }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Empty list").fetchSemanticsNodes().isNotEmpty() }
        list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 2000f) }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.onNodeWithTag("browser-song-list-start", true).assertDoesNotExist()
    }

    @Test fun collectionSongReturnLeavesBackAndTabPageNavigationIntact() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Back return"), tracks)
        val state = fixture(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)),
            playlists = PlaylistState(listOf(playlist), loading = false), listBackEnabled = true)
        awaitRow(playlist.entries.first().id)
        fun returnToSongs() {
            list.performScrollToIndex(65)
            compose.onNodeWithContentDescription("Return to top").performClick()
            compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
            assertTrue(position() > 0f)
        }
        returnToSongs()
        compose.runOnIdle { state.topRequest++ }
        compose.waitUntil(5000) { position() == 0f }
        compose.runOnIdle { assertEquals(0, state.ordinaryBack) }
        returnToSongs()
        compose.awaitExpectedWindowFocus(list)
        pressSystemBack()
        compose.waitUntil(5000) { position() == 0f }
        compose.runOnIdle { assertEquals(0, state.ordinaryBack) }
        compose.awaitExpectedWindowFocus(list)
        pressSystemBack()
        compose.waitUntil(5000) { state.ordinaryBack == 1 }
    }

    @Test fun artistSelectionContractionRecomputesTheSongBoundary() {
        val songs = tracks.mapIndexed { index, track -> track.copy(artist = "Return artist", albumArtist = "Return artist",
            album = if (index == 99) "Single" else "Long album", trackNumber = index + 1) }
        val group = LibraryViews.groups(LibrarySnapshot(tracks = songs), SongListView.Artists, LibraryQuery()).single()
        val state = fixture(BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, group.id), group.title), songs)
        compose.waitUntil(5000) { list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 1f }
        list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100f) }
        val carousel = compose.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        carousel.performScrollToNode(hasText("Single"))
        compose.onNodeWithText("Single").performClick()
        compose.waitUntil(5000) { state.selection.visible.size == 1 }
        list.performScrollToIndex(1)
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("browser-song-list-start", true).getUnclippedBoundsInRoot().top >=
            list.getUnclippedBoundsInRoot().top)
        list.performScrollToIndex(0)
        carousel.performScrollToNode(hasText("All albums"))
        compose.onNodeWithText("All albums").performClick()
        compose.waitUntil(5000) { state.selection.visible.size == songs.size }
        list.performScrollToIndex(65)
        compose.onNodeWithContentDescription("Return to top").performClick()
        assertHeaderHome(MediaKind.Artist)
    }

    @Test fun animatedCollectionReturnStopsAtMeasuredHomeWithoutASecondHeroScroll() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Animated return"), tracks)
        val state = fixture(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)),
            playlists = PlaylistState(listOf(playlist), loading = false), reducedMotion = false)
        awaitRow(playlist.entries.first().id)
        val pixels = playlistPixels(state)
        list.performScrollToIndex(65)
        // Let the real return control enter before freezing animation time.
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Return to top").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Return to top").assertExists()
            var previous = pixels()
            repeat(45) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val current = pixels()
                assertTrue("Return stays within the page", current >= 0f)
                assertTrue("Single-direction return", current <= previous + .01f)
                previous = current
            }
            assertHeaderHome(MediaKind.Playlist)
            val settled = pixels()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            assertEquals("No follow-on ease to the hero", settled, pixels(), 0f)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
    }

    @Test fun activatedTopReturnRetainsItsHostUntilTheListReachesHome() {
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
            compose.onNodeWithContentDescription("Return to top").assertExists()
            compose.onNodeWithTag("floating-return-control", true).assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) { position() == 0f }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
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

    @Test fun queueCurrentReturnAndForegroundDragCancelShareTheirSettledHostGeometry() {
        val queue = repeatedQueue()
        val originalQueues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val fixture = fixture(BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id)),
            songs = queue.entries.map { it.track }.distinctBy { it.id }, queues = originalQueues,
            playing = queue.currentEntryId, inset = 88.dp, foregroundDrag = true)
        awaitRow(queue.currentEntryId!!)
        list.performScrollToIndex(30)
        val heldEntry = queue.entries[30]
        awaitRow(heldEntry.id)
        val returned = compose.onNodeWithContentDescription("Current song").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("return-host").fetchSemanticsNode().boundsInRoot
        val queueClearance = with(compose.density) { 12.dp.toPx() }
        assertEquals("Keep Queue's existing return clearance", host.bottom - fixture.insetPx - queueClearance,
            returned.bottom, 1.1f)
        var held = false
        var failure: Throwable? = null
        try {
            row(heldEntry.id).performTouchInput {
                down(center); held = true; advanceEventTime(650)
                moveBy(Offset(70f, 0f)); advanceEventTime(32)
            }
            compose.waitUntil(5000) { fixture.dragHost?.state?.drag != null }
            compose.onNodeWithTag("drag-foreground").assertExists()
            compose.onNodeWithTag("floating-return-control").assertDoesNotExist()
            compose.waitUntil(5000) { fixture.dragHost?.state?.drag?.cancelMotion?.progress == 1f }
            val cancel = compose.onNodeWithTag("cancel-drag").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertEquals("The same source host remains in place", host,
                compose.onNodeWithTag("return-host").fetchSemanticsNode().boundsInRoot)
            assertEquals("Queue return and drag X share their settled bottom", returned.bottom, cancel.bottom, 1.1f)
            assertEquals("Queue return and drag X share their settled center", returned.center.y, cancel.center.y, 1.1f)
            assertEquals(returned.center.x, cancel.center.x, 1.1f)
            val listBounds = list.fetchSemanticsNode().boundsInRoot
            val target = cancel.center - listBounds.topLeft
            list.performTouchInput { moveTo(target); advanceEventTime(32) }
            compose.onNodeWithTag("cancel-drag").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Cancel move"))
            list.performTouchInput { up(); held = false }
            compose.waitUntil(5000) { fixture.dragHost?.state?.drag == null }
            compose.onNodeWithTag("cancel-drag").assertDoesNotExist()
            compose.onNodeWithTag("drag-stack").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(originalQueues, fixture.queues)
                assertEquals(queue.currentEntryId, fixture.playing)
                assertEquals(0, fixture.playCalls)
                assertEquals(0, fixture.routeChanges)
                assertTrue("Cancelling keeps the selected occurrence", fixture.selection.active)
                assertEquals(listOf(heldEntry.id), fixture.selection.entries.map { it.row.id })
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (held) try {
                list.performTouchInput { cancel() }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}

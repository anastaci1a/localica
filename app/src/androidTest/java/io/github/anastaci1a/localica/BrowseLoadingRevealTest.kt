package io.github.anastaci1a.localica

import android.graphics.Bitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.QueueUiState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** #428: a loading viewport is one complete catalog, never the first available source. */
class BrowseLoadingRevealTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val track = Track("loading-song", "Loading song", album = "Complete album",
        artist = "Complete artist", fileName = "loading.mp3", relativePath = "loading.mp3")
    private val snapshot = LibrarySnapshot(tracks = listOf(track))
    private val playlist = ManualPlaylist(id = "loading-playlist", name = "Complete playlist",
        entries = listOf(SongRow("loading-entry", track)))
    private val combined = LibraryFilters(allCollections = setOf(MediaKind.Album, MediaKind.Playlist))

    @Before fun clearPreferences() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }

    @After fun restoreClockAndPreferences() {
        compose.mainClock.autoAdvance = true
        clearPreferences()
    }

    private class HeldQueries : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(): Runnable = requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) {
            "Expected a pending catalog query"
        }
    }

    private fun finishQuery(queries: HeldQueries) {
        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        queries.next().run()
        compose.waitForIdle()
    }

    @Test fun readyPlaylistsWaitForLibraryAndTheCompletedQueryPublishesBothKinds() {
        val queries = HeldQueries()
        val route = BrowseRoute(MainTab.Library)
        var library by mutableStateOf(LibraryUiState(LibrarySnapshot(), loading = true))
        val playlists = PlaylistState(listOf(playlist), loading = false)
        compose.setContent { LocalicaTheme {
            BrowserScreen(library, playlists, route,
                {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                libraryFilters = combined, queryDispatcher = queries)
        } }
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        compose.onNodeWithText(playlist.name).assertDoesNotExist()
        compose.onNodeWithTag("browser-list").assertDoesNotExist()
        assertTrue("No partial catalog query should start", queries.tasks.isEmpty())

        compose.runOnIdle { library = LibraryUiState(snapshot, loading = false) }
        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        compose.onNodeWithText("Complete album").assertDoesNotExist()
        compose.onNodeWithText(playlist.name).assertDoesNotExist()

        finishQuery(queries)
        compose.onNodeWithTag("browse-loading").assertDoesNotExist()
        compose.onNodeWithText("Complete album").assertIsDisplayed()
        compose.onNodeWithText(playlist.name).assertIsDisplayed()
    }

    @Test fun readyLibraryWaitsForRequiredPlaylistsWithoutPublishingAlbumsEarly() {
        val queries = HeldQueries()
        val route = BrowseRoute(MainTab.Library)
        val library = LibraryUiState(snapshot, loading = false)
        var playlists by mutableStateOf(PlaylistState(loading = true))
        compose.setContent { LocalicaTheme {
            BrowserScreen(library, playlists, route,
                {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                libraryFilters = combined, queryDispatcher = queries)
        } }
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        compose.onNodeWithText("Complete album").assertDoesNotExist()
        assertTrue("Albums cannot publish while a required source is loading", queries.tasks.isEmpty())

        compose.runOnIdle { playlists = PlaylistState(listOf(playlist), loading = false) }
        finishQuery(queries)
        compose.onNodeWithText("Complete album").assertIsDisplayed()
        compose.onNodeWithText(playlist.name).assertIsDisplayed()
    }

    @Test fun cachedPartialCatalogIsHiddenUntilTheFullSourceRevisionIsReady() {
        val queries = HeldQueries()
        val route = BrowseRoute(MainTab.Library)
        val emptyLibrary = LibrarySnapshot()
        val playlists = PlaylistState(listOf(playlist), loading = false)
        val queues = QueueUiState()
        val preferences = BrowsePreferences(context)
        val style = preferences.read(route)
        val sorts = preferences.sortSnapshot()
        val cache = BrowsePresentationCache()
        val partial = BrowseQueries.evaluate(emptyLibrary, playlists.playlists, route,
            style.query(), queues.library.queues, combined.allCollections, sorts)
        cache.put(emptyLibrary, playlists.playlists, queues.library.queues, combined,
            PresentedBrowse(route, style, partial,
                BrowseSourceRevision(emptyLibrary, playlists.playlists, queues.library.queues, combined), sorts))
        var library by mutableStateOf(LibraryUiState(emptyLibrary, loading = true))
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowsePresentationCache provides cache) {
                BrowserScreen(library, playlists, route, {}, {}, {}, { _, _, _ -> },
                    null, null, true, false, queueState = queues,
                    libraryFilters = combined, queryDispatcher = queries)
            }
        } }
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        compose.onNodeWithText(playlist.name).assertDoesNotExist()
        assertTrue(queries.tasks.isEmpty())

        compose.runOnIdle { library = LibraryUiState(snapshot, loading = false) }
        finishQuery(queries)
        compose.onNodeWithText("Complete album").assertIsDisplayed()
        compose.onNodeWithText(playlist.name).assertIsDisplayed()
    }

    @Test fun obsoleteAlbumQueryCannotReplaceAStillLoadingPlaylistFilter() {
        val queries = HeldQueries()
        var route by mutableStateOf(BrowseRoute(MainTab.Library, filter = MediaKind.Album))
        val library = LibraryUiState(snapshot, loading = false)
        var playlists by mutableStateOf(PlaylistState(loading = true))
        compose.setContent { LocalicaTheme {
            BrowserScreen(library, playlists, route, { route = it }, {}, {},
                { _, _, _ -> }, null, null, true, false,
                libraryFilters = combined, queryDispatcher = queries)
        } }
        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        val obsolete = queries.next()
        compose.runOnIdle { route = route.copy(filter = MediaKind.Playlist) }
        obsolete.run()
        compose.waitForIdle()
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        compose.onNodeWithText("Complete album").assertDoesNotExist()
        assertTrue("The new filter must still wait for its source", queries.tasks.isEmpty())

        compose.runOnIdle { playlists = PlaylistState(listOf(playlist), loading = false) }
        finishQuery(queries)
        compose.onNodeWithText(playlist.name).assertIsDisplayed()
        compose.onNodeWithText("Complete album").assertDoesNotExist()
    }

    @Test fun reducedMotionScreenHasStaticSkeletonAndImmediatelyFinalRowGeometry() {
        val queries = HeldQueries()
        val route = BrowseRoute(MainTab.Albums)
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2,
            quickSearch = false, extraInfo = ExtraInfo.None)
        BrowsePreferences(context).write(route, style, false)
        var library by mutableStateOf(LibraryUiState(snapshot, loading = true))
        compose.setContent { LocalicaTheme {
            BrowserScreen(library, PlaylistState(loading = false), route,
                {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                reducedMotion = true, queryDispatcher = queries)
        } }
        compose.mainClock.autoAdvance = false
        val before = compose.onNodeWithTag("browse-loading").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(550)
        val after = compose.onNodeWithTag("browse-loading").captureToImage().toPixelMap()
        assertPixelsEqual("Reduced motion must not run the loading shimmer", before, after)

        compose.runOnIdle { library = LibraryUiState(snapshot, loading = false) }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            queries.tasks.isNotEmpty()
        }
        queries.next().run()
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("browse-loading").assertDoesNotExist()
        val item = BrowseQueries.evaluate(snapshot, emptyList(), route).items.single()
        val first = compose.onNodeWithTag("media-${item.key}").fetchSemanticsNode().boundsInRoot
        val firstPixels = compose.onNodeWithTag("browse-text-${item.key}", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(1400)
        val settled = compose.onNodeWithTag("media-${item.key}").fetchSemanticsNode().boundsInRoot
        val settledPixels = compose.onNodeWithTag("browse-text-${item.key}", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        assertEquals("Reduced motion must not reserve then contract the row", first.height, settled.height, .5f)
        assertPixelsEqual("Text must already have its final opacity", firstPixels, settledPixels)
        compose.onNodeWithText("Complete album").assertIsDisplayed()
    }

    @Test fun skeletonFillsTheViewportInEveryListDensityAndGalleryColumnCount() {
        var style by mutableStateOf(BrowseStyle())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = if (style.presentation == Presentation.List) 1 else requireNotNull(style.itemsPerRow)
                    BrowseLoadingViewport(style, BrowseCellGeometry(columns),
                        constraints.maxWidth, constraints.maxHeight, 0.dp, showInformation = true)
                }
            }
        } }
        val styles = ListSize.densityOrder.map { BrowseStyle(listSize = it) } +
            (1..5).map { BrowseStyle(presentation = Presentation.Standard, itemsPerRow = it) }
        for (next in styles) {
            compose.runOnIdle { style = next }
            val viewport = compose.onNodeWithTag("browse-loading").fetchSemanticsNode().boundsInRoot
            val cells = compose.onAllNodesWithTag("browse-skeleton-item", useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Loading cells exist for $next", cells.isNotEmpty())
            assertTrue("Loading cells fill the viewport for $next",
                cells.maxOf { it.boundsInRoot.bottom } >= viewport.bottom - 1f)
            val arts = compose.onAllNodesWithTag("browse-skeleton-art", useUnmergedTree = true).fetchSemanticsNodes()
            if (next.presentation == Presentation.List && next.listSize in setOf(ListSize.Small, ListSize.Smallest)) {
                assertTrue("Dense artwork-free lists must not gain skeleton covers",
                    arts.all { it.boundsInRoot.width == 0f && it.boundsInRoot.height == 0f })
            } else {
                assertTrue("Fully visible loading artwork stays square", arts.filter {
                    it.boundsInRoot.height > 0f && it.boundsInRoot.bottom < viewport.bottom - 1f
                }
                    .all { kotlin.math.abs(it.boundsInRoot.width - it.boundsInRoot.height) <= 1f })
            }
        }
    }

    @Test fun skeletonCoverageIncludesNormalCellPaddingAtTheViewportEdge() {
        val style = BrowseStyle(listSize = ListSize.Small)
        var height by mutableIntStateOf(110)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalDensity provides Density(1f)) {
                // Small rows allocate 48dp content + 8dp card padding. Exercise bottom
                // padding, an exact row boundary, next-row top padding, then its content.
                BoxWithConstraints(Modifier.size(320.dp, height.dp)) {
                    BrowseLoadingViewport(style, BrowseCellGeometry(1),
                        constraints.maxWidth, constraints.maxHeight, 0.dp, showInformation = true)
                }
            }
        } }
        for (boundary in listOf(110, 112, 114, 118)) {
            compose.runOnIdle { height = boundary }
            val viewport = compose.onNodeWithTag("browse-loading").fetchSemanticsNode().boundsInRoot
            assertEquals("The fixture exercises its requested viewport boundary", boundary.toFloat(), viewport.height, 0f)
            val rows = compose.onAllNodesWithTag("browse-skeleton-item", useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0f }.sortedBy { it.top }
            assertTrue("The viewport must contain complete allocated row bounds at $boundary", rows.isNotEmpty())
            assertEquals("Small row allocation includes both card insets", 56f, rows.first().height, 1f)
            assertEquals("The first row starts at the viewport edge", viewport.top, rows.first().top, 1f)
            rows.zipWithNext().forEach { (above, below) ->
                assertEquals("Allocated loading rows tile without unfilled gaps", above.bottom, below.top, 1f)
            }
            assertTrue("Normal card padding remains part of viewport coverage at $boundary",
                rows.last().bottom >= viewport.bottom - 1f)
        }
    }

    @Test fun artistOnlySkeletonIsCircularWhileMixedCatalogSkeletonIsRounded() {
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Row(Modifier.width(320.dp).background(Color.Magenta)) {
                    Box(Modifier.weight(1f)) { BrowseLoadingItem(style, BrowseCellGeometry(2), true, artistOnly = false) }
                    Box(Modifier.weight(1f)) { BrowseLoadingItem(style, BrowseCellGeometry(2), true, artistOnly = true) }
                }
            }
        } }
        val covers = compose.onAllNodesWithTag("browse-skeleton-art", useUnmergedTree = true)
        val rounded = covers[0].captureToImage().toPixelMap()
        val circular = covers[1].captureToImage().toPixelMap()
        val roundedInset = paintedInset(rounded)
        val circularInset = paintedInset(circular)
        assertTrue("Rounded covers retain paint near their upper-left corner", roundedInset < rounded.width * .08f)
        assertTrue("Artist placeholders have a circular contour", circularInset > circular.width * .15f)
        assertEquals(rounded.width, circular.width)
    }

    @Test fun gallerySkeletonMatchesReservedRowsWithTwoTitleLinesAndOneInfoPlaceholder() {
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2)
        val geometry = BrowseCellGeometry(2)
        val reveal = BrowseLoadingReveal(columns = 1, initiallyComplete = false)
        val cell = reveal.cell(0).apply { place(0f, 0f) }
        val item = BrowserItem(MediaRef(MediaKind.Album, "line-reservation"), "One", "Info", emptyList())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Row(Modifier.width(320.dp)) {
                    Box(Modifier.weight(1f).testTag("skeleton-slot")) {
                        BrowseLoadingItem(style, geometry, showInformation = true)
                    }
                    Box(Modifier.weight(1f).testTag("reserved-slot")) {
                        Box(Modifier.padding(horizontal = geometry.cardInset.dp, vertical = geometry.cardVerticalInset(style).dp)) {
                            LookaheadScope {
                                BrowseItemLayout(item, style, geometry, this, true, {}, loadingReveal = cell)
                            }
                        }
                    }
                }
            }
        } }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Skeleton and loaded row start with identical reserved geometry",
            bounds("skeleton-slot").height, bounds("reserved-slot").height, 1f)
        assertEquals("Gallery loading reserves two title lines",
            bounds("browse-text-${item.key}").height * 2f, bounds("browse-skeleton-title").height, 1f)
        assertEquals("Information draws a one-line placeholder inside its larger reservation",
            bounds("browse-info-${item.key}").height, bounds("browse-skeleton-info").height, 1f)
        assertEquals(bounds("browse-skeleton-art").width, bounds("browse-art-${item.key}").width, 1f)
    }

    @Test fun newlyMountedSkeletonUsesTheAlreadyRunningSharedShimmerClock() {
        // The test policy cancels infinite animations if they start while autoAdvance is true.
        compose.mainClock.autoAdvance = false
        var showSecond by mutableStateOf(false)
        var displaySecond by mutableStateOf(false)
        var phase: State<Float>? = null
        compose.setContent { LocalicaTheme {
            val shimmer = rememberBrowseLoadingShimmer(active = true, reducedMotion = false)
            SideEffect { phase = shimmer }
            CompositionLocalProvider(LocalLoadingShimmer provides shimmer) {
                Box(Modifier.size(100.dp).testTag("shimmer-slot")) {
                    BrowseLoadingShape(Modifier.fillMaxSize().graphicsLayer { alpha = if (displaySecond) 0f else 1f }, { 0f })
                    if (showSecond) BrowseLoadingShape(
                        Modifier.fillMaxSize().graphicsLayer { alpha = if (displaySecond) 1f else 0f }, { 0f })
                }
            }
        } }
        compose.mainClock.advanceTimeBy(380)
        val clock = requireNotNull(phase)
        assertNotEquals("The shared clock must be running before mounting another skeleton", -1f, clock.value)
        val phaseBeforeMount = clock.value
        compose.runOnIdle { showSecond = true }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertSame("Mounting another skeleton cannot replace the common clock", clock, phase)
        assertTrue("Mounting another skeleton cannot restart the common phase", clock.value > phaseBeforeMount)
        val atCapture = clock.value
        // Compare both instances at exactly the same screen coordinates. Side-by-side
        // gradients can differ by one quantization step from screen-space rasterization.
        val first = compose.onNodeWithTag("shimmer-slot").captureToImage().toPixelMap()
        compose.runOnIdle { displaySecond = true } // Draw-only visibility; neither instance is unmounted.
        val second = compose.onNodeWithTag("shimmer-slot").captureToImage().toPixelMap()
        assertEquals("Capture does not advance either instance's phase", atCapture, clock.value, 0f)
        assertPixelsEqual("Late-mounted skeletons share the viewport phase", first, second)
        compose.mainClock.advanceTimeBy(400)
        val later = compose.onNodeWithTag("shimmer-slot").captureToImage().toPixelMap()
        assertTrue("The common shimmer must still be animated",
            (0 until first.height).any { y -> (0 until first.width).any { x -> first[x, y] != later[x, y] } })
    }

    @Test fun everyListDensityKeepsPlaceholderGeometryWhenShortOrMissingMetadataArrives() {
        var size by mutableStateOf(ListSize.Normal)
        var subtitle by mutableStateOf<String?>("Info")
        val geometry = BrowseCellGeometry(1)
        val reveal = BrowseLoadingReveal(columns = 1, initiallyComplete = false)
        val cell = reveal.cell(0).apply { place(0f, 0f) }
        val key = "Album:handoff"
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                val style = BrowseStyle(listSize = size)
                val item = BrowserItem(MediaRef(MediaKind.Album, "handoff"), "One", subtitle, emptyList())
                Column(Modifier.width(320.dp)) {
                    Box(Modifier.fillMaxWidth().testTag("before-ready-slot")) {
                        BrowseLoadingItem(style, geometry, showInformation = true)
                    }
                    Box(Modifier.fillMaxWidth().testTag("after-ready-slot")) {
                        Box(Modifier.padding(horizontal = geometry.cardInset.dp, vertical = geometry.cardVerticalInset(style).dp)) {
                            LookaheadScope {
                                BrowseItemLayout(item, style, geometry, this, true, {}, loadingReveal = cell)
                            }
                        }
                    }
                }
            }
        } }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        for (mode in ListSize.densityOrder) for (info in listOf("Info", null)) {
            compose.runOnIdle { size = mode; subtitle = info }
            val before = bounds("before-ready-slot")
            val after = bounds("after-ready-slot")
            assertEquals("Row height at handoff for $mode / $info", before.height, after.height, 1f)
            for (part in listOf("art", "title", "info")) {
                val loading = bounds("browse-skeleton-$part")
                val ready = bounds("browse-loading-$part-$key")
                val label = "$part handoff for $mode / $info"
                assertEquals("$label width", loading.width, ready.width, 1f)
                assertEquals("$label height", loading.height, ready.height, 1f)
                if (loading.width > 0f && loading.height > 0f) {
                    assertEquals("$label left", loading.left - before.left, ready.left - after.left, 1f)
                    assertEquals("$label top", loading.top - before.top, ready.top - after.top, 1f)
                }
            }
        }
    }

    @Test fun rowReservationWaitsForEveryCellThenContractsTogether() {
        val reveal = BrowseLoadingReveal(columns = 2, initiallyComplete = false)
        val first = reveal.cell(0).apply { place(0f, 0f) }
        val second = reveal.cell(1).apply { place(180f, 0f) }
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2)
        val short = BrowserItem(MediaRef(MediaKind.Album, "short"), "One", null, emptyList())
        val long = BrowserItem(MediaRef(MediaKind.Album, "long"),
            "A deliberately long album title that must wrap across both available title lines",
            "A deliberately long subtitle that must wrap across both available information lines", emptyList())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Row(Modifier.width(320.dp)) {
                    listOf(short to first, long to second).forEachIndexed { index, (item, cell) ->
                        Box(Modifier.weight(1f).testTag("reserved-$index")) {
                            LookaheadScope {
                                BrowseItemLayout(item, style, BrowseCellGeometry(2), this, true, {},
                                    loadingReveal = cell)
                            }
                        }
                    }
                }
            }
        } }
        fun height(index: Int) = compose.onNodeWithTag("reserved-$index").fetchSemanticsNode().boundsInRoot.height
        fun at(time: Float) { compose.runOnIdle { runBlocking { reveal.elapsed.snapTo(time) } } }
        val initial = height(0)
        assertEquals("Both cells initially reserve two title and information lines", initial, height(1), 1f)
        at(350f)
        assertEquals(1f, first.alpha, .001f)
        assertTrue(second.alpha < 1f)
        assertEquals("A completed cell cannot shorten a still-revealing row", initial, height(0), 1f)
        at(400f)
        assertEquals(1f, second.alpha, .001f)
        assertEquals("Contraction starts only after the row's last fade", initial, height(0), 1f)
        at(520f)
        val midway = height(0)
        at(640f)
        val final = height(0)
        assertTrue("Contraction must have an intermediate height", midway < initial && midway > final)
        assertTrue("Short content releases its reserved lines", final < initial)
        assertEquals("Two-line content must not be compressed", initial, height(1), 1f)
        assertEquals(0f, first.reservation, .001f)
        assertEquals(0f, second.reservation, .001f)
    }

    @Test fun fadeFramesDoNotRemeasureRowsUntilReservedHeightChanges() {
        val reveal = BrowseLoadingReveal(columns = 2, initiallyComplete = false)
        val cell = reveal.cell(0).apply { place(0f, 0f) }
        reveal.cell(1).place(180f, 0f)
        var measures = 0
        compose.setContent {
            Layout(content = {}, modifier = Modifier.width(100.dp)) { _, constraints ->
                measures++
                layout(constraints.maxWidth, (100f * cell.reservation).toInt()) { }
            }
        }
        compose.waitForIdle()
        val initial = measures
        for (time in listOf(100f, 200f, 300f, 400f)) {
            compose.runOnIdle { runBlocking { reveal.elapsed.snapTo(time) } }
            compose.waitForIdle()
            assertEquals("Unchanged reservation must not invalidate measurement", initial, measures)
        }
        compose.runOnIdle { runBlocking { reveal.elapsed.snapTo(520f) } }
        compose.waitForIdle()
        assertTrue("The contraction owns measurement only while its height changes", measures > initial)
    }

    @Test fun touchingDuringRevealFinishesOpacityWithoutJumpingTheCurrentRowHeight() {
        val reveal = BrowseLoadingReveal(columns = 2, initiallyComplete = false)
        val first = reveal.cell(0).apply { place(0f, 0f) }
        reveal.cell(1).place(180f, 0f)
        runBlocking { reveal.elapsed.snapTo(520f) }
        val reserved = first.reservation
        assertEquals(.5f, reserved, .001f)
        reveal.revealImmediately()
        assertEquals(1f, first.alpha, 0f)
        assertEquals("Interrupting does not collapse or expand the current geometry", reserved, first.reservation, 0f)
        runBlocking { reveal.elapsed.snapTo(640f) }
        assertEquals(.25f, first.reservation, .001f)
        runBlocking { reveal.elapsed.snapTo(760f) }
        assertEquals(0f, first.reservation, 0f)
    }

    @Test fun pendingArtworkKeepsOneSharedClockAliveAfterCatalogRevealThenReleasesIt() {
        // Disable the test runner's infinite-animation cancellation before composition.
        compose.mainClock.autoAdvance = false
        val activity = LoadingShimmerActivity()
        var loading by mutableStateOf(true)
        var phase: State<Float>? = null
        compose.setContent { LocalicaTheme {
            val shared = rememberBrowseLoadingShimmer(activity.count > 0, reducedMotion = false)
            SideEffect { phase = shared }
            CompositionLocalProvider(LocalLoadingShimmerActivity provides activity, LocalLoadingShimmer provides shared) {
                Row {
                    ArtworkPlaceholder(loading, Modifier.size(100.dp).testTag("late-art-one"))
                    ArtworkPlaceholder(loading, Modifier.size(100.dp).testTag("late-art-two"))
                }
            }
        } }
        compose.mainClock.advanceTimeBy(380)
        compose.runOnIdle {
            assertEquals(2, activity.count)
            assertNotNull("Pending visible covers retain the common clock", phase)
        }
        val previous = requireNotNull(phase).value
        compose.mainClock.advanceTimeBy(120)
        assertNotEquals("The retained common clock keeps advancing", previous, requireNotNull(phase).value)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { loading = false }
        compose.runOnIdle {
            assertEquals(0, activity.count)
            assertNull("Settled artwork must not keep an idle animation clock", phase)
        }
    }

    @Test fun mixedArtistArtworkAndPlaceholderShareTheSameMorphingContour() {
        val reveal = BrowseLoadingReveal(columns = 1, initiallyComplete = false)
        val cell = reveal.cell(0).apply { place(0f, 0f) }
        val uri = "test:morphing-artist"
        val artwork = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val retention = BrowseArtworkScope().apply {
            hold(Any(), listOf(uri), ArtworkCache.CachedArtwork(artwork, 1024))
        }
        val item = BrowserItem(MediaRef(MediaKind.Artist, "morphing-artist"), "Artist", null, emptyList(), coverUri = uri)
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2, extraInfo = ExtraInfo.None)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalBrowseArtwork provides retention) {
                Box(Modifier.width(160.dp).background(Color.Magenta)) {
                    LookaheadScope {
                        BrowseItemLayout(item, style, BrowseCellGeometry(2), this, true, {},
                            loadingReveal = cell, artistOnlyLoading = false)
                    }
                }
            }
        } }
        fun contour(time: Float): Pair<Int, Int> {
            compose.runOnIdle { runBlocking { reveal.elapsed.snapTo(time) } }
            val pixels = compose.onNodeWithTag("browse-art-${item.key}", useUnmergedTree = true)
                .captureToImage().toPixelMap()
            return paintedInset(pixels) to pixels.width
        }
        val start = contour(0f)
        val middle = contour(150f)
        val end = contour(300f)
        assertTrue("The starting placeholder is a rounded square", start.first < start.second * .08f)
        assertTrue("Both painted layers must round continuously during the artist fade",
            middle.first > start.first + 1 && middle.first < end.first - 1)
        assertTrue("The completed artist is circular", end.first > end.second * .15f)
    }

    /** At one tenth of the cover height a circle starts about one fifth inward. */
    private fun paintedInset(pixels: PixelMap): Int {
        val y = (pixels.height * .1f).toInt().coerceAtLeast(1)
        return (0 until pixels.width).firstOrNull { pixels[it, y].green > .2f } ?: pixels.width
    }

    private fun assertPixelsEqual(message: String, first: PixelMap, second: PixelMap) {
        assertEquals(message, first.width, second.width)
        assertEquals(message, first.height, second.height)
        for (y in 0 until first.height) for (x in 0 until first.width) {
            if (first[x, y] != second[x, y]) fail("$message at ($x, $y): ${first[x, y]} != ${second[x, y]}")
        }
    }
}

package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File
import androidx.compose.ui.geometry.Offset
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap

/** The Artist view's rows, played scope and album carousel share one album-group order (#471).
 * With Group by album off (#694), the sort orders the songs themselves and the carousel keeps album order. */
class ArtistAlbumSortOrderTest {
    @get:Rule val compose = createComposeRule()
    private var detail = ""

    /** The shared choice must not leak into other suites on the same device. */
    @org.junit.After fun restoreSharedGrouping() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("browse-style", android.content.Context.MODE_PRIVATE).edit()
            .remove(BrowsePreferenceKeys.ArtistAlbumGrouping).commit()
    }

    private fun track(artist: String, album: String, year: Int?, disc: Int, number: Int, title: String) =
        Track("order:$artist:$album:$disc:$number", title, artist = artist, albumArtist = artist, album = album,
            year = year, discNumber = disc, trackNumber = number, fileName = "$album$disc$number.mp3",
            relativePath = "$album/$disc-$number.mp3")

    /** Returns the played song titles and the carousel album names, both in displayed order. */
    private fun run(sort: TrackSort, ascending: Boolean, grouped: Boolean = true): Pair<List<String>, List<String>> {
        val artist = "Order " + UUID.randomUUID().toString().take(8)
        // Titles deliberately sort against track numbers so inner order cannot be a title or reverse sort.
        val tracks = listOf(
            track(artist, "Zeta", 1999, 1, 2, "a-second"), track(artist, "Zeta", 1999, 1, 1, "z-first"),
            track(artist, "Mid", 2005, 1, 1, "m-one"),
            track(artist, "Alpha", 2010, 2, 1, "b-disc2"), track(artist, "Alpha", 2010, 1, 1, "y-disc1"),
            track(artist, "Nameless", null, 1, 1, "n-one"))
        val snapshot = LibrarySnapshot(tracks = tracks)
        val group = LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
        val route = BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, group.id), artist)
        BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext).write(route,
            BrowseStyle(presentation = Presentation.List, listSize = ListSize.Normal, sort = sort, ascending = ascending,
                albumGrouping = grouped), true)
        var played = emptyList<Track>()
        compose.setContent { LocalicaTheme {
            Box(Modifier.requiredWidth(1400.dp).height(900.dp)) {
                BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route,
                    {}, {}, {}, { rows, _, _ -> played = rows.map { it.track } }, null, null, true, false,
                    queryDispatcher = Dispatchers.Unconfined)
            }
        } }
        compose.waitUntil(10_000) {
            // Off-screen in the wide viewport, so invoke the semantic click; repeat until the loaded rows play.
            compose.onAllNodesWithContentDescription("Play selected albums").fetchSemanticsNodes().isNotEmpty() && kotlin.run {
                compose.onNodeWithContentDescription("Play selected albums").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                played.isNotEmpty()
            }
        }
        // The semantics tree follows composition order, which is the carousel's displayed order.
        val albumNames = setOf("Zeta", "Mid", "Alpha", "Nameless")
        val names = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("album chip") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                ?.any { it.text in albumNames } == true
        }).fetchSemanticsNodes().flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }
            .filter { it in albumNames }.distinct()
        detail = "sort=$sort ascending=$ascending grouped=$grouped tree=$names"
        return played.map { it.title } to names
    }

    @Test fun yearAscendingOrdersAlbumsAndKeepsTracksAscending() {
        val (songs, albums) = run(TrackSort.Year, true)
        assertEquals(detail, listOf("Zeta", "Mid", "Alpha", "Nameless"), albums)
        assertEquals(detail, listOf("z-first", "a-second", "m-one", "y-disc1", "b-disc2", "n-one"), songs)
    }

    @Test fun yearDescendingReversesAlbumsOnlyAndLeavesMissingYearLast() {
        val (songs, albums) = run(TrackSort.Year, false)
        assertEquals(detail, listOf("Alpha", "Mid", "Zeta", "Nameless"), albums)
        assertEquals(detail, listOf("y-disc1", "b-disc2", "m-one", "z-first", "a-second", "n-one"), songs)
    }

    @Test fun titleDescendingFollowsTheAlbumTitleWithAscendingTracks() {
        val (songs, albums) = run(TrackSort.Title, false)
        assertEquals(detail, listOf("Zeta", "Nameless", "Mid", "Alpha"), albums)
        assertEquals(detail, listOf("z-first", "a-second", "n-one", "m-one", "y-disc1", "b-disc2"), songs)
    }

    @Test fun ungroupedTitleOrdersSongsAcrossAlbumsWhileTheCarouselKeepsAlbumOrder() {
        val (songs, albums) = run(TrackSort.Title, true, grouped = false)
        assertEquals(detail, listOf("Alpha", "Mid", "Nameless", "Zeta"), albums)
        assertEquals(detail, listOf("a-second", "b-disc2", "m-one", "n-one", "y-disc1", "z-first"), songs)
    }

    @Test fun ungroupedYearDescendingOrdersSongsByYearThenTitleWithMissingYearLast() {
        val (songs, albums) = run(TrackSort.Year, false, grouped = false)
        assertEquals(detail, listOf("Alpha", "Mid", "Zeta", "Nameless"), albums)
        assertEquals(detail, listOf("b-disc2", "y-disc1", "m-one", "a-second", "z-first", "n-one"), songs)
    }

    @Test fun groupByAlbumIsOneSharedPreferenceThatSurvivesANewPreferencesInstance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artist = BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, "persist-" + UUID.randomUUID()), "A")
        val albumArtist = BrowseRoute(MainTab.AlbumArtists, MediaRef(MediaKind.AlbumArtist, "persist-other"), "B")
        val album = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "persist-album"), "C")
        try {
            BrowsePreferences(context).write(artist, BrowseStyle(sort = TrackSort.Title, albumGrouping = false), true)
            // A fresh instance reads the stored file, as after a process restart.
            val restarted = BrowsePreferences(context)
            assertFalse(restarted.read(artist).albumGrouping)
            assertFalse(restarted.read(albumArtist).albumGrouping)
            assertFalse(restarted.sortSnapshot().artistAlbumGrouping)
            // Non-artist views never write or change it.
            restarted.write(album, BrowseStyle(albumGrouping = true), true)
            assertFalse(BrowsePreferences(context).sortSnapshot().artistAlbumGrouping)
            // Clearing a per-list override keeps the shared choice.
            restarted.reset(artist)
            assertFalse(BrowsePreferences(context).read(artist).albumGrouping)
            restarted.write(albumArtist, restarted.read(albumArtist).copy(albumGrouping = true), true)
            assertTrue(BrowsePreferences(context).read(artist).albumGrouping)
        } finally {
            BrowsePreferences(context).run { reset(artist); reset(albumArtist); reset(album) }
            context.getSharedPreferences("browse-style", android.content.Context.MODE_PRIVATE).edit()
                .remove(BrowsePreferenceKeys.ArtistAlbumGrouping).commit()
        }
    }

    // #694: toggling Group by album through the real Sort dialog in list and gallery layouts.
    // Records a frame strip and per-frame row positions; selection, layout and carousel order survive.
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun listTogglesOffAndBackOnThroughTheSortDialog() = toggle(Presentation.List, "list")

    @Test fun galleryTogglesOffAndBackOnThroughTheSortDialog() = toggle(Presentation.Standard, "gallery")

    private fun toggle(presentation: Presentation, name: String) {
        val artist = "Group " + UUID.randomUUID().toString().take(8)
        val tracks = listOf(
            track(artist, "Zeta", 1999, 1, 2, "a-second"), track(artist, "Zeta", 1999, 1, 1, "z-first"),
            track(artist, "Mid", 2005, 1, 1, "m-one"),
            track(artist, "Alpha", 2010, 2, 1, "b-disc2"), track(artist, "Alpha", 2010, 1, 1, "y-disc1"),
            track(artist, "Nameless", null, 1, 1, "n-one"))
        val snapshot = LibrarySnapshot(tracks = tracks)
        val group = LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
        val ref = MediaRef(MediaKind.Artist, group.id)
        val route = BrowseRoute(MainTab.Artists, ref, artist)
        val preferences = BrowsePreferences(context)
        preferences.write(route, BrowseStyle(presentation = presentation, listSize = ListSize.Normal,
            sort = TrackSort.Title, ascending = true, albumGrouping = true), true)
        val grouped = listOf("y-disc1", "b-disc2", "m-one", "n-one", "z-first", "a-second")
        val ungrouped = listOf("a-second", "b-disc2", "m-one", "n-one", "y-disc1", "z-first")
        val session = SessionSelection()
        var sorting by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSessionSelection provides session) {
                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route,
                        {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                        sorting = sorting, onSortDismiss = { sorting = false },
                        queryDispatcher = Dispatchers.Unconfined)
                }
            }
        } }
        val selected = SongRow(tracks.single { it.title == "m-one" }.id, tracks.single { it.title == "m-one" })
        fun visible() = compose.runOnIdle { session.visible.map { it.track.title } }
        // The bound selection scope is the displayed row order, independent of what is on screen.
        compose.waitUntil(10_000) { visible() == grouped }
        val albumNames = setOf("Zeta", "Mid", "Alpha", "Nameless")
        fun carousel() = compose.onAllNodes(SemanticsMatcher("album chip") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text in albumNames } == true
        }).fetchSemanticsNodes().flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }
            .filter { it in albumNames }.distinct()
        val albumOrder = listOf("Alpha", "Mid", "Nameless", "Zeta")
        // Only the leading covers are composed at phone width; the full carousel order is proven by the play tests above.
        carousel().let { shown -> assertTrue("Carousel prefix $shown", shown.isNotEmpty() && shown == albumOrder.take(shown.size)) }
        compose.runOnIdle { session.select(ref, listOf(selected)) }
        // Bring the songs beneath the artwork header into the viewport for the frame strip.
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * .85f, endY = top + bottom * .15f) }
        compose.waitForIdle()
        fun titleBounds(title: String) = (compose.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes() +
            compose.onAllNodesWithText(title).fetchSemanticsNodes()).firstOrNull()?.boundsInRoot

        fun flip(target: Boolean, expected: List<String>, label: String) {
            compose.runOnIdle { sorting = true }
            val toggle = hasText("Group by album") and isToggleable()
            compose.waitUntil(10_000) { compose.onAllNodes(toggle).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(toggle).assertIsDisplayed().apply { if (target) assertIsOff() else assertIsOn() }.performClick()
            compose.waitUntil(5_000) { runCatching { compose.onNode(toggle).apply { if (target) assertIsOn() else assertIsOff() } }.isSuccess }
            compose.onNode(hasText("Save") and hasClickAction()).performClick()
            // One frame strip per toggle, sampled at every frame from Save until the order settles.
            compose.mainClock.autoAdvance = false
            val frames = mutableListOf<Bitmap>()
            val positions = StringBuilder()
            var settledFrames = 0
            for (frame in 0 until 90) {
                compose.mainClock.advanceTimeByFrame()
                val rows = ungrouped.mapNotNull { title -> titleBounds(title)?.let { title to it } }
                positions.append(frame).append(' ').append(rows.joinToString(" ") { (title, bounds) ->
                    "$title@${bounds.left.toInt()},${bounds.top.toInt()}" }).append('\n')
                if (frame % 2 == 0 && frames.size < 20) screen()?.let { frames += it }
                if (compose.runOnIdle { session.visible.map { it.track.title } } == expected) settledFrames++
                if (settledFrames > 20) break
            }
            screen()?.let { frames += it }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10_000) { visible() == expected }
            save("$name-$label", frames, positions.toString())
            assertEquals(target, BrowsePreferences(context).sortSnapshot().artistAlbumGrouping)
            assertEquals(target, BrowsePreferences(context).read(route).albumGrouping)
            assertEquals("Layout survives the toggle", presentation, BrowsePreferences(context).read(route).presentation)
            assertTrue("Selection survives the toggle", compose.runOnIdle { session.active && session.contains(ref, selected.id) })
            assertEquals("Only the selected song stays selected", 1, compose.runOnIdle { session.entries.size })
        }
        flip(false, ungrouped, "off")
        flip(true, grouped, "on")
    }

    // #694 reorder motion: artist rows glide through the existing keyed placement tween (animateItem)
    // for sort, Group by album and album-filter changes; the inner animateBounds must not add a second
    // tail. Body tops ("media-<key>", inside both modifiers) are traced every frame from the change.
    private class Trace(val label: String, val frames: List<Map<String, Float>>) {
        val moved: List<String> get() = frames.first().keys.filter { key ->
            frames.last()[key] != null && kotlin.math.abs(frames.last().getValue(key) - frames.first().getValue(key)) > 2f }
        /** Last frame index at which any traced row still moved. */
        val lastMotion: Int get() = frames.indices.drop(1).lastOrNull { i ->
            frames[i].any { (key, top) -> frames[i - 1][key]?.let { kotlin.math.abs(it - top) > .5f } == true } } ?: 0
        val firstMotion: Int get() = frames.indices.drop(1).firstOrNull { i ->
            frames[i].any { (key, top) -> frames[i - 1][key]?.let { kotlin.math.abs(it - top) > .5f } == true } } ?: 0
        /** Frames strictly between a moved row's start and end positions. */
        fun intermediate(key: String): Int {
            val start = frames.first().getValue(key); val end = frames.last().getValue(key)
            return frames.count { it[key]?.let { top -> top in minOf(start, end) + 1f..maxOf(start, end) - 1f } == true }
        }
        /** Every moved row travels monotonically: no overshoot or return (a second tail would show here). */
        fun monotonic(key: String): Boolean {
            val tops = frames.mapNotNull { it[key] }
            val sign = kotlin.math.sign(tops.last() - tops.first())
            return tops.zipWithNext().all { (a, b) -> (b - a) * sign >= -.5f }
        }
        override fun toString() = label + "\n" + frames.mapIndexed { i, f ->
            "$i " + f.entries.sortedBy { it.key }.joinToString(" ") { "${it.key.takeLast(10)}@${it.value.toInt()}" } }.joinToString("\n")
    }

    private inner class MotionFixture(presentation: Presentation, reduced: Boolean = false, val searchText: String = "") {
        val artist = "Motion " + UUID.randomUUID().toString().take(8)
        val tracks = listOf(
            track(artist, "Zeta", 1999, 1, 2, "a-second"), track(artist, "Zeta", 1999, 1, 1, "z-first"),
            track(artist, "Mid", 2005, 1, 1, "m-one"),
            track(artist, "Alpha", 2010, 2, 1, "b-disc2"), track(artist, "Alpha", 2010, 1, 1, "y-disc1"),
            track(artist, "Nameless", null, 1, 1, "n-one"))
        val snapshot = LibrarySnapshot(tracks = tracks)
        val ref = MediaRef(MediaKind.Artist, LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single().id)
        val baseRoute = BrowseRoute(MainTab.Artists, ref, artist)
        var route by mutableStateOf(baseRoute.copy(text = searchText))
        val preferences = BrowsePreferences(context)
        var style = BrowseStyle(presentation = presentation, listSize = ListSize.Normal, sort = TrackSort.Title, ascending = true)
        val session = SessionSelection()
        init {
            preferences.write(baseRoute, style.copy(albumGrouping = true), true)
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalSessionSelection provides session) {
                    Box(Modifier.fillMaxSize()) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route,
                            { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                            reducedMotion = reduced, queryDispatcher = Dispatchers.Unconfined)
                    }
                }
            } }
            compose.waitUntil(10_000) { visible().size == if (searchText.isBlank()) 6 else visible().size.coerceAtLeast(1) }
            compose.waitForIdle()
        }
        fun visible() = compose.runOnIdle { session.visible.map { it.track.title } }
        fun write(next: BrowseStyle) { style = next; preferences.write(baseRoute, next, true) }
        fun tops(): Map<String, Float> = tracks.mapNotNull { t ->
            compose.onAllNodesWithTag("media-${t.id}", useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull()?.boundsInRoot?.let { t.title to it.top } }.toMap()
        fun scrollSongsIntoView() {
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * .85f, endY = top + bottom * .15f) }
            compose.waitForIdle()
        }
        /** Runs [change] with a paused clock and records every frame for [count] frames. */
        fun trace(label: String, count: Int = 45, midway: Pair<Int, () -> Unit>? = null, change: () -> Unit): Trace {
            compose.mainClock.autoAdvance = false
            val frames = mutableListOf(tops())
            val shots = mutableListOf<Bitmap>()
            try {
                change()
                for (frame in 1..count) {
                    if (midway?.first == frame) midway.second()
                    compose.mainClock.advanceTimeByFrame()
                    frames += tops()
                    if (frame % 3 == 1 && shots.size < 14) screen()?.let { shots += it }
                }
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            return Trace(label, frames).also { save("motion-$label", shots, it.toString()) }
        }
    }

    /** One glide: 350 ms at 16 ms frames is about 22 frames; allow the change's own dispatch frames. */
    private fun assertGlide(trace: Trace, rows: Int = 2) {
        val detail = trace.toString()
        assertTrue("Rows move: $detail", trace.moved.size >= rows)
        trace.moved.forEach { key ->
            assertTrue("$key glides through intermediate frames: $detail", trace.intermediate(key) >= 6)
            assertTrue("$key travels without a second tail or overshoot: $detail", trace.monotonic(key))
        }
        assertTrue("Motion ends within one 350 ms tween (no second tail): first=${trace.firstMotion} last=${trace.lastMotion}\n$detail",
            trace.lastMotion - trace.firstMotion <= 26)
    }

    private fun assertJump(trace: Trace) {
        val detail = trace.toString()
        assertTrue("Rows reorder: $detail", trace.moved.isNotEmpty())
        assertTrue("Reordered without a glide: first=${trace.firstMotion} last=${trace.lastMotion}\n$detail",
            trace.lastMotion - trace.firstMotion <= 2)
    }

    @Test fun listGlidesForGroupSortFilterAndRetargetWithOneTween() {
        val f = MotionFixture(Presentation.List)
        f.scrollSongsIntoView()
        assertGlide(f.trace("list-group-off") { f.write(f.style.copy(albumGrouping = false)) })
        assertEquals(listOf("a-second", "b-disc2", "m-one", "n-one", "y-disc1", "z-first"), f.visible())
        assertGlide(f.trace("list-sort-descending") { f.write(f.style.copy(ascending = false)) })
        assertEquals(listOf("z-first", "y-disc1", "n-one", "m-one", "b-disc2", "a-second"), f.visible())
        // Rapid retarget: turn grouping back on mid-glide; rows turn toward the new target without a jump.
        val retarget = f.trace("list-retarget", count = 60, midway = 6 to { f.write(f.style.copy(albumGrouping = true)) }) {
            f.write(f.style.copy(ascending = true))
        }
        val detail = retarget.toString()
        retarget.frames.zipWithNext().forEach { (a, b) -> a.forEach { (key, top) -> b[key]?.let { next ->
            assertTrue("$key steps at most one row height per frame: $detail", kotlin.math.abs(next - top) < 200f) } } }
        assertTrue("Retargeted motion settles: $detail", retarget.lastMotion < 50)
        assertEquals(listOf("y-disc1", "b-disc2", "m-one", "n-one", "z-first", "a-second"), f.visible())
    }

    @Test fun albumFilterGlidesSurvivingRowsWithOneTween() {
        val f = MotionFixture(Presentation.List)
        f.scrollSongsIntoView()
        // After scrolling, only the leading covers (Alpha, Mid, Nameless) are composed; Mid is third.
        val chip = hasText("Mid") and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodes(chip).fetchSemanticsNodes().isNotEmpty() }
        val trace = f.trace("list-filter-mid") {
            compose.onAllNodes(chip).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }
        // Mid's row was third; it glides up to the top while the others leave.
        val detail = trace.toString()
        listOf("m-one").forEach { key ->
            assertTrue("$key survives and moves up: $detail", trace.frames.last()[key]!! < trace.frames.first()[key]!! - 2f)
            assertTrue("$key glides: $detail", trace.intermediate(key) >= 6)
            assertTrue("$key has no second tail: $detail", trace.monotonic(key))
        }
        assertTrue("One tween: $detail", trace.lastMotion - trace.firstMotion <= 26)
    }

    @Test fun galleryGlidesForGroupAndSortWithOneTween() {
        val f = MotionFixture(Presentation.Standard)
        f.scrollSongsIntoView()
        assertGlide(f.trace("gallery-group-off") { f.write(f.style.copy(albumGrouping = false)) })
        assertGlide(f.trace("gallery-sort-descending") { f.write(f.style.copy(ascending = false)) })
        assertEquals(listOf("z-first", "y-disc1", "n-one", "m-one", "b-disc2", "a-second"), f.visible())
    }

    @Test fun reducedMotionAndNonblankSearchReorderWithoutAGlide() {
        val reduced = MotionFixture(Presentation.List, reduced = true)
        reduced.scrollSongsIntoView()
        assertJump(reduced.trace("reduced-group-off") { reduced.write(reduced.style.copy(albumGrouping = false)) })
        assertEquals(listOf("a-second", "b-disc2", "m-one", "n-one", "y-disc1", "z-first"), reduced.visible())
    }

    @Test fun nonblankSearchReordersWithoutAGlide() {
        // "-" matches every title, so all six rows stay while search text is nonblank.
        val f = MotionFixture(Presentation.List, searchText = "-")
        compose.waitUntil(10_000) { f.visible().size == 6 }
        f.scrollSongsIntoView()
        assertJump(f.trace("search-group-off") { f.write(f.style.copy(albumGrouping = false)) })
    }

    @Test fun pinchDuringAGlideTakesOverAndLandsInTheNewOrder() {
        val f = MotionFixture(Presentation.List)
        f.scrollSongsIntoView()
        compose.mainClock.autoAdvance = false
        try {
            f.write(f.style.copy(albumGrouping = false))
            repeat(5) { compose.mainClock.advanceTimeByFrame() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("browser-list").performTouchInput {
            val y = centerY
            down(0, Offset(centerX - 50f, y)); down(1, Offset(centerX + 50f, y))
            repeat(8) { n -> val d = 50f + 60f * (n + 1) / 8f
                moveTo(0, Offset(centerX - d, y), delayMillis = 24); moveTo(1, Offset(centerX + d, y), delayMillis = 24) }
            up(0); up(1)
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { BrowsePreferences(context).read(f.baseRoute).let { it.presentation != Presentation.List || it.listSize != ListSize.Normal } }
        assertEquals(listOf("a-second", "b-disc2", "m-one", "n-one", "y-disc1", "z-first"), f.visible())
        val settled = f.tops()
        compose.mainClock.autoAdvance = false
        try { repeat(30) { compose.mainClock.advanceTimeByFrame() } } finally { compose.mainClock.autoAdvance = true }
        assertEquals("No late reorder tail after the pinch lands", settled, f.tops())
    }

    /** Native window pixels: the closing dialog is a second root, so a Compose root capture is ambiguous.
     * API 26 can refuse rapid screenshots; then the content root is captured, or the frame is skipped. */
    private fun screen(): Bitmap? {
        compose.waitForIdle()
        return InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: runCatching { compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap() }.getOrNull()
    }

    private fun save(name: String, frames: List<Bitmap>, positions: String) {
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))).let { File(it, "artist-group-694") }.apply { mkdirs() }
        val api = android.os.Build.VERSION.SDK_INT
        File(root, "$name-api$api-positions.txt").writeText(positions + "frames=" + frames.size + "\n")
        if (frames.isEmpty()) return
        val scale = .35f
        val width = (frames.first().width * scale).toInt()
        val height = (frames.first().height * scale).toInt()
        val strip = Bitmap.createBitmap(width * frames.size, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        frames.forEachIndexed { i, frame ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(frame, width, height, true), (i * width).toFloat(), 0f, null)
        }
        File(root, "$name-api$api.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

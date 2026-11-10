package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #769: scrolling an album, artist or playlist subview through its header moves every row
 * monotonically, and the header's measured height is a pure function of the scroll offset
 * (it never reacts to the list). One frame is one stepped main-clock frame of a real drag or fling. */
class SubviewScrollJitterTest {
    @get:Rule val compose = createComposeRule()

    private class Frame(val header: Pair<Float, Float>?, val rows: Map<String, Float>)

    private fun track(i: Int, artist: String, album: String) = Track("jitter:$artist:$album:$i",
        "Song ${i.toString().padStart(3, '0')}", artist = artist, albumArtist = artist, album = album,
        trackNumber = i, fileName = "$album$i.mp3", relativePath = "$album/$i.mp3")

    @Test fun albumSubviewScrollsMonotonically() {
        val tracks = (1..48).map { track(it, "Jitter Artist", "Jitter Album") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("jitter-root", "Music")), tracks = tracks)
        val group = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        verify("album", snapshot, PlaylistState(), BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, group.id), "Jitter Album"))
    }

    @Test fun artistSubviewScrollsMonotonically() {
        val tracks = (0..7).flatMap { a -> (1..6).map { track(it, "Jitter Artist", "Album $a") } }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("jitter-root", "Music")), tracks = tracks)
        val group = LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
        verify("artist", snapshot, PlaylistState(), BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, group.id), "Jitter Artist"))
    }

    @Test fun playlistSubviewScrollsMonotonically() {
        val tracks = (1..48).map { track(it, "Jitter Artist $it", "Jitter Album ${it / 6}") }
        val playlist = ManualPlaylist(id = "jitter-playlist", name = "Jitter playlist",
            entries = tracks.mapIndexed { i, t -> SongRow("jitter-entry-$i", t) })
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("jitter-root", "Music")), tracks = tracks)
        verify("playlist", snapshot, PlaylistState(listOf(playlist), loading = false),
            BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), playlist.name))
    }

    private fun sample(): Frame {
        val header = compose.onAllNodesWithTag("browse-full-span", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.let { it.boundsInRoot.top to it.boundsInRoot.height }
        val rows = compose.onAllNodes(SemanticsMatcher("media row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().associate {
            it.config[SemanticsProperties.TestTag] to it.boundsInRoot.top
        }
        return Frame(header, rows)
    }

    private fun step(): Frame { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); return sample() }

    private fun verify(label: String, snapshot: LibrarySnapshot, playlists: PlaylistState, route: BrowseRoute) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = prefs.read(route); val overridden = prefs.overridden(route)
        prefs.write(route, BrowseStyle(presentation = Presentation.List, listSize = ListSize.Normal), true)
        try {
            compose.setContent { LocalicaTheme { Box(Modifier.requiredSize(412.dp, 800.dp)) {
                BrowserScreen(LibraryUiState(snapshot, loading = false), playlists, route, {}, {}, {}, { _, _, _ -> },
                    null, null, true, false, queryDispatcher = Dispatchers.Unconfined)
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("browse-full-span", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val list = compose.onNodeWithTag("browser-list")
            val frames = mutableListOf<Frame>()
            compose.mainClock.autoAdvance = false
            // Slow held drag: 9 px per frame upward through the whole header.
            list.performTouchInput { down(center) }
            frames += step()
            repeat(150) {
                list.performTouchInput { moveBy(Offset(0f, -9f), delayMillis = 16) }
                frames += step()
            }
            list.performTouchInput { up() }
            repeat(60) { frames += step() }
            val dragFrames = frames.size
            // Back to the top, then one fling through the header and beyond.
            compose.mainClock.autoAdvance = true
            list.performScrollToIndex(0)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            frames += step()
            list.performTouchInput { swipe(Offset(centerX, bottom - 120f), Offset(centerX, top + 120f), 90) }
            repeat(150) { frames += step() }

            val problems = mutableListOf<String>()
            // Each pass (drag, fling) is checked on its own; the jump between them is a programmatic scroll.
            listOf(0 until dragFrames, dragFrames + 1 until frames.size).forEach { range ->
                for (i in range.first + 1..range.last) {
                    val a = frames[i - 1]; val b = frames[i]
                    for ((tag, y) in b.rows) {
                        val before = a.rows[tag] ?: continue
                        if (y - before > .5f) problems += "frame $i: $tag stepped back ${y - before}px ($before -> $y)"
                    }
                    val ha = a.header; val hb = b.header
                    if (ha != null && hb != null && hb.first - ha.first > .5f) problems += "frame $i: header stepped back ${hb.first - ha.first}px"
                }
            }
            // Header height is a pure function of the offset: one height for every sampled offset, across both passes.
            val heights = frames.mapNotNull { f -> f.header?.let { (-it.first).toInt() to it.second } }
            val byOffset = heights.groupBy({ it.first }, { it.second })
            byOffset.forEach { (offset, h) -> if (h.max() - h.min() > .5f) problems += "header height differs at offset $offset: $h" }
            val distinct = heights.map { it.second }.distinct()
            if (distinct.size > 1 && distinct.max() - distinct.min() > .5f) problems += "header height varies with scroll: $distinct"
            // Rows move rigidly with the header while both are visible: their gap is constant.
            val gaps = frames.mapIndexedNotNull { i, f ->
                val header = f.header ?: return@mapIndexedNotNull null
                val bottom = header.first + header.second
                f.rows.values.filter { it >= bottom - 1f }.minOrNull()?.let { i to it - bottom }
            }
            gaps.firstOrNull()?.let { (_, base) ->
                gaps.forEach { (i, gap) -> if (kotlin.math.abs(gap - base) > 1f) problems += "frame $i: first row gap to header $gap (expected $base)" }
            }
            assertTrue("$label subview jitter (${frames.size} frames, ${frames.count { it.header != null }} with header):\n" +
                problems.take(25).joinToString("\n"), problems.isEmpty())
            compose.mainClock.autoAdvance = true
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, original, overridden) }
    }
}

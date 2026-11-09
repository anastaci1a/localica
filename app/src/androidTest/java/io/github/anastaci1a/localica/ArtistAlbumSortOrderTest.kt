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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("y-disc1").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { session.select(ref, listOf(selected)) }
        fun visible() = compose.runOnIdle { session.visible.map { it.track.title } }
        compose.waitUntil(5_000) { visible() == grouped }
        val albumNames = setOf("Zeta", "Mid", "Alpha", "Nameless")
        fun carousel() = compose.onAllNodes(SemanticsMatcher("album chip") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text in albumNames } == true
        }).fetchSemanticsNodes().flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }
            .filter { it in albumNames }.distinct()
        val albumOrder = listOf("Alpha", "Mid", "Nameless", "Zeta")
        assertEquals(albumOrder, carousel())

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
                val rows = ungrouped.mapNotNull { title ->
                    compose.onAllNodesWithText(title).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let { title to it }
                }
                positions.append(frame).append(' ').append(rows.joinToString(" ") { (title, bounds) ->
                    "$title@${bounds.left.toInt()},${bounds.top.toInt()}" }).append('\n')
                if (frame % 3 == 0 && frames.size < 16) frames += compose.onRoot().captureToImage().asAndroidBitmap()
                if (compose.runOnIdle { session.visible.map { it.track.title } } == expected) settledFrames++
                if (settledFrames > 20) break
            }
            frames += compose.onRoot().captureToImage().asAndroidBitmap()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10_000) { visible() == expected }
            save("$name-$label", frames, positions.toString())
            assertEquals(target, BrowsePreferences(context).sortSnapshot().artistAlbumGrouping)
            assertEquals(target, BrowsePreferences(context).read(route).albumGrouping)
            assertEquals("Layout survives the toggle", presentation, BrowsePreferences(context).read(route).presentation)
            assertTrue("Selection survives the toggle", compose.runOnIdle { session.active && session.contains(ref, selected.id) })
            assertEquals("Only the selected song stays selected", 1, compose.runOnIdle { session.entries.size })
            assertEquals("The carousel keeps album order", albumOrder, carousel())
        }
        flip(false, ungrouped, "off")
        flip(true, grouped, "on")
    }

    private fun save(name: String, frames: List<Bitmap>, positions: String) {
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))).let { File(it, "artist-group-694") }.apply { mkdirs() }
        val scale = .35f
        val width = (frames.first().width * scale).toInt()
        val height = (frames.first().height * scale).toInt()
        val strip = Bitmap.createBitmap(width * frames.size, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        frames.forEachIndexed { i, frame ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(frame, width, height, true), (i * width).toFloat(), 0f, null)
        }
        val api = android.os.Build.VERSION.SDK_INT
        File(root, "$name-api$api.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(root, "$name-api$api-positions.txt").writeText(positions)
    }
}

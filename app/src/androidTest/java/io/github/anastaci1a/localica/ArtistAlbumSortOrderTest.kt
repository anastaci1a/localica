package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

/** The Artist view's rows, played scope and album carousel share one album-group order (#471). */
class ArtistAlbumSortOrderTest {
    @get:Rule val compose = createComposeRule()

    private fun track(artist: String, album: String, year: Int?, disc: Int, number: Int, title: String) =
        Track("order:$artist:$album:$disc:$number", title, artist = artist, albumArtist = artist, album = album,
            year = year, discNumber = disc, trackNumber = number, fileName = "$album$disc$number.mp3",
            relativePath = "$album/$disc-$number.mp3")

    /** Returns the played song titles and the carousel album names, both in displayed order. */
    private fun run(sort: TrackSort, ascending: Boolean): Pair<List<String>, List<String>> {
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
            BrowseStyle(presentation = Presentation.List, listSize = ListSize.Normal, sort = sort, ascending = ascending), true)
        var played = emptyList<Track>()
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxWidth().height(900.dp)) {
                BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route,
                    {}, {}, {}, { rows, _, _ -> played = rows.map { it.track } }, null, null, true, false,
                    queryDispatcher = Dispatchers.Unconfined)
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play selected albums").performClick()
        val carousel = listOf("Zeta", "Mid", "Alpha", "Nameless").map { name ->
            // The first match is the carousel chip, which sits above every song row.
            val node = compose.onAllNodesWithText(name)[0].fetchSemanticsNode().boundsInRoot
            name to node
        }.sortedWith(compareBy({ it.second.top }, { it.second.left })).map { it.first }
        return played.map { it.title } to carousel
    }

    @Test fun yearAscendingOrdersAlbumsAndKeepsTracksAscending() {
        val (songs, albums) = run(TrackSort.Year, true)
        assertEquals(listOf("Zeta", "Mid", "Alpha", "Nameless"), albums)
        assertEquals(listOf("z-first", "a-second", "m-one", "y-disc1", "b-disc2", "n-one"), songs)
    }

    @Test fun yearDescendingReversesAlbumsOnlyAndLeavesMissingYearLast() {
        val (songs, albums) = run(TrackSort.Year, false)
        assertEquals(listOf("Alpha", "Mid", "Zeta", "Nameless"), albums)
        assertEquals(listOf("y-disc1", "b-disc2", "m-one", "z-first", "a-second", "n-one"), songs)
    }

    @Test fun titleDescendingFollowsTheAlbumTitleWithAscendingTracks() {
        val (songs, albums) = run(TrackSort.Title, false)
        assertEquals(listOf("Zeta", "Nameless", "Mid", "Alpha"), albums)
        assertEquals(listOf("z-first", "a-second", "n-one", "m-one", "y-disc1", "b-disc2"), songs)
    }
}

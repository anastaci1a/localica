package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class BrowseQueriesTest {
    private val tracks = listOf(
        Track("one", "First", album = "Album", albumArtist = "Group", trackNumber = 2, fileName = "one.mp3", relativePath = "one.mp3"),
        Track("two", "Second", album = "Album", albumArtist = "Group", trackNumber = 1, fileName = "two.mp3", relativePath = "two.mp3"))
    private val library = LibrarySnapshot(tracks = tracks)
    @Test fun mixedSearchIncludesCollectionsButQueueSnapshotContainsOnlyDisplayedSongs() {
        val route = BrowseRoute(MainTab.Library, search = true, globalSearch = true, text = "Album")
        val result = BrowseQueries.evaluate(library, emptyList(), route)
        assertEquals(3, result.items.size)
        assertEquals(listOf("one", "two"), result.rows.map { it.id })
        assertEquals(SongListView.Search, result.scope)
    }
    @Test fun albumsDefaultToTrackOrderAndSearchStaysWithinCollection() {
        val album = BrowseQueries.evaluate(library, emptyList(), BrowseRoute(MainTab.Albums)).items.single()
        val detail = BrowseRoute(MainTab.Albums, album.ref)
        assertEquals(listOf("two", "one"), BrowseQueries.evaluate(library, emptyList(), detail).rows.map { it.id })
        assertEquals(listOf("one"), BrowseQueries.evaluate(library, emptyList(), detail.copy(search = true, text = "First")).rows.map { it.id })
    }
    @Test fun playlistDuplicatesRetainOccurrenceIdentityAndSavedOrderWhenSorted() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), tracks + tracks.first())
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Playlist, playlist.id))
        val rows = BrowseQueries.evaluate(library, listOf(playlist), route).rows
        assertEquals(playlist.entries.map { it.id }, rows.map { it.id })
        val sorted = BrowseQueries.evaluate(library, listOf(playlist), route, LibraryQuery(sort = TrackSort.Title))
        assertEquals(3, sorted.rows.map { it.id }.distinct().size)
        assertEquals(listOf("one", "one", "two"), sorted.rows.map { it.track.id })
        assertEquals(listOf("one", "two", "one"), playlist.entries.map { it.track.id })
    }
}

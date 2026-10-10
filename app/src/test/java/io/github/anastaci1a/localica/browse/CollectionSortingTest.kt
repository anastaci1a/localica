package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class CollectionSortingTest {
    private val first = Track("one", "First", "Singer", "Album", fileName = "one.mp3", relativePath = "one.mp3",
        albumArtist = "Band", addedAt = 100, modifiedAt = 600, playCount = 4,
        locations = listOf(TrackLocation("root", "one", "one.mp3")))
    private val second = first.copy(id = "two", uri = "two", title = "Second", addedAt = 200, modifiedAt = 300, playCount = 2)
    private val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = listOf(first, second),
        playlistPlays = mapOf("mix" to 3L))
    private val playlist = PlaylistEdits.append(ManualPlaylist(id = "mix", name = "Mix", createdAt = 250, modifiedAt = 700), listOf(first, first))
    @Test fun allDefaultsToAlbumsAlbumArtistsAndPlaylistsAndCanIncludeOtherCollections() {
        val route = BrowseRoute(MainTab.Library)
        assertEquals(setOf(MediaKind.Album, MediaKind.AlbumArtist, MediaKind.Playlist),
            BrowseQueries.evaluate(snapshot, listOf(playlist), route).items.map { it.ref.kind }.toSet())
        val kinds = setOf(MediaKind.Artist, MediaKind.Folder)
        assertEquals(kinds, BrowseQueries.evaluate(snapshot, listOf(playlist), route, allCollections = kinds).items.map { it.ref.kind }.toSet())
        assertTrue(BrowseQueries.evaluate(snapshot, listOf(playlist), route, allCollections = emptySet()).items.isEmpty())
        // Global search remains complete regardless of All's collection preferences.
        assertTrue(BrowseQueries.evaluate(snapshot, listOf(playlist), route.copy(globalSearch = true, search = true),
            allCollections = emptySet()).rows.isNotEmpty())
    }
    @Test fun mixedCollectionsAggregateCurrentDistinctSongsButPlaylistUsesOwnDatesAndOriginCount() {
        val items = BrowseQueries.evaluate(snapshot, listOf(playlist), BrowseRoute(MainTab.Library),
            allCollections = BrowseQueries.libraryKinds.toSet()).items
        assertFalse(items.any { it.row != null })
        items.filterNot { it.ref.kind == MediaKind.Playlist }.forEach {
            assertEquals(100L, it.addedAt); assertEquals(600L, it.modifiedAt); assertEquals(6L, it.playCount)
        }
        val mix = items.first { it.ref.kind == MediaKind.Playlist }
        assertEquals(250L, mix.addedAt); assertEquals(700L, mix.modifiedAt); assertEquals(3L, mix.playCount)
        val sorted = BrowseQueries.evaluate(snapshot, listOf(playlist), BrowseRoute(MainTab.Library),
            LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)).items
        assertEquals("mix", sorted.last().ref.id)
        assertEquals("3 plays", mix.information(BrowseStyle(sort = TrackSort.MostPlayed)))
    }
    @Test fun dateSortingKeepsUnknownLastInEitherDirectionWithStableTies() {
        val unknown = first.copy(id = "missing", title = "Unknown", addedAt = null, modifiedAt = null)
        for (sort in listOf(TrackSort.Added, TrackSort.Modified)) for (ascending in listOf(true, false)) {
            val rows = LibraryQueries.rows(listOf(unknown, second, first).map { SongRow(it.id, it) }, LibraryQuery(sort = sort, ascending = ascending))
            assertEquals("missing", rows.last().id)
            val expected = if ((sort == TrackSort.Added) == ascending) "one" else "two"
            assertEquals(expected, rows.first().id)
        }
    }
    @Test fun mostPlayedShowsOnlyQualifiedSongsAndPlaylistContentsKeepOriginAfterSorting() {
        val result = BrowseQueries.evaluate(snapshot.copy(tracks = snapshot.tracks + first.copy(id = "never", playCount = 0)),
            emptyList(), BrowseRoute(MainTab.MostPlayed))
        assertEquals(listOf("one", "two"), result.rows.map { it.track.id })
        val rows = BrowseQueries.evaluate(snapshot, listOf(playlist), BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix")),
            LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)).rows
        assertEquals(2, rows.map { it.id }.distinct().size)
        assertTrue(rows.all { it.sourcePlaylistId == "mix" && it.track.playCount == 4L })
    }
}

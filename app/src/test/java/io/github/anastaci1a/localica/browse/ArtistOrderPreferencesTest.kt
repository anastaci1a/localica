package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.browseOptionsDeclaration
import org.junit.Assert.*
import org.junit.Test

class ArtistOrderPreferencesTest {
    @Test fun actualArtistQueriesPublishTheSameAlbumOrderAsTheirContiguousRows() {
        fun track(id: String, album: String, year: Int, number: Int) = Track(id, "Title $id",
            artist = "Artist", albumArtist = "Artist", album = album, year = year,
            discNumber = 1, trackNumber = number, fileName = "$id.mp3", relativePath = "$id.mp3")
        val tracks = listOf(track("old-two", "Z old", 2001, 2), track("new-two", "A new", 2020, 2),
            track("old-one", "Z old", 1999, 1), track("new-one", "A new", 2021, 1))
        val snapshot = LibrarySnapshot(tracks = tracks)
        for ((kind, view) in listOf(MediaKind.Artist to SongListView.Artists, MediaKind.AlbumArtist to SongListView.AlbumArtists)) {
            val ref = MediaRef(kind, LibraryViews.groups(snapshot, view, LibraryQuery()).single().id)
            val route = BrowseRoute(MainTab.Library, ref)
            for (ascending in listOf(true, false)) {
                val result = BrowseQueries.evaluate(snapshot, emptyList(), route,
                    LibraryQuery(sort = TrackSort.Year, ascending = ascending))
                val expected = if (ascending) listOf("old-one", "old-two", "new-one", "new-two")
                    else listOf("new-one", "new-two", "old-one", "old-two")
                assertEquals(expected, result.rows.map { it.track.id })
                assertEquals(expected, result.artistAlbums.flatMap { it.tracks }.map { it.id })
                val sorts = BrowseSortSnapshot.fromValues(mapOf("contents.$kind.sort" to "Year", "contents.$kind.ascending" to ascending))
                assertEquals(expected, sorts.rows(ref, tracks.map { SongRow(it.id, it) }).map { it.track.id })
            }
        }
    }

    @Test fun untouchedArtistContentsUseYearAscendingWithoutChangingAlbumOrCatalogDefaults() {
        for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "artist"))
            assertEquals(LibraryQuery(sort = TrackSort.Year), BrowseSortSnapshot.Default.query(route))
            assertFalse(TrackSort.Album in browseOptionsDeclaration(route).sortFields)
            assertTrue(TrackSort.Folder in browseOptionsDeclaration(route).sortFields)
        }
        val album = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Album, "album"))
        assertEquals(TrackSort.Album, BrowseSortSnapshot.Default.query(album).sort)
        assertTrue(TrackSort.Album in browseOptionsDeclaration(album).sortFields)
        assertEquals(TrackSort.Title, BrowseSortSnapshot.Default.query(BrowseRoute(MainTab.Artists)).sort)
    }

    @Test fun sharedAndSpecificRetiredChoicesMigrateButOtherExplicitDirectionsSurvive() {
        for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "artist"))
            for (key in listOf(BrowsePreferenceKeys.type(route), BrowsePreferenceKeys.list(route))) {
                for (ascending in listOf(false, true)) {
                    val old = BrowseSortSnapshot.fromValues(mapOf("$key.sort" to "Album", "$key.ascending" to ascending))
                    assertEquals(LibraryQuery(sort = TrackSort.Year), old.query(route))
                }
                for (sort in TrackSort.entries.filterNot { it == TrackSort.Album || it == TrackSort.Manual }) {
                    val explicit = BrowseSortSnapshot.fromValues(mapOf("$key.sort" to sort.name, "$key.ascending" to false))
                    assertEquals(LibraryQuery(sort = sort, ascending = false), explicit.query(route))
                }
            }
            val legacyKey = BrowseQueries.scope(route).name + "."
            val legacy = BrowseSortSnapshot.fromValues(emptyMap<String, Any>(),
                mapOf(legacyKey + "sort" to "Album", legacyKey + "ascending" to false))
            assertEquals(LibraryQuery(sort = TrackSort.Year), legacy.query(route))
        }
    }
}

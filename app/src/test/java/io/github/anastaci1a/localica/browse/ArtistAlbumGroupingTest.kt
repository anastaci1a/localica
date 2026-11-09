package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.browseOptionsDeclaration
import org.junit.Assert.*
import org.junit.Test

/** #694: artist views group songs by album unless the shared Group by album choice is off. */
class ArtistAlbumGroupingTest {
    private fun track(id: String, album: String, year: Int, number: Int, plays: Long, title: String) = Track(id, title,
        artist = "Artist", albumArtist = "Artist", album = album, year = year, discNumber = 1, trackNumber = number,
        durationMs = 60_000, playCount = plays, fileName = "$id.mp3", relativePath = "$album/$id.mp3")

    // Old (1999): o1 "zulu" 1 play, o2 "alpha" 9 plays. New (2020): n1 "mike" 5 plays, n2 "bravo" 0 plays.
    private val tracks = listOf(track("o2", "Old", 1999, 2, 9, "alpha"), track("n1", "New", 2020, 1, 5, "mike"),
        track("o1", "Old", 1999, 1, 1, "zulu"), track("n2", "New", 2020, 2, 0, "bravo"))
    private val snapshot = LibrarySnapshot(tracks = tracks)
    private fun sorts(grouped: Boolean?) = BrowseSortSnapshot.fromValues(
        if (grouped == null) emptyMap() else mapOf(BrowsePreferenceKeys.ArtistAlbumGrouping to grouped))

    private fun evaluate(kind: MediaKind, sort: TrackSort, ascending: Boolean, grouped: Boolean?): BrowseResult {
        val view = if (kind == MediaKind.Artist) SongListView.Artists else SongListView.AlbumArtists
        val ref = MediaRef(kind, LibraryViews.groups(snapshot, view, LibraryQuery()).single().id)
        return BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Library, ref),
            LibraryQuery(sort = sort, ascending = ascending), sorts = sorts(grouped))
    }

    @Test fun groupedModeKeepsContiguousAlbumsForEverySortAndIsTheDefault() {
        for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) for (grouped in listOf(null, true)) {
            // Most played orders albums by their sums (Old 10, New 5); tracks stay ascending inside.
            assertEquals(listOf("o1", "o2", "n1", "n2"),
                evaluate(kind, TrackSort.MostPlayed, false, grouped).rows.map { it.track.id })
            // Title descending follows album title: Old, then New.
            assertEquals(listOf("o1", "o2", "n1", "n2"),
                evaluate(kind, TrackSort.Title, false, grouped).rows.map { it.track.id })
            assertEquals(listOf("n1", "n2", "o1", "o2"),
                evaluate(kind, TrackSort.Year, false, grouped).rows.map { it.track.id })
        }
        assertTrue(BrowseSortSnapshot.Default.artistAlbumGrouping)
    }

    @Test fun ungroupedModeOrdersIndividualSongsAcrossAlbumsWhileTheCarouselKeepsAlbumOrder() {
        for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
            val played = evaluate(kind, TrackSort.MostPlayed, false, false)
            assertEquals(listOf("o2", "n1", "o1", "n2"), played.rows.map { it.track.id })
            assertEquals(listOf("Old", "New"), played.artistAlbums.map { it.title })
            assertEquals(listOf("n2", "o1", "n1", "o2"),
                evaluate(kind, TrackSort.MostPlayed, true, false).rows.map { it.track.id })
            assertEquals(listOf("alpha", "bravo", "mike", "zulu"),
                evaluate(kind, TrackSort.Title, true, false).rows.map { it.track.title })
            assertEquals(listOf("zulu", "mike", "bravo", "alpha"),
                evaluate(kind, TrackSort.Title, false, false).rows.map { it.track.title })
            // Year ties fall back to song title, not to album/track grouping.
            assertEquals(listOf("alpha", "zulu", "bravo", "mike"),
                evaluate(kind, TrackSort.Year, true, false).rows.map { it.track.title })
        }
    }

    @Test fun collectionRowsUsedByPlayAndCoversFollowTheSameChoice() {
        val ref = MediaRef(MediaKind.Artist, LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single().id)
        val rows = tracks.map { SongRow(it.id, it) }
        val order = mapOf("contents.Artist.sort" to "MostPlayed", "contents.Artist.ascending" to false)
        assertEquals(listOf("o1", "o2", "n1", "n2"), BrowseSortSnapshot.fromValues(order).rows(ref, rows).map { it.track.id })
        assertEquals(listOf("o2", "n1", "o1", "n2"), BrowseSortSnapshot.fromValues(order +
            (BrowsePreferenceKeys.ArtistAlbumGrouping to false)).rows(ref, rows).map { it.track.id })
    }

    @Test fun persistedValuesRoundTripAndInvalidateCachedCollectionResults() {
        // The SharedPreferences map as BrowsePreferences writes it after a restart.
        val stored = mapOf<String, Any>(BrowsePreferenceKeys.ArtistAlbumGrouping to false, "contents.Artist.sort" to "Year")
        val restored = BrowseSortSnapshot.fromValues(stored)
        assertFalse(restored.artistAlbumGrouping)
        assertTrue(BrowsePreferenceKeys.ArtistAlbumGrouping.endsWith(".group"))
        assertFalse(restored.sameCollections(BrowseSortSnapshot.fromValues(stored - BrowsePreferenceKeys.ArtistAlbumGrouping)))
        assertTrue(restored.sameCollections(BrowseSortSnapshot.fromValues(stored)))
        // A malformed value keeps the default instead of disabling grouping.
        assertTrue(BrowseSortSnapshot.fromValues(mapOf(BrowsePreferenceKeys.ArtistAlbumGrouping to "Off")).artistAlbumGrouping)
    }

    @Test fun onlyArtistSongViewsOfferTheOption() {
        for (kind in MediaKind.entries) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "id"))
            val artist = kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)
            assertEquals(artist, browseOptionsDeclaration(route).albumGrouping)
            assertEquals(artist, route.isArtistContents())
            assertFalse(browseOptionsDeclaration(route.copy(search = true)).albumGrouping)
        }
        for (tab in MainTab.entries) assertFalse(browseOptionsDeclaration(BrowseRoute(tab)).albumGrouping)
        assertTrue(BrowseStyle().albumGrouping)
    }
}

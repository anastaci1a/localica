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
    @Test fun longestListenedSortsEveryCollectionKindBySummedListeningTimeAndLabelsIt() {
        // one: 4 plays x 100 s = 400 s; two: 2 x 400 s = 800 s; playlist Mix holds "one" twice, counted once.
        val timed = snapshot.copy(tracks = listOf(first.copy(durationMs = 100_000), second.copy(durationMs = 400_000)))
        val byTime = LibraryQuery(sort = TrackSort.LongestListened, ascending = false)
        assertEquals(listOf("two", "one"), BrowseQueries.evaluate(timed, listOf(playlist), BrowseRoute(MainTab.Songs), byTime).rows.map { it.track.id })
        assertEquals(listOf("one", "two"), BrowseQueries.evaluate(timed, listOf(playlist), BrowseRoute(MainTab.Songs),
            LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)).rows.map { it.track.id })
        val all = BrowseQueries.libraryKinds.toSet()
        val items = BrowseQueries.evaluate(timed, listOf(playlist), BrowseRoute(MainTab.Library), byTime, allCollections = all).items
        val mix = items.first { it.ref.kind == MediaKind.Playlist }
        assertEquals(400_000L, mix.longestListenedMs())
        items.filterNot { it.ref.kind == MediaKind.Playlist }.forEach { assertEquals(it.title, 1_200_000L, it.longestListenedMs()) }
        assertEquals("mix", items.last().ref.id)
        assertEquals("mix", BrowseQueries.evaluate(timed, listOf(playlist), BrowseRoute(MainTab.Library),
            byTime.copy(ascending = true), allCollections = all).items.first().ref.id)
        assertEquals("20:00", items.first().information(BrowseStyle(sort = TrackSort.LongestListened)))
        assertEquals("6:40", mix.information(BrowseStyle(extraInfo = ExtraInfo.LongestListened)))
        val partial = mix.copy(songs = mix.songs + SongRow("extra", first.copy(id = "extra", durationMs = null, playCount = 1)))
        assertEquals("6:40 (partial)", partial.information(BrowseStyle(extraInfo = ExtraInfo.LongestListened)))
        assertEquals("1:00:05", listeningTimeLabel(3_605_000))
        assertEquals("0:00", listeningTimeLabel(0))
        assertEquals("59:59", listeningTimeLabel(3_599_999))
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

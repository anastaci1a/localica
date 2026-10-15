package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class BrowseQueriesTest {
    @Test fun preparedThirteenThousandSongSearchesMatchOracleAcrossSortsAndReportWarmPercentiles() {
        val tracks = (0 until 13_000).map { index -> Track("$index", "Song $index", artist = "Artist ${index % 31}",
            album = "Album ${index % 100}", comment = "rare ${index % 23}", fileName = "$index.mp3", relativePath = "Disc/$index.mp3") }
        val prepareStart = System.nanoTime()
        val snapshot = LibrarySearchIndexBuilder().prepare(LibrarySnapshot(tracks = tracks))
        val prepareMs = (System.nanoTime() - prepareStart) / 1_000_000.0
        val route = BrowseRoute(MainTab.Songs, search = true)
        val queries = listOf("s", "10", "Song 10", "Artist 7 rare 3", "Disc/999", "absent")
        for (sort in listOf(TrackSort.Title, TrackSort.Artist, TrackSort.Manual)) {
            val ordering = LibraryQuery(sort = sort, ascending = false)
            for (text in queries) assertEquals(LibraryQueries.rows(tracks.map { SongRow(it.id, it) }, ordering.copy(text = text)).map { it.id },
                BrowseQueries.evaluate(snapshot, emptyList(), route.copy(text = text), ordering).rows.map { it.id })
        }
        val ordering = LibraryQuery()
        BrowseQueries.evaluate(snapshot, emptyList(), route, ordering)
        val timings = (0 until 60).map { attempt ->
            val start = System.nanoTime()
            BrowseQueries.evaluate(snapshot, emptyList(), route.copy(text = queries[attempt % queries.size]), ordering)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("13,000-song index prepare=${prepareMs}ms; warm query p50=${timings[29]}ms p95=${timings[56]}ms (host JVM; not visible-device latency)")
    }

    @Test fun computationalCatalogIgnoresNavigationHistoryButKeepsEffectiveOrder() {
        val snapshot = LibrarySearchIndexBuilder().prepare(library)
        val route = BrowseRoute(MainTab.Songs, search = true)
        val first = BrowseQueries.evaluate(snapshot, emptyList(), route)
        assertSame(first, BrowseQueries.evaluate(snapshot, emptyList(), route.copy(key = "new history", title = "renamed", queryCommitted = true)))
        val descending = BrowseQueries.evaluate(snapshot, emptyList(), route, LibraryQuery(ascending = false))
        assertEquals(first.rows.reversed(), descending.rows)
    }

    @Test fun globalCatalogSharesAcrossOriginsAndCanceledPreparationNeverCachesPartialRows() {
        val snapshot = LibrarySearchIndexBuilder().prepare(library)
        val global = BrowseRoute(MainTab.Home, search = true, globalSearch = true)
        val first = BrowseQueries.evaluate(snapshot, emptyList(), global)
        assertSame(first, BrowseQueries.evaluate(snapshot, emptyList(), global.copy(tab = MainTab.Songs, key = "other origin")))
        val separate = snapshot.copy(tracks = (0 until 2000).map { tracks.first().copy(id = "$it", title = "Song $it") })
        var checks = 0
        try {
            BrowseQueries.evaluate(separate, emptyList(), BrowseRoute(MainTab.Songs)) {
                if (++checks == 3) throw java.util.concurrent.CancellationException("superseded")
            }
            fail("Expected a canceled catalog build")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(2000, BrowseQueries.evaluate(separate, emptyList(), BrowseRoute(MainTab.Songs)).rows.size)
    }

    @Test fun statisticsReuseTextButRefreshMostPlayedOrderAndUnavailableOccurrenceMetadata() {
        val snapshot = LibrarySearchIndexBuilder().prepare(library)
        val counted = snapshot.copy(tracks = snapshot.tracks.map { it.copy(playCount = if (it.id == "two") 10 else 1) })
        assertSame(snapshot.searchIndex, counted.searchIndex)
        val route = BrowseRoute(MainTab.Songs, search = true)
        assertEquals(listOf("two", "one"), BrowseQueries.evaluate(counted, emptyList(), route,
            LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)).rows.map { it.id })
        val unavailable = tracks.first().copy(title = "Unavailable needle", id = "missing")
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(unavailable, unavailable, tracks[1]))
        val detail = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), search = true, text = "needle")
        val found = BrowseQueries.evaluate(snapshot, listOf(playlist), detail).rows
        assertEquals(playlist.entries.take(2).map { it.id }, found.map { it.id })
    }

    @Test fun successiveSearchEditsMatchCompleteQueriesAndInvalidateWithLibraryMetadata() {
        val tracks = (0 until 12000).map { index -> Track("$index", "Song $index", artist = "Artist ${index % 31}",
            album = "Album ${index % 100}", comment = "rare ${index % 23}", fileName = "$index.mp3", relativePath = "Disc/$index.mp3") }
        val snapshot = LibrarySnapshot(tracks = tracks)
        val route = BrowseRoute(MainTab.Songs, search = true)
        val query = LibraryQuery(sort = TrackSort.Title)
        BrowseQueries.evaluate(snapshot, emptyList(), route, query)
        val started = System.nanoTime()
        val answers = listOf("s", "Song 10", "Artist 7 rare 3", "Disc/999", "absent").associateWith {
            BrowseQueries.evaluate(snapshot, emptyList(), route.copy(text = it), query).rows
        }
        println("Five warm 12,000-song searches: ${(System.nanoTime() - started) / 1_000_000} ms")
        answers.forEach { (text, actual) ->
            assertEquals(LibraryQueries.rows(tracks.map { SongRow(it.id, it) }, query.copy(text = text)).map { it.id }, actual.map { it.id })
        }
        val changed = snapshot.copy(tracks = listOf(tracks.first().copy(title = "Changed")))
        assertEquals("Changed", BrowseQueries.evaluate(changed, emptyList(), route.copy(text = "Changed"), query).rows.single().track.title)
    }
    private val tracks = listOf(
        Track("one", "First", album = "Album", albumArtist = "Group", trackNumber = 2, fileName = "one.mp3", relativePath = "one.mp3"),
        Track("two", "Second", album = "Album", albumArtist = "Group", trackNumber = 1, fileName = "two.mp3", relativePath = "two.mp3"))
    private val library = LibrarySnapshot(tracks = tracks)
    @Test fun queueNamesDescribeSourceAndAvoidMislabelingMixedArtists() {
        assertEquals("Album ◀ Group", queueSourceName(BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "a"), "Album"), tracks))
        assertEquals("All Songs ◀ Group", queueSourceName(BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, "a"), "Group"), tracks))
        assertEquals("All Songs", queueSourceName(BrowseRoute(MainTab.Songs), tracks + tracks.first().copy(albumArtist = "Other")))
    }
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
    @Test fun playlistQueriesKeepPhysicalOrderUntilAnExplicitSortOperation() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), tracks + tracks.first())
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Playlist, playlist.id))
        val rows = BrowseQueries.evaluate(library, listOf(playlist), route).rows
        assertEquals(playlist.entries.map { it.id }, rows.map { it.id })
        val sorted = BrowseQueries.evaluate(library, listOf(playlist), route, LibraryQuery(sort = TrackSort.Title))
        assertEquals(3, sorted.rows.map { it.id }.distinct().size)
        assertEquals(listOf("one", "two", "one"), sorted.rows.map { it.track.id })
        val applied = PlaylistEdits.sort(playlist, TrackSort.Title, true)
        assertEquals(listOf("one", "one", "two"), BrowseQueries.evaluate(library, listOf(applied), route).rows.map { it.track.id })
        assertEquals(listOf("one", "two", "one"), playlist.entries.map { it.track.id })
    }
}

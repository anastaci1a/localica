package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test

class CollectionArtworkTest {
    private val alpha = Track("alpha", "Alpha", id = "z-id", album = "Album", artist = "Artist",
        albumArtist = "Group", trackNumber = 2, year = 2020, fileName = "a.mp3", relativePath = "Disc/a.mp3")
    private val zulu = alpha.copy(id = "a-id", title = "Zulu", uri = "zulu", trackNumber = 1, year = 2024,
        relativePath = "Disc/z.mp3")
    private val library = LibrarySnapshot(tracks = listOf(alpha, zulu))
    private fun sorts(kind: MediaKind, sort: TrackSort, ascending: Boolean = true, ref: MediaRef? = null) =
        BrowseSortSnapshot.fromValues(buildMap {
            val key = if (ref == null) "contents.$kind" else BrowsePreferenceKeys.list(BrowseRoute(MainTab.Library, ref))
            put("$key.sort", sort.name); put("$key.ascending", ascending)
        })
    private fun item(tab: MainTab, sorts: BrowseSortSnapshot = BrowseSortSnapshot.Default) =
        BrowseQueries.evaluate(library, emptyList(), BrowseRoute(tab), sorts = sorts).items.single()

    @Test fun everyAutomaticLibraryCollectionUsesItsEffectiveContentsSort() {
        for ((tab, kind) in listOf(MainTab.Albums to MediaKind.Album, MainTab.Artists to MediaKind.Artist,
            MainTab.AlbumArtists to MediaKind.AlbumArtist)) {
            val ascending = sorts(kind, TrackSort.Title)
            val descending = sorts(kind, TrackSort.Title, false)
            val first = item(tab, ascending)
            // Artist Title orders album groups; this single album always starts
            // with track 1, even when the outer album direction changes (#471).
            assertEquals(listOf(if (kind == MediaKind.Album) "alpha" else "zulu"), first.coverUris)
            assertEquals(listOf("zulu"), item(tab, descending).coverUris)
            val detail = BrowseQueries.evaluate(library, emptyList(), BrowseRoute(tab, first.ref), sorts = ascending)
            assertEquals(detail.rows.first().track.uri, first.coverUris.single())
        }
    }

    @Test fun folderUsesItsOwnEffectiveSortAndGrantedLocationUri() {
        val snapshot = library.copy(folders = listOf(FolderSelection("root", "Music")), tracks = library.tracks.map {
            it.copy(locations = listOf(TrackLocation("root", "granted:${it.uri}", it.relativePath)))
        })
        val preferences = sorts(MediaKind.Folder, TrackSort.Title, false)
        val folder = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Folders), sorts = preferences).items.single()
        assertEquals(listOf("granted:zulu"), folder.coverUris)
        assertEquals(folder.coverUris.single(), BrowseQueries.evaluate(snapshot, emptyList(),
            BrowseRoute(MainTab.Folders, folder.ref), sorts = preferences).rows.first().track.uri)
    }

    @Test fun albumDefaultIsDiscTrackOrderAndCatalogSortDoesNotChangeItsContents() {
        assertEquals(listOf("zulu"), item(MainTab.Albums).coverUris)
        val catalog = BrowseRoute(MainTab.Albums)
        val result = BrowseQueries.evaluate(library, emptyList(), catalog,
            LibraryQuery(sort = TrackSort.Title, ascending = false), sorts = sorts(MediaKind.Album, TrackSort.Title))
        assertEquals(listOf("alpha"), result.items.single().coverUris)
    }

    @Test fun overrideWinsUntilResetThenInheritedChangesApply() {
        val ref = item(MainTab.Albums).ref
        val key = BrowsePreferenceKeys.list(BrowseRoute(MainTab.Library, ref))
        val values = mutableMapOf<String, Any>("contents.Album.sort" to "Title", "contents.Album.ascending" to true,
            "$key.sort" to "Title", "$key.ascending" to false)
        val captured = BrowseSortSnapshot.fromValues(values)
        assertEquals(listOf("zulu"), item(MainTab.Albums, captured).coverUris)
        values["contents.Album.sort"] = "Year"
        assertEquals(listOf("zulu"), item(MainTab.Albums, BrowseSortSnapshot.fromValues(values)).coverUris)
        values.remove("$key.sort"); values.remove("$key.ascending")
        assertEquals(listOf("alpha"), item(MainTab.Albums, BrowseSortSnapshot.fromValues(values)).coverUris)
        assertEquals("A captured snapshot is immutable", listOf("zulu"), item(MainTab.Albums, captured).coverUris)
    }

    @Test fun legacySortAndUnknownValuesUseTheSameDefaultsAsDetailQueries() {
        val legacy = BrowseSortSnapshot.fromValues(emptyMap<String, Any>(), mapOf("Albums.sort" to "Title", "Albums.ascending" to false))
        val album = item(MainTab.Albums, legacy)
        assertEquals("zulu", album.coverUris.single())
        assertEquals("zulu", BrowseQueries.evaluate(library, emptyList(), BrowseRoute(MainTab.Albums, album.ref), sorts = legacy).rows.first().track.uri)
        val unknown = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "future-method"))
        assertEquals(TrackSort.Album, unknown.collectionQuery(album.ref).sort)
    }

    @Test fun firstMissingArtworkNeverOffersLaterSongsAsFallbackAndCustomRemovalRestoresFirst() {
        val rows = listOf(SongRow("first", alpha.copy(uri = "missing-art")), SongRow("second", zulu))
        val automatic = BrowserItem(MediaRef(MediaKind.Playlist, "mix"), "Mix", null, rows)
        assertEquals(listOf("missing-art"), automatic.coverUris)
        val custom = automatic.copy(coverUri = "custom-image")
        assertEquals(listOf("custom-image"), custom.coverUris)
        assertEquals(automatic.coverUris, custom.copy(coverUri = null).coverUris)
        assertTrue(automatic.copy(songs = emptyList()).coverUris.isEmpty())
        assertEquals(listOf("custom-image"), custom.copy(songs = emptyList()).coverUris)
    }

    @Test fun playlistsKeepOccurrencesAndPersistedSortInsteadOfReapplyingViewPreferences() {
        val playlist = ManualPlaylist(id = "mix", name = "Mix", entries = listOf(
            SongRow("z-first", zulu), SongRow("a-second", alpha), SongRow("z-again", zulu)))
        val preferences = sorts(MediaKind.Playlist, TrackSort.Title)
        fun catalog(value: ManualPlaylist) = BrowseQueries.evaluate(library, listOf(value), BrowseRoute(MainTab.Playlists), sorts = preferences).items.single()
        assertEquals(listOf("zulu"), catalog(playlist).coverUris)
        assertEquals(listOf("z-first", "a-second", "z-again"), catalog(playlist).songs.map { it.id })
        val applied = PlaylistEdits.sort(playlist, TrackSort.Title, true)
        assertEquals(listOf("alpha"), catalog(applied).coverUris)
        assertEquals(3, catalog(applied).songs.map { it.id }.distinct().size)
        val reordered = PlaylistEdits.moveBefore(applied, setOf("z-again"), "a-second")
        assertEquals(listOf("zulu"), catalog(reordered).coverUris)
        assertEquals(listOf("custom"), catalog(reordered.copy(coverUri = "custom")).coverUris)
    }

    @Test fun queueFirstOccurrenceIsItsSavedPhysicalOrder() {
        val queue = ListeningQueue(id = "queue", name = "Queue", entries = listOf(
            QueueEntry(id = "z-first", track = zulu), QueueEntry(id = "a-second", track = alpha)))
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id))
        val rows = BrowseQueries.evaluate(library, emptyList(), route, queues = listOf(queue),
            sorts = sorts(MediaKind.Queue, TrackSort.Title)).rows
        assertEquals(listOf("z-first", "a-second"), rows.map { it.id })
        assertEquals(listOf("zulu"), BrowserItem(route.collection!!, "Queue", null, rows).coverUris)
    }

    @Test fun cacheInvalidatesOnContentsSortEvenWhenCatalogQueryAndLibraryAreUnchanged() {
        val route = BrowseRoute(MainTab.Albums)
        val ascending = sorts(MediaKind.Album, TrackSort.Title)
        val descending = sorts(MediaKind.Album, TrackSort.Title, false)
        val first = BrowseQueries.evaluate(library, emptyList(), route, sorts = ascending)
        assertSame(first, BrowseQueries.evaluate(library, emptyList(), route, sorts = ascending))
        val changed = BrowseQueries.evaluate(library, emptyList(), route, sorts = descending)
        assertEquals(listOf("alpha"), first.items.single().coverUris)
        assertEquals(listOf("zulu"), changed.items.single().coverUris)
        val layout = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Title", "contents.Album.ascending" to false,
            "contents.Album.presentation" to "Large"))
        assertEquals(descending, layout)
        assertSame(changed, BrowseQueries.evaluate(library, emptyList(), route, sorts = layout))
    }

    @Test fun albumGroupingAndPinnedCatalogAgreeWithTheFirstDisplayedMember() {
        val snapshot = LibrarySnapshot(tracks = listOf(alpha.copy(artist = "Zulu artist"), zulu.copy(artist = "Alpha artist")))
        val preferences = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Title", "contents.Album.group" to "Artist"))
        val album = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Albums), sorts = preferences).items.single()
        val pinned = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Home, globalSearch = true), sorts = preferences)
            .items.first { it.ref == album.ref }
        val detail = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Albums, album.ref), sorts = preferences)
        assertEquals("zulu", detail.rows.first().track.uri)
        assertEquals(listOf(detail.rows.first().track.uri), album.coverUris)
        assertEquals(album.coverUris, pinned.coverUris)
    }

    @Test fun scopedAlbumSearchRestoresTheHeadingOfTheFirstSurvivingMember() {
        val album = item(MainTab.Albums)
        val preferences = BrowseSortSnapshot.fromValues(mapOf("search.sort" to "Title", "search.group" to "Artist"))
        val result = BrowseQueries.evaluate(library, emptyList(), BrowseRoute(MainTab.Albums, album.ref, search = true, text = "Zulu"),
            sorts = preferences)
        assertEquals(listOf("zulu"), result.rows.map { it.track.uri })
        assertEquals("Artist", result.items.single().groupHeading)
    }

    @Test fun sharedComparatorKeepsUnknownValuesLastAndDeterministicTies() {
        val rows = listOf(SongRow("unknown", alpha.copy(id = "unknown", uri = "unknown", year = null)),
            SongRow("known-b", alpha.copy(id = "b", uri = "b")), SongRow("known-a", alpha.copy(id = "a", uri = "a")))
        val ref = MediaRef(MediaKind.Artist, "artist")
        for (ascending in listOf(true, false)) {
            val preferences = sorts(MediaKind.Artist, TrackSort.Year, ascending)
            assertEquals(LibraryQueries.rows(rows, preferences.collectionQuery(ref)), preferences.rows(ref, rows))
            assertEquals(listOf("known-a", "known-b", "unknown"), preferences.rows(ref, rows).map { it.id })
        }
    }

    @Test fun manyCollectionsUseBoundedLibraryPassesRatherThanOnePassPerGroup() {
        var reads = 0
        val tracks = object : AbstractList<Track>() {
            override val size = 12000
            override fun get(index: Int): Track {
                reads++
                return alpha.copy(id = "$index", uri = "song:$index", title = "Song ${size - index}", album = "Album ${index / 10}",
                    artist = "Artist ${index / 100}", albumArtist = "Group ${index / 100}",
                    locations = listOf(TrackLocation("root", "song:$index", "Folder ${index / 20}/$index.mp3")))
            }
        }
        val snapshot = LibrarySnapshot(tracks = tracks, folders = listOf(FolderSelection("root", "Music")))
        val preferences = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Title", "contents.Album.ascending" to false,
            "contents.Artist.sort" to "Title", "contents.AlbumArtist.sort" to "Title", "contents.Folder.sort" to "Title"))
        val started = System.nanoTime()
        val result = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Library), sorts = preferences,
            allCollections = setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Folder))
        println("12,000 tracks / ${result.items.size} collection covers: ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(2040, result.items.size)
        assertEquals("One library scan per grouping kind, independent of group count", tracks.size * 4, reads)
        result.items.forEach { item ->
            val expected = if (item.ref.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
                // Independent oracle for this fixture: every disc/track number
                // is equal, so album Title then inner song Title chooses the cover.
                item.songs.minWith(compareBy<SongRow> { it.track.album!!.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.track.title.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.track.id }).track.uri
            } else LibraryQueries.rows(item.songs, preferences.collectionQuery(item.ref)).first().track.uri
            assertEquals(expected, item.coverUris.single())
        }
    }

    @Test fun playlistCatalogIndexesCurrentLibraryOnlyOnceForAllCollections() {
        var reads = 0
        val tracks = object : AbstractList<Track>() {
            override val size = 12000
            override fun get(index: Int): Track { reads++; return alpha.copy(id = "$index") }
        }
        val snapshot = LibrarySnapshot(tracks = tracks)
        val playlists = (0 until 300).map { ManualPlaylist(id = "playlist-$it", name = "Playlist $it",
            entries = listOf(SongRow("entry-$it", alpha.copy(id = "$it")))) }
        assertEquals(300, BrowseQueries.evaluate(snapshot, playlists, BrowseRoute(MainTab.Playlists)).items.size)
        assertEquals("Library metadata must be indexed once, not once per playlist", tracks.size, reads)
    }

    @Test fun collectionOrderingAndGroupedFilteringKeepCancellationCheckpoints() {
        val rows = (0 until 1024).map { index -> SongRow("row-$index", alpha.copy(id = "track-$index")) }
        var checks = 0
        sorts(MediaKind.Album, TrackSort.Title).rows(MediaRef(MediaKind.Album, "album"), rows) { checks++ }
        assertTrue("Collection contents sorting must remain cancellable", checks > 1)
        val items = rows.map { row -> BrowserItem(MediaRef(MediaKind.Song, row.track.id), row.track.title, null, listOf(row), row) }
        checks = 0
        BrowseResult(items).groupAlbumSongs(SongGrouping.Artist) { checks++ }
        assertTrue("Post-filter grouping must remain cancellable", checks > 1)
    }
}

package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the evaluator and presentation-cache boundary shared by warm and foreground callers.
 * Compose effect scheduling and SharedPreferences observation remain instrumentation concerns. */
class BrowsePresentationParityTest {
    private val alpha = Track("alpha", "Alpha", id = "a", album = "Album", artist = "Zulu artist",
        albumArtist = "Group", composer = "Writer Z", fileName = "a.mp3", relativePath = "Disc/a.mp3", trackNumber = 2, year = 2020, playCount = 3,
        locations = listOf(TrackLocation("root", "granted:alpha", "Disc/a.mp3")))
    private val zulu = alpha.copy(uri = "zulu", title = "Zulu", id = "z", artist = "Alpha artist",
        composer = "Writer A", trackNumber = 1, year = 2024)
    private val library = LibrarySnapshot(tracks = listOf(alpha, zulu), folders = listOf(FolderSelection("root", "Music")))
    private val playlists = listOf(ManualPlaylist(id = "mix", name = "Mix", entries = listOf(
        SongRow("z-first", zulu), SongRow("a-second", alpha), SongRow("z-again", zulu))))
    private val queues = listOf(ListeningQueue(id = "queue", name = "Queue", entries = listOf(
        QueueEntry(id = "z-first", track = zulu), QueueEntry(id = "a-second", track = alpha))))
    private val filters = LibraryFilters(allCollections = BrowseQueries.libraryKinds.toSet() - MediaKind.Song)

    private fun routes(): List<BrowseRoute> {
        val roots = MainTab.entries.map { BrowseRoute(it) } + BrowseQueries.libraryKinds.map { BrowseRoute(MainTab.Library, filter = it) }
        val collections = listOf(MainTab.Albums, MainTab.Artists, MainTab.AlbumArtists, MainTab.Folders, MainTab.Playlists)
            .flatMap { tab -> BrowseQueries.evaluate(library, playlists, BrowseRoute(tab)).items.map { BrowseRoute(tab, it.ref) } } +
            BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "queue"))
        val scoped = (roots + collections).flatMap { listOf(it, it.copy(search = true, text = "Zulu")) }
        val global = MainTab.entries.flatMap { tab -> (listOf<MediaKind?>(null) + BrowseQueries.libraryKinds).map {
            BrowseRoute(tab, search = true, globalSearch = true, text = "a", filter = it)
        } }
        return scoped + global
    }

    @Test fun warmCacheAndColdEvaluationHaveIdenticalFullPresentationsAcrossRoutesAndOrders() {
        var comparisons = 0
        for (route in routes()) for (grouping in SongGrouping.entries) for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
            val key = BrowsePreferenceKeys.type(route)
            val sorts = BrowseSortSnapshot.fromValues(mapOf("$key.sort" to sort.name, "$key.ascending" to ascending,
                "$key.group" to grouping.name, "contents.Album.group" to grouping.name))
            val style = BrowseStyle(sort = sort, ascending = ascending, songGrouping = grouping)
            val source = BrowseSourceRevision(library, playlists, queues, filters)
            val cache = BrowsePresentationCache()
            val warmResult = BrowseQueries.evaluate(library, playlists, route, style.query(), queues, filters.allCollections, sorts)
            cache.put(library, playlists, queues, filters, PresentedBrowse(route, style, warmResult, source, sorts))
            // A different snapshot identity flushes the evaluator catalog, so the second evaluation is cold.
            BrowseQueries.evaluate(library.copy(), playlists, BrowseRoute(MainTab.Songs))
            val cold = PresentedBrowse(route, style,
                BrowseQueries.evaluate(library, playlists, route, style.query(), queues, filters.allCollections, sorts), source, sorts)
            val warm = cache.get(library, playlists, queues, filters, route, style, sorts)
            assertEquals("$route / $style", cold, warm)
            assertEquals(cold.result.items.map { it.coverUris }, warm!!.result.items.map { it.coverUris })
            comparisons++
        }
        println("Warm/cold full-presentation comparisons: $comparisons")
    }

    @Test fun changedSourcesCannotReuseAnOldPresentationAndRewarmingMatchesCold() {
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix"))
        val style = BrowseStyle()
        fun present(l: LibrarySnapshot, p: List<ManualPlaylist>, q: List<ListeningQueue>, f: LibraryFilters) =
            PresentedBrowse(route, style, BrowseQueries.evaluate(l, p, route, style.query(), q, f.allCollections), BrowseSourceRevision(l, p, q, f))
        val revisions = listOf(
            BrowseSourceRevision(library.copy(), playlists, queues, filters),
            BrowseSourceRevision(library, ArrayList(playlists), queues, filters),
            BrowseSourceRevision(library, playlists, ArrayList(queues), filters),
            BrowseSourceRevision(library.copy(tracks = listOf(alpha.copy(title = "Changed"), zulu)), playlists, queues, filters),
            BrowseSourceRevision(library, playlists.map { it.copy(entries = it.entries.reversed()) }, queues, filters),
            BrowseSourceRevision(library, playlists, queues.map { it.copy(entries = it.entries.reversed()) }, filters),
            BrowseSourceRevision(library, playlists, queues, filters.copy(allCollections = setOf(MediaKind.Album))))
        for (revision in revisions) {
            val cache = BrowsePresentationCache()
            cache.put(library, playlists, queues, filters, present(library, playlists, queues, filters))
            assertNull(cache.get(revision.library, revision.playlists, revision.queues, revision.filters, route, style))
            val warm = present(revision.library, revision.playlists, revision.queues, revision.filters)
            cache.put(revision.library, revision.playlists, revision.queues, revision.filters, warm)
            BrowseQueries.evaluate(library.copy(), playlists, BrowseRoute(MainTab.Songs))
            assertEquals(present(revision.library, revision.playlists, revision.queues, revision.filters),
                cache.get(revision.library, revision.playlists, revision.queues, revision.filters, route, style))
        }
    }

    @Test fun retainedSearchOrderRejectsAWarmEntryUsingDifferentPreferenceOrder() {
        val route = BrowseRoute(MainTab.Songs, search = true, queryCommitted = true,
            querySort = TrackSort.Year, queryAscending = false)
        val preferred = BrowseStyle(sort = TrackSort.Title, ascending = true)
        val retained = preferred.copy(sort = route.querySort!!, ascending = route.queryAscending!!)
        val cache = BrowsePresentationCache()
        val source = BrowseSourceRevision(library, playlists, queues, filters)
        val warmed = BrowseQueries.evaluate(library, playlists, route, preferred.query(), queues, filters.allCollections)
        cache.put(library, playlists, queues, filters, PresentedBrowse(route, preferred, warmed, source))
        assertNull(cache.get(library, playlists, queues, filters, route, retained))
        val cold = BrowseQueries.evaluate(library, playlists, route, retained.query(), queues, filters.allCollections)
        assertEquals(listOf("a", "z"), warmed.rows.map { it.track.id })
        assertEquals(listOf("z", "a"), cold.rows.map { it.track.id })
    }
}

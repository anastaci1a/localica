package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BrowsePresentationCacheConcurrencyTest {
    @Test fun concurrentWarmReadsAndWritesKeepSourceAndRouteCorrect() {
        val cache = BrowsePresentationCache()
        val library = LibrarySnapshot()
        val playlists = emptyList<ManualPlaylist>()
        val queues = emptyList<ListeningQueue>()
        val filters = LibraryFilters()
        val source = BrowseSourceRevision(library, playlists, queues, filters)
        val style = BrowseStyle()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { worker -> pool.submit {
                start.await()
                repeat(1500) { iteration ->
                    val route = BrowseRoute(MainTab.Library, key = "route-${(iteration + worker) % 16}")
                    cache.put(library, playlists, queues, filters, PresentedBrowse(route, style, BrowseResult(), source))
                    cache.get(library, playlists, queues, filters, route, style)?.let {
                        assertEquals(route, it.route)
                        assertEquals(source, it.source)
                    }
                    cache.putHome(library, playlists, emptyList(), emptyList())
                    cache.home(library, playlists, emptyList())?.let { assertTrue(it.isEmpty()) }
                }
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test fun lruRemainsBoundedAndIdentityChangesInvalidateOldEntries() {
        val cache = BrowsePresentationCache()
        val library = LibrarySnapshot()
        val playlists = emptyList<ManualPlaylist>()
        val queues = emptyList<ListeningQueue>()
        val filters = LibraryFilters()
        val style = BrowseStyle()
        val routes = (0..12).map { BrowseRoute(MainTab.Library, key = "route-$it") }
        routes.forEach { cache.put(library, playlists, queues, filters,
            PresentedBrowse(it, style, BrowseResult(), BrowseSourceRevision(library, playlists, queues, filters))) }
        assertNull(cache.get(library, playlists, queues, filters, routes.first(), style))
        assertNotNull(cache.get(library, playlists, queues, filters, routes.last(), style))
        val newer = library.copy()
        cache.put(newer, playlists, queues, filters, PresentedBrowse(routes.last(), style, BrowseResult(),
            BrowseSourceRevision(newer, playlists, queues, filters)))
        assertNull(cache.get(library, playlists, queues, filters, routes.last(), style))
        assertNotNull(cache.get(newer, playlists, queues, filters, routes.last(), style))
    }
}

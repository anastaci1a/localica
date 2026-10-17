package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.LibraryFilters
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import org.junit.Assert.*
import org.junit.Test

class BrowseSourceRevisionTest {
    @Test fun revisionsIncludeLibraryAndCollectionSourcesEvenWhenTheirValuesAreEqual() {
        val library = LibrarySnapshot()
        val playlists = listOf(ManualPlaylist(name = "Mix"))
        val queues = emptyList<io.github.anastaci1a.localica.queue.ListeningQueue>()
        val filters = LibraryFilters()
        val first = BrowseSourceRevision(library, playlists, queues, filters)
        assertEquals(first, BrowseSourceRevision(library, playlists, queues, filters))
        assertNotEquals(first, BrowseSourceRevision(library.copy(), playlists, queues, filters))
        assertNotEquals(first, BrowseSourceRevision(library, playlists.map { it.copy() }, queues, filters))
        assertNotEquals(first, BrowseSourceRevision(library, playlists, ArrayList(queues), filters))
    }
}

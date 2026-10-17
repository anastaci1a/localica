package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class CollectionArtworkCacheTest {
    private val library = LibrarySnapshot()
    private val source = BrowseSourceRevision(library, emptyList(), emptyList(), LibraryFilters())
    private val route = BrowseRoute(MainTab.Albums)
    private val style = BrowseStyle()
    private val ascending = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Title", "contents.Album.ascending" to true))
    private val descending = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Title", "contents.Album.ascending" to false))

    @Test fun retainedBrowseAndHomeInvalidateWhenContentsSortChanges() {
        val cache = BrowsePresentationCache()
        val result = BrowseResult()
        cache.put(library, emptyList(), emptyList(), LibraryFilters(), PresentedBrowse(route, style, result, source, ascending))
        assertNotNull(cache.get(library, emptyList(), emptyList(), LibraryFilters(), route, style, ascending))
        assertNull(cache.get(library, emptyList(), emptyList(), LibraryFilters(), route, style, descending))
        cache.putHome(library, emptyList(), emptyList(), emptyList(), ascending)
        assertNotNull(cache.home(library, emptyList(), emptyList(), ascending))
        assertNull(cache.home(library, emptyList(), emptyList(), descending))
    }

    @Test fun savingDefaultContentsPreferencesDoesNotInvalidateALayoutOnlyChange() {
        val cache = BrowsePresentationCache()
        val explicit = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Album", "contents.Album.ascending" to true,
            "contents.Album.group" to "None", "contents.Album.presentation" to "Large"))
        assertTrue(BrowseSortSnapshot.Default.sameCollections(explicit))
        cache.put(library, emptyList(), emptyList(), LibraryFilters(), PresentedBrowse(route, style, BrowseResult(), source))
        assertNotNull(cache.get(library, emptyList(), emptyList(), LibraryFilters(), route, style, explicit))
    }

    @Test fun completedDetailReplacesSeedAndEmptyResultClearsIt() {
        val first = Track("first", "First", fileName = "first", relativePath = "first")
        val second = first.copy(id = "second", uri = "second")
        val seed = BrowserItem(MediaRef(MediaKind.Album, "album"), "Album", null, listOf(SongRow("first", first)))
        val row = SongRow("second", second)
        val result = BrowseResult(listOf(BrowserItem(MediaRef(MediaKind.Song, second.id), "Second", null, listOf(row), row)))
        assertEquals("first", collectionHeroUri(null, seed))
        assertEquals("second", collectionHeroUri(result, seed))
        assertNull(collectionHeroUri(BrowseResult(), seed))
    }
}

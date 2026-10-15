package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class InheritedInformationTest {
    @Test fun onlyUnfilteredMixedRootsOfferInheritanceInTheRequestedOrder() {
        val library = BrowseRoute(MainTab.Library)
        val global = BrowseRoute(MainTab.Songs, search = true, globalSearch = true)
        for (route in listOf(library, global)) {
            assertTrue(route.allowsInheritedInformation())
            assertEquals(listOf(ExtraInfo.None, ExtraInfo.Inherited, ExtraInfo.Automatic), route.informationChoices().take(3))
        }
        for (route in listOf(library.copy(filter = MediaKind.Album), global.copy(filter = MediaKind.Song),
            BrowseRoute(MainTab.Albums), BrowseRoute(MainTab.Songs, search = true),
            library.copy(collection = MediaRef(MediaKind.Playlist, "x")))) {
            assertFalse(route.allowsInheritedInformation())
            assertFalse(ExtraInfo.Inherited in route.informationChoices())
            assertEquals(listOf(ExtraInfo.None, ExtraInfo.Automatic), route.informationChoices().take(2))
        }
    }

    @Test fun albumsFollowTheirOwnSortWhileOtherTypesKeepTheirOwnChoices() {
        val track = Track("song", "Title", year = 2001, fileName = "song.mp3", relativePath = "song.mp3")
        val album = BrowserItem(MediaRef(MediaKind.Album, "album"), "Album", "Subtitle", listOf(SongRow("song", track)))
        val mixed = BrowseStyle(sort = TrackSort.Duration, extraInfo = ExtraInfo.Inherited)
        val types = mapOf(MediaKind.Album to BrowseStyle(sort = TrackSort.Year, extraInfo = ExtraInfo.Automatic),
            MediaKind.Artist to BrowseStyle(extraInfo = ExtraInfo.None))
        assertEquals("2001", album.information(mixed, types))
        assertNull(album.copy(ref = MediaRef(MediaKind.Artist, "artist")).information(mixed, types))
        assertEquals("1 song", album.information(mixed, types + (MediaKind.Album to BrowseStyle(extraInfo = ExtraInfo.Count))))
    }

    @Test fun missingSelectedMetadataDoesNotInventInformation() {
        val track = Track("song", "Title", fileName = "song.mp3", relativePath = "song.mp3")
        val album = BrowserItem(MediaRef(MediaKind.Album, "album"), "Album", "Subtitle", listOf(SongRow("song", track)))
        for (info in listOf(ExtraInfo.Year, ExtraInfo.Duration)) {
            assertNull(album.information(BrowseStyle(extraInfo = ExtraInfo.Inherited),
                mapOf(MediaKind.Album to BrowseStyle(extraInfo = info))))
        }
    }

    @Test fun invalidRecursiveInheritanceFallsBackWithoutRecursing() {
        val item = BrowserItem(MediaRef(MediaKind.Album, "album"), "Album", "Subtitle", emptyList())
        assertEquals("Subtitle", item.information(BrowseStyle(extraInfo = ExtraInfo.Inherited),
            mapOf(MediaKind.Album to BrowseStyle(extraInfo = ExtraInfo.Inherited))))
        assertEquals("Subtitle", item.information(BrowseStyle(extraInfo = ExtraInfo.Inherited)))
    }
}

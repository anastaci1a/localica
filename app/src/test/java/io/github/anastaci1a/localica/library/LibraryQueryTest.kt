package io.github.anastaci1a.localica.library

import org.junit.Assert.*
import org.junit.Test

class LibraryQueryTest {
    private fun track(id: String, title: String) = Track(id, title, fileName = "$id.mp3", relativePath = "$id.mp3")

    @Test fun `search combines words across tags and paths without depending on capitalization`() {
        val song = track("song", "Quiet Night").copy(artist = "Soloist", albumArtist = "Various Artists",
            album = "Collection", genre = "Jazz", locations = listOf(TrackLocation("a", "song", "Live/song.mp3")))
        val snapshot = LibrarySnapshot(tracks = listOf(song, track("other", "Quiet")))
        assertEquals(listOf(song), LibraryQueries.tracks(snapshot, LibraryQuery(text = "  VARIOUS   live jazz ")))
        assertTrue(LibraryQueries.tracks(snapshot, LibraryQuery(text = "quiet missing")).isEmpty())
    }

    @Test fun `root filtering preserves identity but selects its accessible URI and relative path`() {
        val song = track("id", "Song").copy(locations = listOf(
            TrackLocation("parent", "parent/song", "nested/song.mp3"), TrackLocation("child", "child/song", "song.mp3")))
        val snapshot = LibrarySnapshot(tracks = listOf(song))
        val found = LibraryQueries.tracks(snapshot, LibraryQuery(folderUri = "child")).single()
        assertEquals("id", found.id)
        assertEquals("child/song", found.uri)
        assertEquals("song.mp3", found.relativePath)
        assertTrue(LibraryQueries.tracks(snapshot, LibraryQuery(folderUri = "missing")).isEmpty())
    }

    @Test fun `album order uses album artist then disc and track numbers`() {
        val songs = listOf(track("late", "Alpha").copy(album = "Album", albumArtist = "Band", discNumber = 2, trackNumber = 1),
            track("early", "Zulu").copy(album = "Album", albumArtist = "Band", discNumber = 1, trackNumber = 2),
            track("first", "Zeta").copy(album = "Album", albumArtist = "Band", discNumber = 1, trackNumber = 1))
        assertEquals(listOf("first", "early", "late"), LibraryQueries.tracks(LibrarySnapshot(tracks = songs),
            LibraryQuery(sort = TrackSort.Album)).map { it.id })
    }

    @Test fun `missing numbers stay last in either direction and equal titles have stable IDs`() {
        val songs = listOf(track("b", "Same").copy(year = 2020), track("unknown", "Unknown"), track("a", "Same").copy(year = 2020),
            track("old", "Old").copy(year = 1990))
        assertEquals(listOf("old", "a", "b", "unknown"), LibraryQueries.tracks(LibrarySnapshot(tracks = songs),
            LibraryQuery(sort = TrackSort.Year)).map { it.id })
        assertEquals(listOf("a", "b", "old", "unknown"), LibraryQueries.tracks(LibrarySnapshot(tracks = songs.reversed()),
            LibraryQuery(sort = TrackSort.Year, ascending = false)).map { it.id })
    }

    @Test fun `query returns the full large result for later queue snapshots`() {
        val songs = (1..20000).map { track(it.toString(), "Song $it").copy(albumArtist = "Artist") }
        val result = LibraryQueries.tracks(LibrarySnapshot(tracks = songs), LibraryQuery(text = "artist"))
        assertEquals(20000, result.size)
        assertEquals(20000, result.map { it.id }.toSet().size)
        assertEquals(result, LibraryQueries.tracks(LibrarySnapshot(tracks = songs.reversed()), LibraryQuery(text = "artist")))
    }
}

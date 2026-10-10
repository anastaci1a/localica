package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.*
import org.junit.Test

class LibraryViewsTest {
    private fun song(id: String, artist: String? = null, albumArtist: String? = null, album: String? = null,
        number: Int? = null) = Track(id, id, artist = artist, albumArtist = albumArtist, album = album,
        trackNumber = number, fileName = "$id.mp3", relativePath = "$id.mp3")

    @Test fun `compilations use album artist while artists stay distinct and albums with same title stay separate`() {
        val tracks = listOf(song("second", "Guest B", "Various Artists", "Collection", 2),
            song("first", "Guest A", "Various Artists", "Collection", 1), song("other", "Solo", album = "Collection"))
        val snapshot = LibrarySnapshot(tracks = tracks)
        val albums = LibraryViews.groups(snapshot, SongListView.Albums, LibraryViews.defaults(SongListView.Albums))
        assertEquals(2, albums.size)
        assertEquals(listOf("first", "second"), albums.first { it.subtitle == "Various Artists" }.tracks.map { it.id })
        assertEquals(3, LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).size)
        assertEquals(2, LibraryViews.groups(snapshot, SongListView.AlbumArtists, LibraryQuery()).size)
    }
    @Test fun `missing names and separator characters cannot collide with real tags`() {
        val tracks = listOf(song("missing"), song("literal", album = "Unknown album", artist = "Unknown album artist"),
            song("one", album = "a:b", artist = "c"), song("two", album = "a", artist = "b:c"))
        val groups = LibraryViews.groups(LibrarySnapshot(tracks = tracks), SongListView.Albums, LibraryQuery())
        assertEquals(4, groups.map { it.id }.toSet().size)
        val caseVariants = listOf(song("a", album = " Album ", artist = "ARTIST"), song("b", album = "album", artist = "artist"))
        assertEquals(1, LibraryViews.groups(LibrarySnapshot(tracks = caseVariants), SongListView.Albums, LibraryQuery()).size)
    }
    @Test fun `folder views preserve access paths for overlapping roots and filter complete group results`() {
        val track = song("shared").copy(locations = listOf(TrackLocation("one", "one-uri", "sub/shared.mp3"),
            TrackLocation("two", "two-uri", "shared.mp3")))
        val snapshot = LibrarySnapshot(listOf(FolderSelection("one", "Music"), FolderSelection("two", "sub")), listOf(track))
        val groups = LibraryViews.groups(snapshot, SongListView.Folders, LibraryQuery())
        assertEquals(2, groups.size)
        assertEquals(setOf("one-uri", "two-uri"), groups.flatMap { it.tracks }.map { it.uri }.toSet())
        val filtered = LibraryViews.groups(snapshot, SongListView.Folders, LibraryQuery(folderUri = "two", text = "shared"))
        assertEquals(1, filtered.size)
        assertEquals("two-uri", filtered.single().tracks.single().uri)
    }
    @Test fun `playlist occurrences survive rescan filtering reorder removal and queue snapshot`() {
        val a = song("a"); val b = song("b")
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(b, a, a))
        assertEquals(3, playlist.entries.map { it.id }.toSet().size)
        val rows = PlaylistEdits.rows(playlist, LibrarySnapshot(tracks = listOf(a.copy(title = "Updated"))), LibraryViews.defaults(SongListView.Playlists))
        assertEquals(listOf("b", "Updated", "Updated"), rows.map { it.track.title })
        assertEquals(playlist.entries.map { it.id }, rows.map { it.id })
        val queue = QueueEdits.create(rows.map { it.track }, 2, "Session")
        assertEquals(queue.entries[2].id, queue.currentEntryId)
        val reordered = PlaylistEdits.move(playlist, playlist.entries.last().id, -2)
        val removed = PlaylistEdits.remove(reordered, setOf(playlist.entries[1].id))
        assertEquals(listOf(playlist.entries[2].id, playlist.entries[0].id), removed.entries.map { it.id })
        assertEquals(3, queue.entries.size)
        val matches = PlaylistEdits.rows(playlist, LibrarySnapshot(), LibraryQuery(text = "a", sort = TrackSort.Title))
        assertEquals(playlist.entries.drop(1).map { it.id }, matches.map { it.id })
        assertEquals(playlist.entries, PlaylistEdits.rows(playlist, LibrarySnapshot(), LibraryViews.defaults(SongListView.Playlists)))
    }
    @Test fun `batch insertion preserves order and view overrides fall back to app preference`() {
        val queue = QueueEdits.create(listOf(song("a"), song("d")), 0, "Queue").copy(positionMs = 1200)
        val next = QueueEdits.insert(queue, listOf(song("b"), song("c")), SongTap.PlayNext)
        assertEquals(listOf("a", "b", "c", "d"), next.entries.map { it.track.id })
        assertEquals(queue.currentEntryId, next.currentEntryId)
        assertEquals(1200L, next.positionMs)
        val settings = QueueSettings(songTap = SongTap.Append, tapOverrides = mapOf(SongListView.Albums to SongTap.NewQueue))
        assertEquals(SongTap.NewQueue, settings.tapFor(SongListView.Albums))
        assertEquals(SongTap.Append, settings.tapFor(SongListView.Playlists))
        assertEquals(SongTap.Append, settings.copy(tapOverrides = emptyMap()).tapFor(SongListView.Albums))
    }
}

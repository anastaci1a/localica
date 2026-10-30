package io.github.anastaci1a.localica.library

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LibraryGroupPreparationTest {
    private val tracks = listOf(
        Track("b", "Same", artist = " Guest ", albumArtist = "Various", album = " Album ", trackNumber = 2,
            fileName = "b.mp3", relativePath = "b.mp3", playCount = 7,
            locations = listOf(TrackLocation("root", "root/b", "sub/b.mp3"), TrackLocation("child", "child/b", "b.mp3"))),
        Track("a", "Same", artist = "guest", albumArtist = "Various", album = "album", trackNumber = 1,
            fileName = "a.mp3", relativePath = "a.mp3", locations = listOf(TrackLocation("root", "root/a", "sub/a.mp3"))),
        Track("missing", "Unknown", fileName = "unknown.mp3", relativePath = "unknown.mp3"),
        Track("literal", "Literal", artist = "Unknown Album Artist", album = "Unknown album", fileName = "literal.mp3", relativePath = "literal.mp3")
    )
    private fun snapshot(values: List<Track> = tracks) = LibrarySnapshot(
        folders = listOf(FolderSelection("root", "Music"), FolderSelection("child", "Sub")), tracks = values)
    private class Reads(private val values: List<Track>) : AbstractList<Track>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): Track { reads++; return values[index] }
    }

    @Test fun sameQueryIsReadOnceAcrossAlbumArtistAndAlbumArtistGroups() {
        val source = Reads(tracks)
        val prepared = LibraryViews.Preparation(snapshot(source)) {}
        val views = listOf(SongListView.Albums, SongListView.Artists, SongListView.AlbumArtists)
        for (view in views) {
            val query = LibraryViews.defaults(view)
            assertEquals(LibraryViews.groups(snapshot(), view, query), prepared.groups(view, query))
            assertEquals(tracks.size, source.reads)
        }
    }

    @Test fun allQueryFieldsAndFolderCopiesRetainIndependentResults() {
        val source = snapshot()
        val prepared = LibraryViews.Preparation(source) {}
        val queries = TrackSort.entries.flatMap { sort -> listOf(true, false).flatMap { ascending ->
            listOf(LibraryQuery(sort = sort, ascending = ascending),
                LibraryQuery(text = "same", folderUri = "child", sort = sort, ascending = ascending))
        } }
        for (query in queries) for (view in SongListView.entries) {
            assertEquals("$view / $query", LibraryViews.groups(source, view, query), prepared.groups(view, query))
        }
        val folders = prepared.groups(SongListView.Folders, LibraryQuery(folderUri = "child"))
        assertEquals("child/b", folders.single().tracks.single().uri)
        assertEquals("b.mp3", folders.single().tracks.single().relativePath)
        assertEquals("root/b", tracks.first().locations.first().uri)
    }

    @Test fun aNewEvaluationUsesCurrentMetadataAndStatistics() {
        val query = LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)
        val old = LibraryViews.Preparation(snapshot()) {}
        val before = old.groups(SongListView.Artists, query)
        val changed = tracks.map { it.copy(artist = "New artist", album = "New album", playCount = 40, durationMs = 12345) }
        val fresh = LibraryViews.Preparation(snapshot(changed)) {}
        assertEquals(before, old.groups(SongListView.Artists, query))
        val after = fresh.groups(SongListView.Artists, query)
        assertEquals(LibraryViews.groups(snapshot(changed), SongListView.Artists, query), after)
        assertEquals(listOf("New artist"), after.map { it.title })
        assertTrue(after.single().tracks.all { it.playCount == 40L && it.durationMs == 12345L && it.album == "New album" })
    }

    @Test fun cancellationRemainsObservableForFreshAndReusedQueries() {
        val source = Reads(tracks)
        var cancel = true
        val prepared = LibraryViews.Preparation(snapshot(source)) { if (cancel) throw CancellationException("stop") }
        val query = LibraryViews.defaults(SongListView.Albums)
        fun rejected(view: SongListView) {
            try { prepared.groups(view, query); fail("Cancelled preparation returned groups") }
            catch (expected: CancellationException) { assertEquals("stop", expected.message) }
        }
        rejected(SongListView.Albums)
        assertEquals(0, source.reads)
        cancel = false
        assertEquals(LibraryViews.groups(snapshot(), SongListView.Albums, query), prepared.groups(SongListView.Albums, query))
        assertEquals(tracks.size, source.reads)
        cancel = true
        rejected(SongListView.Artists)
        assertEquals(tracks.size, source.reads)
    }
}

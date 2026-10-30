package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CollectionLookupTest {
    private val locations = listOf(TrackLocation("root-a", "access-a", "A/song.mp3"),
        TrackLocation("root-b", "access-b", "B/song.mp3"))
    private val song = Track("access-a", "Song", artist = "Artist", albumArtist = "Album artist", album = "Album",
        fileName = "song.mp3", relativePath = "A/song.mp3", id = "document", locations = locations)
    private fun library(vararg tracks: Track) = LibrarySnapshot(
        folders = listOf(FolderSelection("root-a", "First root"), FolderSelection("root-b", "Second root")),
        tracks = tracks.toList())
    private val actions = mapOf(PlayerAction.Album to MainTab.Albums, PlayerAction.Artist to MainTab.Artists,
        PlayerAction.AlbumArtist to MainTab.AlbumArtists, PlayerAction.Folder to MainTab.Folders)

    @Test fun eachActionKeepsOriginalFirstMatchingItemAndSortedSongObjects() {
        // Two records with the same identity in different groups exercise first-match selection.
        val snapshot = library(song, song.copy(album = "Zulu", artist = "Zulu", albumArtist = "Zulu"),
            song.copy(id = "other", title = "Earlier", fileName = "other.mp3"))
        for ((action, tab) in actions) for (ascending in listOf(true, false)) {
            val key = BrowsePreferenceKeys.type(BrowseRoute(tab))
            val sorts = BrowseSortSnapshot.fromValues(mapOf("$key.sort" to "Title", "$key.ascending" to ascending,
                "contents.Album.sort" to "Title", "contents.Album.ascending" to false))
            val original = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(tab), sorts = sorts).items
                .firstOrNull { item -> item.songs.any { it.track.id == song.id &&
                    (action != PlayerAction.Folder || sameSongInformationLocation(it.track, song)) } }
            val actual = collectionItemForTrack(action, song, snapshot, emptyList(), sorts)
            assertNotNull(original)
            assertSame(original, actual)
            assertSame(original!!.songs, actual!!.songs)
        }
    }
    @Test fun folderActionUsesTheSelectedAccessLocationEvenWhenAnotherRootSortsFirst() {
        val snapshot = library(song)
        val secondLocation = song.copy(uri = "access-b", relativePath = "B/song.mp3")
        val first = collectionItemForTrack(PlayerAction.Folder, song, snapshot, emptyList(), BrowseSortSnapshot.Default)!!
        val second = collectionItemForTrack(PlayerAction.Folder, secondLocation, snapshot, emptyList(), BrowseSortSnapshot.Default)!!
        assertNotEquals(first.ref, second.ref)
        assertEquals("access-a", first.songs.single().track.uri)
        assertEquals("access-b", second.songs.single().track.uri)
        assertEquals("B/song.mp3", second.songs.single().track.relativePath)
        assertNull(collectionItemForTrack(PlayerAction.Folder,
            song.copy(uri = "missing", relativePath = "missing", locations = emptyList()), snapshot, emptyList(), BrowseSortSnapshot.Default))
    }
    @Test fun missingTracksStayMissingAndSourceReplacementCannotReturnAnOldCollection() {
        val original = library(song)
        val before = collectionItemForTrack(PlayerAction.Album, song, original, emptyList(), BrowseSortSnapshot.Default)!!
        val changed = song.copy(album = "New album", title = "New song")
        val after = collectionItemForTrack(PlayerAction.Album, changed, library(changed), emptyList(), BrowseSortSnapshot.Default)!!
        assertNotEquals(before.ref, after.ref)
        assertEquals("New album", after.title)
        assertEquals("New song", after.songs.single().track.title)
        for (action in actions.keys) {
            assertNull(collectionItemForTrack(action, song.copy(id = "absent"), original, emptyList(), BrowseSortSnapshot.Default))
            assertNull(collectionItemForTrack(action, song, library(), emptyList(), BrowseSortSnapshot.Default))
        }
    }
    @Test fun cancellationPropagatesForColdAndWarmCatalogsWithoutPublishingAPartialResult() {
        val snapshot = library(song)
        val failure = CancellationException("caller superseded")
        repeat(2) {
            try {
                collectionItemForTrack(PlayerAction.Album, song, snapshot, emptyList(), BrowseSortSnapshot.Default) { throw failure }
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(failure, actual) }
            assertEquals(song.id, collectionItemForTrack(PlayerAction.Album, song, snapshot, emptyList(), BrowseSortSnapshot.Default)!!.songs.single().track.id)
        }
    }
}

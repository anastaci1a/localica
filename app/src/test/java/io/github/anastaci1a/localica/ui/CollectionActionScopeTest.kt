package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class CollectionActionScopeTest {
    private val track = Track("scope:a", "A", album = "A", fileName = "a", relativePath = "a")
    private val rows = listOf(SongRow("last-first", track, "p2"),
        SongRow("different", track.copy(id = "b", album = "B"), "p1"), SongRow("same-file-again", track, "p3"))
    private val ref = MediaRef(MediaKind.Artist, "artist")
    private fun result(values: List<SongRow>) = BrowseResult(values.map { BrowserItem(
        MediaRef(MediaKind.Song, it.track.id), it.track.title, null, listOf(it), row = it) })

    @Test fun actionsAndMultiselectionShareExactOrderedOccurrencesForOneSubsetAllAndChanges() {
        for (visible in listOf(rows.take(1), listOf(rows[0], rows[2]), rows, rows.drop(1), emptyList())) {
            val target = collectionActionTarget(ref, "Artist", result(visible))
            assertEquals(visible, target.songs)
            assertEquals(visible, target.selectionTarget().rows)
            assertEquals(ref, target.selectionTarget().source)
            assertEquals(visible.map { it.id }, target.songs.map { it.id })
            assertEquals(visible.map { it.sourcePlaylistId }, target.songs.map { it.sourcePlaylistId })
        }
    }

    @Test fun actionTargetDoesNotDeduplicateReorderOrShuffleTheDisplayedSnapshot() {
        val shown = listOf(rows[2], rows[0], rows[1])
        val target = collectionActionTarget(ref, "Title", result(shown), "cover")
        assertEquals(listOf("same-file-again", "last-first", "different"), target.songs.map { it.id })
        assertEquals(listOf("p3", "p2", "p1"), target.songs.map { it.sourcePlaylistId })
        assertSame(track, target.songs[0].track)
        assertSame(track, target.songs[1].track)
        assertEquals("cover", target.coverUri)
        assertEquals("Title", target.title)
    }

    @Test fun shortTapAlwaysMakesOneExplicitAlbumEvenWhenAlreadySelected() {
        assertEquals(listOf("a"), ArtistAlbumSelection.only("a"))
        val explicit = ArtistAlbumSelection.only("a")
        assertFalse(explicit.isEmpty())
        assertTrue(ArtistAlbumSelection.shownSelected(explicit, "a"))
        assertFalse(ArtistAlbumSelection.shownSelected(explicit, "b"))
        assertEquals(explicit, ArtistAlbumSelection.only("a"))
    }

    @Test fun longTogglePreservesOtherAlbumsAndLastRemovalReturnsAll() {
        val albums = listOf("a", "b", "c")
        assertEquals(listOf("b", "c"), ArtistAlbumSelection.toggle(emptyList(), "a", albums))
        assertEquals(listOf("a", "b"), ArtistAlbumSelection.toggle(listOf("a"), "b", albums))
        assertEquals(listOf("b"), ArtistAlbumSelection.toggle(listOf("a", "b"), "a", albums))
        val all = ArtistAlbumSelection.toggle(listOf("b"), "b", albums)
        assertTrue(all.isEmpty())
        assertTrue(albums.all { ArtistAlbumSelection.shownSelected(all, it) })
    }

    @Test fun singleAlbumExplicitSelectionAndAllRemainDistinctModes() {
        val albums = listOf("only")
        val explicit = ArtistAlbumSelection.only("only")
        assertFalse("Explicit single never becomes the All sentinel", explicit.isEmpty())
        assertTrue(ArtistAlbumSelection.shownSelected(explicit, "only"))
        val all = ArtistAlbumSelection.toggle(explicit, "only", albums)
        assertTrue(all.isEmpty())
        assertTrue(ArtistAlbumSelection.shownSelected(all, "only"))
        assertTrue(ArtistAlbumSelection.toggle(all, "only", albums).isEmpty())
    }

    @Test fun albumArtistAlbumAndPlaylistKeepTheirOwnCollectionIdentity() {
        for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Album, MediaKind.Playlist)) {
            val source = MediaRef(kind, "source")
            val target = collectionActionTarget(source, "Source", result(rows))
            assertEquals(source, target.ref)
            assertNull(target.row)
            assertEquals(rows, target.songs)
        }
    }
}

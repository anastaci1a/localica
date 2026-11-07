package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class SessionSelectionTest {
    private val track = Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
    @Test fun sameSongIsSelectedAcrossEveryViewAndTappingAnyOccurrenceDeselectsIt() {
        val state = SessionSelection()
        val album = MediaRef(MediaKind.Album, "album")
        state.select(album, listOf(SongRow("album-row", track)))
        for (kind in MediaKind.entries) {
            val source = MediaRef(kind, "other-view")
            val row = SongRow(kind.name + "-row", track.copy(title = "Refreshed metadata"))
            val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
            assertEquals("Selected in " + kind, 1, state.selectedCount(item, source))
            state.select(source, listOf(row, row))
            assertEquals("Unique action input in " + kind, 1, state.entries.size)
            state.toggle(source, row)
            assertTrue("Deselected everywhere from " + kind, state.entries.isEmpty())
            state.select(album, listOf(SongRow("album-row", track)))
        }
    }
    @Test fun heldPointerDefersOnlyNewPanelAndReleasePreservesMembership() {
        val state = SessionSelection()
        val token = state.beginPresentationHold()
        state.select(null, listOf(SongRow("entry", track)))
        assertTrue(state.active)
        assertFalse(state.panelVisible)
        state.endPresentationHold(Any())
        state.endPresentationHold(null)
        assertFalse(state.panelVisible)
        state.endPresentationHold(token)
        assertTrue(state.panelVisible)
        assertEquals(listOf("entry"), state.entries.map { it.row.id })
        state.collapsed = true
        assertNull(state.beginPresentationHold())
        assertTrue(state.panelVisible)
        assertTrue(state.collapsed)
    }
    @Test fun clearingMembershipDoesNotReleaseAnOutstandingPointerOrALaterOwner() {
        val state = SessionSelection()
        val first = state.beginPresentationHold()
        state.select(null, listOf(SongRow("entry", track)))
        state.clear()
        state.select(null, listOf(SongRow("second", track)))
        assertFalse(state.panelVisible)
        state.endPresentationHold(first)
        state.clear()
        val second = state.beginPresentationHold()
        state.select(null, listOf(SongRow("third", track)))
        state.endPresentationHold(first)
        assertFalse(state.panelVisible)
        state.endPresentationHold(second)
        assertTrue(state.panelVisible)
    }
    @Test fun collectionSelectionUsesItsOwnSourceInCatalogsSearchAndOtherCollections() {
        val rows = listOf(SongRow("entry1", track), SongRow("entry2", track))
        for (kind in MediaKind.entries.filterNot { it == MediaKind.Song }) {
            val ref = MediaRef(kind, "collection")
            val item = BrowserItem(ref, "Collection", null, rows)
            for (containingSource in listOf(null, ref, MediaRef(MediaKind.Folder, "parent"))) {
                assertEquals(SongSelectionTarget(ref, rows), item.selectionTarget(containingSource))
            }
        }
    }
    @Test fun songSelectionUsesContainingOccurrencesAndStandaloneSongsHaveNoSyntheticSource() {
        val source = MediaRef(MediaKind.Playlist, "playlist")
        val row = SongRow("entry1", track, source.id)
        val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
        assertEquals(SongSelectionTarget(source, listOf(row)), item.selectionTarget(source))
        assertEquals(SongSelectionTarget(null, listOf(row)), item.selectionTarget())
    }
    @Test fun standaloneSongsAndCollectionsShareMembershipWithoutChangingSourceProvenance() {
        val source = MediaRef(MediaKind.Playlist, "playlist")
        val row = SongRow(track.id, track)
        val song = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
        val collection = BrowserItem(source, "Playlist", null, listOf(row))
        val state = SessionSelection()
        state.select(source, listOf(row))
        assertEquals(1, state.selectedCount(song))
        assertEquals(1, state.selectedCount(song, source))
        assertEquals(1, state.selectedCount(collection))
        state.select(null, listOf(row))
        assertEquals(source, state.entries.single().source)
        state.toggle(null, row)
        assertEquals(0, state.selectedCount(song))
        assertTrue(state.entries.isEmpty())
    }
    @Test fun selectingAPlaylistRetainsAllSourceOccurrencesWithOneActionInputPerSong() {
        val source = MediaRef(MediaKind.Playlist, "playlist")
        val rows = listOf(SongRow("entry1", track, source.id), SongRow("entry2", track, source.id))
        val target = BrowserItem(source, "Playlist", null, rows).selectionTarget()
        val state = SessionSelection()
        state.select(target.source, target.rows)
        assertTrue(rows.all { state.contains(it) })
        state.select(source, rows)
        assertEquals(1, state.entries.size)
        assertEquals(setOf("entry1", "entry2"), state.entries.single().sourceRowIds)
        assertEquals(source, state.entries.single().source)
        state.toggle(source, rows.last())
        assertTrue(state.entries.isEmpty())
    }
    @Test fun visibleDuplicateOccurrencesAreRetainedWhenSelectingOneRow() {
        val state = SessionSelection()
        val source = MediaRef(MediaKind.Queue, "queue")
        val rows = listOf(SongRow("entry1", track), SongRow("entry2", track))
        state.source = source
        state.visible = rows
        state.select(source, rows.take(1))
        assertEquals(setOf("entry1", "entry2"), state.entries.single().sourceRowIds)
        assertEquals(1, state.entries.size)
    }
    @Test fun sourceProvenanceAndAppendOrderSurviveDeduplicationAcrossCollections() {
        val state = SessionSelection()
        val first = MediaRef(MediaKind.Playlist, "one")
        val second = MediaRef(MediaKind.Playlist, "two")
        val rows = listOf(SongRow("entry1", track), SongRow("entry2", track.copy(id = "other")))
        state.select(first, rows + rows)
        state.select(second, rows.map { it.copy(id = "other-" + it.id) })
        state.select(first, rows)
        assertEquals(2, state.entries.size)
        assertEquals(2, state.entries.map { it.id }.distinct().size)
        assertTrue(state.entries.all { it.source == first })
        state.toggle(second, rows.first())
        state.toggle(second, rows.first())
        assertEquals(listOf("entry2", "entry1"), state.entries.map { it.row.id })
        assertEquals(second, state.entries.last().source)
    }
    @Test fun emptySelectionStaysActiveUntilExplicitCloseAndNewSessionStartsEmpty() {
        val state = SessionSelection()
        val row = SongRow("one", track)
        state.select(null, listOf(row)); state.toggle(null, row)
        assertTrue(state.active)
        assertTrue(state.entries.isEmpty())
        state.clear()
        assertFalse(state.active)
        assertFalse(SessionSelection().active)
    }
    @Test fun addingToACollapsedSelectionDoesNotReopenItsPanel() {
        val state = SessionSelection()
        state.select(null, listOf(SongRow("one", track)))
        state.collapsed = true
        state.select(null, listOf(SongRow("two", track)))
        assertTrue(state.collapsed)
        state.clear()
        state.select(null, listOf(SongRow("three", track)))
        assertFalse(state.collapsed)
    }
    @Test fun rangeFindsAnchorsFromOtherViewsAndAppendsUniqueSongsInVisibleOrder() {
        val state = SessionSelection()
        val rows = (0..4).map { SongRow("$it", track.copy(id = "$it")) }
        state.visible = rows
        state.select(MediaRef(MediaKind.Album, "album"), listOf(rows[3], rows[1]).map { it.copy(id = "album-" + it.id) })
        state.range()
        assertEquals(listOf("album-3", "album-1", "2"), state.entries.map { it.row.id })
    }
}

package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class SessionSelectionTest {
    private val track = Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
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
    @Test fun clearingMembershipRevokesItsHoldWithoutReleasingALaterOwner() {
        val state = SessionSelection()
        val first = state.beginPresentationHold()
        state.select(null, listOf(SongRow("entry", track)))
        state.clear()
        state.select(null, listOf(SongRow("second", track)))
        assertTrue(state.panelVisible)
        state.endPresentationHold(first)
        state.clear()
        val second = state.beginPresentationHold()
        state.select(null, listOf(SongRow("third", track)))
        state.endPresentationHold(first)
        assertFalse(state.panelVisible)
        state.endPresentationHold(second)
        assertTrue(state.panelVisible)
    }
    @Test fun acquisitionAdoptsOnlyItsContactAndRetiresWithoutInheritingSelection() {
        val state = SessionSelection()
        val owner = Any() to 1
        val pointer = state.beginPresentationHold(owner)
        val row = SongRow("entry", track)
        state.select(null, listOf(row))
        val foreign = state.adoptPresentationHold(Any() to 1)
        foreign.second()
        assertFalse(state.panelVisible)
        val drag = state.adoptPresentationHold(owner)
        state.endPresentationHold(pointer)
        assertFalse(state.panelVisible)
        drag.second()
        assertTrue(state.panelVisible)
        assertEquals(listOf(row), state.entries.map { it.row })
        state.clear()
        val newer = state.beginPresentationHold()
        state.select(null, listOf(row))
        drag.first(); drag.second()
        assertTrue(state.active)
        assertFalse(state.panelVisible)
        state.endPresentationHold(newer)
        assertTrue(state.panelVisible)
    }
    @Test fun successfulAcquisitionClearsOnlyItsSelectionAndPreselectionStaysVisible() {
        val state = SessionSelection()
        val owner = Any()
        val pointer = state.beginPresentationHold(owner)
        state.select(null, listOf(SongRow("entry", track)))
        val drag = state.adoptPresentationHold(owner)
        state.endPresentationHold(pointer)
        drag.first()
        assertFalse(state.active)
        assertFalse(state.panelVisible)
        drag.second()
        state.select(null, listOf(SongRow("entry", track)))
        val preselected = state.adoptPresentationHold(owner)
        assertTrue(state.panelVisible)
        preselected.second()
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
    @Test fun librarySelectionFollowsTheSameOccurrenceAcrossViewsAndKeepsFirstProvenance() {
        val album = MediaRef(MediaKind.Album, "album")
        val artist = MediaRef(MediaKind.Artist, "artist")
        val row = SongRow(track.id, track)
        val song = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
        val state = SessionSelection()
        state.select(album, listOf(row))
        for (source in listOf(null, album, artist, MediaRef(MediaKind.Folder, "folder"))) {
            assertEquals(1, state.selectedCount(song, source))
            state.select(source, listOf(row.copy(track = track.copy(title = "Updated"))))
        }
        assertEquals(1, state.entries.size)
        assertEquals(album, state.entries.single().source)
        state.toggle(artist, row)
        assertTrue(state.entries.isEmpty())
    }
    @Test fun libraryAndMutableOccurrencesDoNotBorrowEachOthersMembership() {
        val playlist = MediaRef(MediaKind.Playlist, "same")
        val queue = MediaRef(MediaKind.Queue, "same")
        val row = SongRow(track.id, track)
        val song = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
        val state = SessionSelection()
        state.select(playlist, listOf(row))
        assertEquals(0, state.selectedCount(song))
        assertEquals(0, state.selectedCount(song, queue))
        assertEquals(1, state.selectedCount(song, playlist))
        state.select(null, listOf(row))
        state.select(queue, listOf(row))
        assertEquals(3, state.entries.size)
        state.toggle(null, row)
        assertEquals(2, state.entries.size)
        assertEquals(1, state.selectedCount(song, playlist))
        assertEquals(1, state.selectedCount(song, queue))
    }
    @Test fun collectionSummariesUseOccurrenceMembershipRatherThanTrackMembership() {
        val state = SessionSelection()
        val playlist = MediaRef(MediaKind.Playlist, "playlist")
        val rows = listOf(SongRow("entry1", track), SongRow("entry2", track))
        val item = BrowserItem(playlist, "Playlist", null, rows)
        state.select(playlist, listOf(rows.first()))
        assertEquals(1, state.selectedCount(item))
        state.select(playlist, listOf(rows.last()))
        assertEquals(2, state.selectedCount(item))
        state.clear()
        state.select(null, listOf(SongRow(track.id, track)))
        assertEquals(0, state.selectedCount(item))
    }
    @Test fun libraryRangeRecognizesEndpointsSelectedInOtherViews() {
        val state = SessionSelection()
        val album = MediaRef(MediaKind.Album, "album")
        val artist = MediaRef(MediaKind.Artist, "artist")
        val rows = (0..4).map { SongRow("$it", track.copy(id = "$it")) }
        state.select(album, listOf(rows[3], rows[1]))
        state.source = artist
        state.visible = rows
        state.range()
        assertEquals(listOf("3", "1", "2"), state.entries.map { it.row.id })
    }
    @Test fun selectingAPlaylistInItsCatalogRetainsItsOccurrencesInsideThePlaylist() {
        val source = MediaRef(MediaKind.Playlist, "playlist")
        val rows = listOf(SongRow("entry1", track, source.id), SongRow("entry2", track, source.id))
        val target = BrowserItem(source, "Playlist", null, rows).selectionTarget()
        val state = SessionSelection()
        state.select(target.source, target.rows)
        assertTrue(rows.all { state.contains(source, it.id) })
        state.select(source, rows)
        assertEquals(2, state.entries.size)
        state.toggle(source, rows.first())
        assertEquals(listOf("entry2"), state.entries.map { it.row.id })
        assertEquals(source, state.entries.map { it.source }.distinct().single())
    }
    @Test fun sourceOccurrencesKeepAppendOrderAndDuplicatesAcrossCollections() {
        val state = SessionSelection()
        val first = MediaRef(MediaKind.Playlist, "one")
        val second = MediaRef(MediaKind.Playlist, "two")
        val rows = listOf(SongRow("entry1", track), SongRow("entry2", track))
        state.select(first, rows + rows)
        state.select(second, rows)
        state.select(first, rows)
        assertEquals(4, state.entries.size)
        assertEquals(4, state.entries.map { it.id }.distinct().size)
        state.toggle(first, rows.first())
        state.toggle(first, rows.first())
        assertEquals(listOf("entry2", "entry1", "entry2", "entry1"), state.entries.map { it.row.id })
        assertEquals(first, state.entries.last().source)
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
    @Test fun rangeAppendsMissingOccurrencesWithoutReorderingExistingSelection() {
        val state = SessionSelection()
        val rows = (0..4).map { SongRow("$it", track) }
        state.visible = rows
        state.select(null, listOf(rows[3], rows[1]))
        state.range()
        assertEquals(listOf("3", "1", "2"), state.entries.map { it.row.id })
    }
}

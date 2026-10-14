package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class SessionSelectionTest {
    private val track = Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
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

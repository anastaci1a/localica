package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

class PlaylistDestinationsTest {
    private val song = Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
    @Test fun existingAndNewDestinationsAreOneEditWithIndependentOccurrences() {
        val original = listOf(ManualPlaylist(name = "First"), ManualPlaylist(name = "Second"), ManualPlaylist(name = "Untouched"))
        val changed = PlaylistEdits.addToDestinations(original, original.take(2).map { it.id }.toSet(), " New ", listOf(song, song))
        assertEquals(listOf("First", "Second", "Untouched", "New"), changed.map { it.name })
        assertSame(original[2], changed[2])
        assertEquals(listOf(2, 2, 0, 2), changed.map { it.entries.size })
        assertEquals(6, changed.flatMap { it.entries }.map { it.id }.distinct().size)
        assertTrue(original.all { it.entries.isEmpty() })
    }
    @Test fun trimmedCaseInsensitiveDuplicatesAndMissingDestinationsRejectTheWholeEdit() {
        val original = listOf(ManualPlaylist(name = "  MiX  "))
        assertFalse(PlaylistEdits.nameAvailable(original, "mix"))
        assertTrue(PlaylistEdits.nameAvailable(original, " mix ", original.single().id))
        for ((ids, name) in listOf(setOf(original.single().id) to "MIX", setOf("deleted") to "New")) {
            try { PlaylistEdits.addToDestinations(original, ids, name, listOf(song)); fail("Invalid transaction must fail") }
            catch (_: IllegalArgumentException) { }
            assertTrue(original.single().entries.isEmpty())
        }
    }
}

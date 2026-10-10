package io.github.anastaci1a.localica.home

import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class PinEditsTest {
    @Test fun mixedPinsKeepTypeIdentityAndManualOrder() {
        val song = MediaRef(MediaKind.Song, "same-id")
        val album = MediaRef(MediaKind.Album, "same-id")
        val playlist = MediaRef(MediaKind.Playlist, "mix")
        val initial = listOf(song, album, playlist)
        assertEquals(listOf(song, playlist, album), PinEdits.move(initial, playlist, -1))
        assertEquals(listOf(album, playlist), PinEdits.toggle(initial, song))
        assertEquals(initial, PinEdits.move(initial, song, -1))
        assertEquals(initial, PinEdits.toggle(PinEdits.toggle(initial, playlist), playlist))
    }
}

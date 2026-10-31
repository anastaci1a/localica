package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BrowseResultPreparationTest {
    private fun song(id: String) = SongRow(id, Track("uri:$id", id, fileName = "$id.mp3", relativePath = "$id.mp3"))

    @Test fun preparedRowsAndKeyIndicesMatchTheirDefinitionsAndStayStable() {
        val album = BrowserItem(MediaRef(MediaKind.Album, "a"), "Album", null, listOf(song("x")))
        val rows = listOf(song("one"), song("two"), song("one"))
        val items = listOf(album) + rows.map { BrowserItem(MediaRef(MediaKind.Song, it.track.id), it.track.title, null, listOf(it), row = it) }
        val result = BrowseResult(items).prepared()
        assertEquals(items.mapNotNull { it.row }, result.rows)
        assertEquals(items.withIndex().associate { it.value.key to it.index }, result.keyIndices)
        assertEquals(3, result.keyIndices["one"]) // Duplicate keys keep the last occurrence, as before.
        assertEquals(0, result.keyIndices["Album:a"])
        // One derivation per result: repeated reads return the same instances.
        assertSame(result.rows, result.rows)
        assertSame(result.keyIndices, result.keyIndices)
        // Data-class equality and copies are unaffected; a copy derives its own values.
        val copy = result.copy(items = items.drop(1))
        assertEquals(rows, copy.rows)
        assertEquals(BrowseResult(items), result)
    }
}

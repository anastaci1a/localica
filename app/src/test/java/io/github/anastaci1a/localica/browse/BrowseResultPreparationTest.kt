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

    @Test fun rowsAndKeyIndicesMatchTheirDefinitionsAndStayStable() {
        val album = BrowserItem(MediaRef(MediaKind.Album, "a"), "Album", null, listOf(song("x")))
        val rows = listOf(song("one"), song("two"), song("one"))
        val items = listOf(album) + rows.map { BrowserItem(MediaRef(MediaKind.Song, it.track.id), it.track.title, null, listOf(it), row = it) }
        val result = BrowseResult(items)
        assertEquals(items.mapNotNull { it.row }, result.rows)
        for ((key, index) in items.withIndex().associate { it.value.key to it.index }) assertEquals(index, result.indexOfKey(key))
        assertEquals(3, result.indexOfKey("one")) // Duplicate keys keep the last occurrence, as before.
        assertEquals(0, result.indexOfKey("Album:a"))
        // One derivation per result: repeated reads return the same instance.
        assertSame(result.rows, result.rows)
        // Data-class equality is unaffected; another result derives its own values.
        assertEquals(rows, BrowseResult(items.drop(1)).rows)
        assertEquals(BrowseResult(items), result)
    }
}

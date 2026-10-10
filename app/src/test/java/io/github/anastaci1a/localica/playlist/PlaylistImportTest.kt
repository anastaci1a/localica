package io.github.anastaci1a.localica.playlist

import io.github.anastaci1a.localica.library.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistImportTest {
    private fun song(id: String, path: String) = Track("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F${path.replace("/", "%2F")}", id,
        fileName = path.substringAfterLast('/'), relativePath = path, id = id)
    private val first = song("first", "A/Same.mp3")
    private val second = song("second", "B/Same.mp3")
    private val third = song("third", "A/Unique + ü.mp3")
    private val library = LibrarySnapshot(tracks = listOf(first, second, third))
    @Test fun `relative paths take precedence and duplicates retain order`() {
        val parsed = PlaylistImport.parse("#EXTM3U\nSame.mp3\n../B/Same.mp3\nSame.mp3".toByteArray(), "Mix.m3u8", library, "Music/A")
        assertEquals(listOf(first, second, first), parsed.tracks)
        assertEquals("Mix", parsed.name)
    }
    @Test fun `ambiguous names and remote entries stay unresolved`() {
        val parsed = PlaylistImport.parse("Same.mp3\nhttps://example.com/Same.mp3\nabsent.mp3\nUnique + ü.mp3".toByteArray(), "Mix.m3u", library)
        assertEquals(3, parsed.entries.count { it.track == null })
        assertEquals(third, parsed.tracks.single())
        assertTrue(parsed.entries.last().nameOnly)
        assertTrue(parsed.entries.first().problem!!.contains("More than one"))
    }
    @Test fun `PLS numeric ordering differs from lexical ordering`() {
        val parsed = PlaylistImport.parse("[playlist]\nFile10=A/Same.mp3\nFile2=B/Same.mp3\nFile1=A/Unique + ü.mp3".toByteArray(), "Mix.pls", library)
        assertEquals(listOf(third, second, first), parsed.tracks)
    }
    @Test fun `file URLs decode spaces unicode and plus signs without name ambiguity`() {
        val parsed = PlaylistImport.parse("file:///Music/A/Unique%20+%20%C3%BC.mp3".toByteArray(), "Mix.m3u8", library)
        assertEquals(third, parsed.tracks.single())
        assertEquals("Music/A/Unique + ü.mp3", PlaylistImport.documentPath(third.uri))
    }
    @Test fun `oversized malformed and unsupported playlists fail before saving`() {
        for ((bytes, name) in listOf(ByteArray(PlaylistImport.MAX_BYTES + 1) to "big.m3u", byteArrayOf(0xff.toByte()) to "bad.m3u8",
            "[playlist]\nFile1=A\nFile1=B".toByteArray() to "bad.pls", "# comments only".toByteArray() to "empty.m3u", "A".toByteArray() to "other.xml")) {
            try { PlaylistImport.parse(bytes, name, library); fail(name) } catch (_: IllegalArgumentException) { }
        }
    }
}

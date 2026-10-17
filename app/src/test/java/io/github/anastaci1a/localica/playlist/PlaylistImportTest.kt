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
    @Test fun `M3U and M3U8 accept exactly the entry limit and reject the next song`() {
        for (extension in listOf("m3u", "m3u8")) {
            val maximum = "a\n".repeat(PlaylistImport.MAX_ENTRIES)
            val parsed = PlaylistImport.parse(maximum.toByteArray(), "Mix.$extension", LibrarySnapshot())
            assertEquals(PlaylistImport.MAX_ENTRIES, parsed.entries.size)
            assertTrue(parsed.entries.all { it.path == "a" })
            assertFailure("Playlist exceeds the 20,000-song limit.", maximum + "b", "Mix.$extension")
        }
    }
    @Test fun `PLS bounds entries while preserving numeric order and repeated song paths`() {
        val maximum = buildString {
            append("[playlist]\n")
            for (index in PlaylistImport.MAX_ENTRIES downTo 1) append("File$index=${index % 2}\n")
        }
        val parsed = PlaylistImport.parse(maximum.toByteArray(), "Mix.pls", LibrarySnapshot())
        assertEquals(PlaylistImport.MAX_ENTRIES, parsed.entries.size)
        assertEquals(List(PlaylistImport.MAX_ENTRIES) { ((it + 1) % 2).toString() }, parsed.entries.map { it.path })
        assertFailure("Playlist exceeds the 20,000-song limit.", maximum + "File20001=a\nFile1=duplicate", "Mix.pls")
    }
    @Test fun `maximum byte input with tiny entries rejects without retaining all lines`() {
        val tinyM3u = "a\n".repeat(PlaylistImport.MAX_BYTES / 2)
        assertEquals(PlaylistImport.MAX_BYTES, tinyM3u.length)
        assertFailure("Playlist exceeds the 20,000-song limit.", tinyM3u, "Mix.m3u8")
        val tinyPls = buildString {
            append("[playlist]\n")
            for (index in 1..20001) append("File$index=a\n")
        }.padEnd(PlaylistImport.MAX_BYTES, '\n')
        assertFailure("Playlist exceeds the 20,000-song limit.", tinyPls, "Mix.pls")
    }
    @Test fun `maximum byte comment heavy playlists retain only song entries`() {
        for ((name, prefix) in listOf("Mix.m3u8" to "a\n", "Mix.pls" to "[playlist]\nFile1=a\n")) {
            val comments = prefix + "#\n".repeat((PlaylistImport.MAX_BYTES - prefix.length) / 2)
            val bytes = comments.padEnd(PlaylistImport.MAX_BYTES, '\n').toByteArray()
            assertEquals(PlaylistImport.MAX_BYTES, bytes.size)
            val parsed = PlaylistImport.parse(bytes, name, LibrarySnapshot())
            assertEquals(listOf("a"), parsed.entries.map { it.path })
        }
    }
    @Test fun `PLS keeps positive unique numeric indices and accepts a late case insensitive header`() {
        for (entries in listOf("File0=a", "File2147483648=a", "File01=a\nFile1=b")) {
            assertFailure("The PLS file has invalid or repeated entry numbers.", "[playlist]\n$entries", "Mix.pls")
        }
        val parsed = PlaylistImport.parse("File2=b\nFile1=  a  \n[PLAYLIST]".toByteArray(), "Mix.pls", LibrarySnapshot())
        assertEquals(listOf("a", "b"), parsed.entries.map { it.path })
        assertFailure("This is not a PLS playlist.", "File1=a", "Mix.pls")
    }
    @Test fun `BOM line endings and legacy encoding remain supported`() {
        val parsed = PlaylistImport.parse("\uFEFF#EXTM3U\r\n  A/Same.mp3 \r\n\r\nB/Same.mp3\r".toByteArray(), "Mix.m3u8", library)
        assertEquals(listOf(first, second), parsed.tracks)
        for (name in listOf("Mix.m3u", "Mix.pls")) {
            val text = if (name.endsWith("pls")) "[playlist]\nFile1=café.mp3" else "café.mp3"
            val legacy = PlaylistImport.parse(text.toByteArray(Charsets.ISO_8859_1), name, LibrarySnapshot())
            assertEquals("café.mp3", legacy.entries.single().path)
        }
        assertFailure("The playlist contains unsupported binary or UTF-16 data.", "a\u0000b", "Mix.m3u")
    }
    private fun assertFailure(message: String, text: String, name: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PlaylistImport.parse(text.toByteArray(), name, LibrarySnapshot())
        }
        assertEquals(message, failure.message)
    }

}

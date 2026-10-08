package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TreeScannerTest {
    @Test fun `scans nested folders once and ignores non audio files`() = runTest {
        val source = FakeDocuments()
        val nested = directory("nested")
        val mp3 = audio("one", "01.MP3")
        source.entries["root"] = listOf(nested, mp3, audio("cover", "cover.jpg", "image/jpeg"))
        source.entries["nested"] = listOf(audio("two", "02.m4a"), mp3, directory("root"))
        source.tags["one"] = AudioMetadata(title = " Zebra ", artist = "Artist")
        source.tags["two"] = AudioMetadata(title = "Alpha", durationMs = 3000)
        val result = TreeScanner(source).scan("tree")
        assertEquals(listOf("Alpha", "Zebra"), result.tracks.map { it.title })
        assertEquals("nested/02.m4a", result.tracks.first().relativePath)
        assertEquals(listOf("root", "nested"), source.queried)
        assertEquals(2, source.metadataReads)
    }

    @Test fun `keeps filename when metadata is broken and normalizes missing tags`() = runTest {
        val source = FakeDocuments()
        source.entries["root"] = listOf(audio("broken", "A song.mp3"), audio("unknown", "B.m4a"))
        source.tags["unknown"] = AudioMetadata(title = "  ", artist = "<unknown>", durationMs = -1)
        source.metadataFailure = IOException("Bad tags")
        source.failureUri = "broken"
        val result = TreeScanner(source).scan("tree")
        assertEquals(1, result.metadataFailures)
        assertEquals("A song", result.tracks.first().title)
        assertNull(result.tracks.last().artist)
        assertNull(result.tracks.last().durationMs)
    }

    @Test fun `propagates cancellation instead of treating it as missing metadata`() = runTest {
        val source = FakeDocuments()
        source.entries["root"] = listOf(audio("cancelled", "song.mp3"))
        source.metadataFailure = CancellationException("Cancelled")
        source.failureUri = "cancelled"
        try {
            TreeScanner(source).scan("tree")
            fail("Cancellation must abort the scan")
        } catch (_: CancellationException) { }
    }

    @Test fun `recognizes common formats without claiming videos`() {
        listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus").forEach {
            assertTrue(AudioFiles.accepts("song.$it", "application/octet-stream"))
        }
        assertTrue(AudioFiles.accepts("no extension", "audio/mpeg"))
        assertFalse(AudioFiles.accepts("video.m4a", "video/mp4"))
        assertFalse(AudioFiles.accepts("song.mp3.txt", "text/plain"))
    }
}

internal fun directory(id: String) = ScanDocument(id, id, id, "directory", true)
internal fun audio(id: String, name: String, mime: String = "application/octet-stream") = ScanDocument(id, id, name, mime, false)

internal class FakeDocuments : DocumentSource {
    val entries = mutableMapOf<String, List<ScanDocument>>("root" to emptyList())
    val tags = mutableMapOf<String, AudioMetadata>()
    val queried = mutableListOf<String>()
    var metadataReads = 0
    var metadataFailure: Exception? = null
    var failureUri: String? = null
    var directoryFailure: Exception? = null
    override fun root(treeUri: String) = directory("root")
    override fun children(treeUri: String, documentId: String): List<ScanDocument> {
        queried.add(documentId)
        directoryFailure?.let { throw it }
        return entries[documentId].orEmpty()
    }
    override fun metadata(uri: String): AudioMetadata {
        metadataReads++
        if (uri == failureUri) metadataFailure?.let { throw it }
        return tags[uri] ?: AudioMetadata()
    }
}

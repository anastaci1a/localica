package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TreeScannerTest {
    @Test fun `large cached library enumeration does not open indexed audio`() = runTest {
        val source = FakeDocuments()
        val known = (0 until 13_300).associate { index ->
            val id = "song-$index"
            id to Track(id, "Title $index", fileName = "$id.mp3", relativePath = "$id.mp3")
        }
        source.entries["root"] = known.keys.map { audio(it, "$it.mp3") }
        val result = TreeScanner(source).scan("tree", knownTracks = known)
        assertEquals(13_300, result.tracks.size)
        assertEquals(0, source.metadataReads)
        assertEquals(1, result.metrics.directoryQueries)
        println("StartupScanBenchmark tracks=13300 directoryQueries=1 metadataReads=0 elapsedNanos=${result.metrics.elapsedNanos} synthetic=true")
    }

    @Test fun `unchanged identities reuse metadata regardless of timestamp and alias`() = runTest {
        val source = FakeDocuments().apply {
            entries["root"] = listOf(audio("old", "renamed.mp3").copy(identity = "stable", modifiedAt = null),
                audio("alias", "alias.mp3").copy(identity = "stable", modifiedAt = 1L))
        }
        val cached = Track("cached", "Saved title", artist = "Saved artist", fileName = "old.mp3",
            relativePath = "old.mp3", id = "stable", addedAt = 42L)
        val result = TreeScanner(source).scan("tree", knownTracks = mapOf("stable" to cached))
        assertEquals(cached.copy(uri = "old", relativePath = "renamed.mp3"), result.tracks.single())
        assertEquals(0, source.metadataReads)
        assertEquals(ScanMetrics(1, 0, result.metrics.elapsedNanos), result.metrics)
        assertTrue(result.metrics.elapsedNanos >= 0)
    }

    @Test fun `old and null timestamps do not suppress new files or bypass exclusions`() = runTest {
        val source = FakeDocuments().apply {
            entries["root"] = listOf(audio("old-copy", "old.mp3").copy(modifiedAt = 1),
                audio("null-copy", "null.mp3"), directory("excluded"), directory("nested"))
            entries["excluded"] = listOf(audio("excluded-song", "skip.mp3"))
            entries["nested"] = listOf(audio("nested-song", "nested.mp3"))
        }
        val result = TreeScanner(source).scan("tree", ScanOptions(excludedPaths = setOf("excluded")))
        assertEquals(setOf("old-copy", "null-copy", "nested-song"), result.tracks.map { it.id }.toSet())
        assertEquals(3, source.metadataReads)
        assertEquals(2, result.metrics.directoryQueries)
        assertEquals(3, result.metrics.metadataReads)
    }

    @Test fun `excluded short aliases are opened only once per discovery`() = runTest {
        val source = FakeDocuments().apply {
            entries["root"] = listOf(audio("one", "one.mp3"), audio("alias", "alias.mp3").copy(identity = "one"))
            tags["one"] = AudioMetadata(durationMs = 100)
        }
        assertTrue(TreeScanner(source).scan("tree", ScanOptions(minimumDurationSeconds = 1)).tracks.isEmpty())
        assertEquals(1, source.metadataReads)
    }

    @Test fun playlistAudioMimeTypesAreNotIndexedAsSongs() {
        assertFalse(AudioFiles.accepts("mix.m3u8", "audio/x-mpegurl"))
        assertFalse(AudioFiles.accepts("mix.pls", "audio/x-scpls"))
        assertFalse(AudioFiles.accepts("mix.m3u", "application/octet-stream"))
    }
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
        assertEquals(setOf("broken"), result.failedMetadataIds)
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

    @Test fun `retains grouping metadata and deduplicates document identity across aliases`() = runTest {
        val source = FakeDocuments()
        source.entries["root"] = listOf(audio("one", "one.mp3").copy(identity = "document:song"),
            audio("alias", "alias.mp3").copy(identity = "document:song"))
        source.tags["one"] = AudioMetadata(albumArtist = " Various Artists ", trackNumber = 4, discNumber = 2,
            year = 2024, genre = " Jazz ")
        val track = TreeScanner(source).scan("tree").tracks.single()
        assertEquals("document:song", track.id)
        assertEquals("Various Artists", track.albumArtist)
        assertEquals(4, track.trackNumber)
        assertEquals(2, track.discNumber)
        assertEquals(2024, track.year)
        assertEquals("Jazz", track.genre)
        assertEquals(1, source.metadataReads)
    }

    @Test fun `recognizes common formats without claiming videos`() {
        listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus").forEach {
            assertTrue(AudioFiles.accepts("song.$it", "application/octet-stream"))
        }
        assertTrue(AudioFiles.accepts("no extension", "audio/mpeg"))
        assertFalse(AudioFiles.accepts("video.m4a", "video/mp4"))
        assertFalse(AudioFiles.accepts("song.mp3.txt", "text/plain"))
    }
    @Test fun `scan rules exclude folders and short audio but retain unknown durations`() = runTest {
        val source = FakeDocuments()
        source.entries["root"] = listOf(directory("excluded"), directory("included"), audio("short", "short.mp3"), audio("unknown", "unknown.mp3"))
        source.entries["excluded"] = listOf(audio("skip", "skip.mp3"))
        source.entries["included"] = listOf(audio("long", "long.mp3"))
        source.tags["short"] = AudioMetadata(durationMs = 500)
        source.tags["long"] = AudioMetadata(durationMs = 30000)
        val scanned = TreeScanner(source).scan("tree", ScanOptions(minimumDurationSeconds = 10, excludedPaths = setOf("excluded")))
        assertEquals(setOf("long", "unknown"), scanned.tracks.map { it.id }.toSet())
        assertFalse("excluded" in source.queried)
        source.queried.clear()
        assertEquals(setOf("short", "unknown"), TreeScanner(source).scan("tree", ScanOptions(includeSubfolders = false)).tracks.map { it.id }.toSet())
        assertEquals(listOf("root"), source.queried)
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
    override suspend fun metadata(uri: String): AudioMetadata {
        metadataReads++
        if (uri == failureUri) metadataFailure?.let { throw it }
        return tags[uri] ?: AudioMetadata()
    }
}

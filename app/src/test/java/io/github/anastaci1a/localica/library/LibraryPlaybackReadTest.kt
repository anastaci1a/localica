package io.github.anastaci1a.localica.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryPlaybackReadTest {
    private fun song(id: String) = Track("content://fixture/$id", "Title $id", artist = "Artist", album = "Album",
        durationMs = 12345L, fileName = "$id.flac", relativePath = "nested/$id.flac", id = id,
        albumArtist = "Album artist", trackNumber = 7, discNumber = 2, year = 2026, genre = "Genre",
        comment = "Comment", composer = "Composer", lyricist = "Lyricist", modifiedAt = 1234567L)

    @Test fun defaultStoreRetainsSnapshotsFirstFullListAndOriginalIdentity() = runBlocking {
        val first = LibrarySnapshot(tracks = listOf(song("one"), song("two")))
        val stream = MutableStateFlow(first)
        val store = object : LibraryStore {
            override val snapshots: Flow<LibrarySnapshot> = stream
            override suspend fun current(): LibrarySnapshot = error("Default contract must collect snapshots.first")
            override suspend fun replaceRoot(result: ScanResult): Unit = error("No writes")
            override suspend fun removeRoot(uri: String): Unit = error("No writes")
            override suspend fun updateMetadata(track: Track): Unit = error("No writes")
        }
        val original = store.playbackSnapshot(setOf("absent"))
        assertSame(first, original.fullSnapshot)
        assertSame(first.tracks, original.tracks)
        assertEquals(2, original.totalTracks)
        val next = LibrarySnapshot(tracks = listOf(song("three")))
        stream.value = next
        val latest = store.playbackSnapshot(emptySet())
        assertSame(next, latest.fullSnapshot)
        assertSame(next.tracks, latest.tracks)
        assertEquals(1, latest.totalTracks)
    }
}

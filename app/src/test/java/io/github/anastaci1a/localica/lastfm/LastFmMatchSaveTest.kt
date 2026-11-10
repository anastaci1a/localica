package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmMatchSaveTest {
    @Test fun failedDurableChoicePreservesArchiveAndRetryPublishesExactlyOneChoice() = runBlocking {
        val original = SavedLastFm("user", snapshot = LastFmSnapshot("user", 1, listOf(LastFmTrack("Artist", "Song", 7)), localBaseline = emptyMap()))
        var saved = original; var fail = true; var writes = 0
        val store = object : LastFmStore {
            override suspend fun read() = saved
            override suspend fun write(value: SavedLastFm) { writes++; if (fail) error("fixture storage full"); saved = value }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val consent = object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> error("No network") }), flowOf(LibrarySnapshot()), true, consent, scope)
        try {
            withTimeout(5000) { controller.state.first { !it.loading } }
            assertFalse(withTimeout(5000) { controller.mapAndAwait("remote", "local") })
            assertEquals(original, saved); assertTrue(controller.state.value.saved.mappings.isEmpty())
            assertNotNull(controller.state.value.problem)
            fail = false
            assertTrue(withTimeout(5000) { controller.mapAndAwait("remote", "local") })
            assertEquals(mapOf("remote" to "local"), saved.mappings)
            assertEquals(original.snapshot, saved.snapshot); assertEquals(2, writes)
            assertNull(controller.state.value.problem)
        } finally { scope.cancel() }
    }

    @Test fun explicitAbsentChoiceCanBeUndoneWithoutChangingTheArchive() = runBlocking {
        val track = io.github.anastaci1a.localica.library.Track("local", "Song", "Artist", "Album", 1000, "song.flac", "song.flac")
        val remote = LastFmTrack("Artist", "Song", 7)
        val archive = LastFmSnapshot("user", 1, listOf(remote), localBaseline = emptyMap())
        var saved = SavedLastFm("user", snapshot = archive)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val consent = object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }
        val controller = LastFmController(object : LastFmStore {
            override suspend fun read() = saved
            override suspend fun write(value: SavedLastFm) { saved = value }
        }, LastFmSync(LastFmApi { _, _ -> error("No network") }), flowOf(LibrarySnapshot(tracks = listOf(track))), true, consent, scope)
        try {
            withTimeout(5000) { controller.details.first { it.clearCount == 1 } }
            assertTrue(controller.mapAndAwait(remote.key, LastFmMatcher.NONE))
            val absent = withTimeout(5000) { controller.details.first { it.complete && it.notInLibrary == 1 } }
            assertTrue(absent.rows(LastFmGroup.ABSENT, saved.mappings).single().canUndo)
            assertEquals("Marked not in your library by you", absent.rows(LastFmGroup.ABSENT, saved.mappings).single().reason)
            controller.resetMapping(remote.key)
            withTimeout(5000) { controller.details.first { it.clearCount == 1 } }
            assertFalse(saved.mappings.containsKey(remote.key)); assertEquals(archive, saved.snapshot)
        } finally { scope.cancel() }
    }
}

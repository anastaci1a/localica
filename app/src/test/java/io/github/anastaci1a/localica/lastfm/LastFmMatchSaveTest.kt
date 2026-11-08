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
}

package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.diagnostics.WorkCounters
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** #779: a launch with an unchanged library, archive and mappings never matches the whole archive again. */
class LastFmMatchCacheTest {
    private class Consent : NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private class Store(initial: SavedLastFm) : LastFmStore {
        var bytes = LastFmSnapshotCodec.encode(initial)
        override suspend fun read() = LastFmSnapshotCodec.decode(bytes)
        override suspend fun write(value: SavedLastFm) { bytes = LastFmSnapshotCodec.encode(value) }
    }
    private class MemoryCache(var value: LastFmMatchCache? = null) : LastFmMatchCacheStore {
        var writes = 0
        override suspend fun read() = value
        override suspend fun write(value: LastFmMatchCache?) { this.value = value; writes++ }
    }
    private val song = Track("song", "Song", "Artist", "Album", 200_000, "song.flac", "song.flac")
    private val other = song.copy(uri = "other", id = "other", title = "Other")
    private val remote = LastFmTrack("Artist", "Song", 42, durationMs = 200_000)
    private val archive = LastFmSnapshot("user", 1000, listOf(remote), emptyMap(), 1)
    private val saved = SavedLastFm("user", true, archive, emptyMap())
    private fun controller(library: Flow<LibrarySnapshot>, cache: LastFmMatchCacheStore, scope: CoroutineScope) =
        LastFmController(Store(saved), LastFmSync(LastFmApi { _, _ -> error("No request expected") }, {}), library, true, Consent(), scope,
            { 100_000 }, localStatistics = flowOf(ListeningStatistics()), matchCache = cache)

    @Test fun codecRoundTripsEveryField() {
        val cache = LastFmMatchCache("lib", "user|1000|1|42", "-", mapOf("song" to 42L), mapOf("song" to "song"), 1, 2, 3)
        assertEquals(cache, LastFmMatchCacheCodec.decode(LastFmMatchCacheCodec.encode(cache)))
        assertNull(LastFmMatchCacheCodec.decode("{\"version\":0}"))
    }

    @Test fun keysChangeOnlyWithMatchingInputs() {
        val library = LibrarySnapshot(tracks = listOf(song, other))
        val key = LastFmMatchKey.of(library, archive, emptyMap())
        assertEquals(key, LastFmMatchKey.of(library.copy(tracks = listOf(song.copy(playCount = 9), other.copy(addedAt = 5))), archive, emptyMap()))
        assertNotEquals(key, LastFmMatchKey.of(library.copy(tracks = listOf(song.copy(title = "Renamed"), other)), archive, emptyMap()))
        assertNotEquals(key, LastFmMatchKey.of(library, archive.copy(syncedAt = 2000), emptyMap()))
        assertNotEquals(key, LastFmMatchKey.of(library, archive, mapOf(remote.key to "other")))
    }

    @Test fun firstMatchIsPersistedAndTheNextLaunchUsesItWithoutMatching() = runBlocking {
        val library = LibrarySnapshot(tracks = listOf(song, other))
        val cache = MemoryCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            WorkCounters.reset()
            val first = controller(flowOf(library), cache, scope)
            withTimeout(3000) { first.overlay.first { it.counts["song"] == 42L } }
            assertEquals(1L, WorkCounters.lastFmMatches.get()); assertEquals(1, cache.writes)
            assertEquals(mapOf("song" to 42L), requireNotNull(cache.value).counts)
            WorkCounters.reset()
            val second = controller(flowOf(library), cache, scope)
            withTimeout(3000) { second.overlay.first { it.counts["song"] == 42L } }
            assertEquals("A cached result must not match again", 0L, WorkCounters.lastFmMatches.get())
            assertFalse(second.matches.value.complete)
            assertEquals(mapOf("song" to 42L), withTimeout(3000) { second.applyTo(flowOf(ListeningStatistics())).first { it.tracks.isNotEmpty() } }.tracks)
            // Something displaying review lists completes the cached result in place.
            val complete = withTimeout(3000) { second.matches.first { it.complete } }
            assertEquals(1, complete.matched); assertEquals(1L, WorkCounters.lastFmMatches.get())
        } finally { scope.cancel() }
    }

    @Test fun aChangedLibraryMatchesAgainAndRefreshesTheCache() = runBlocking {
        val cache = MemoryCache()
        val library = MutableStateFlow(LibrarySnapshot(tracks = listOf(song)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            WorkCounters.reset()
            val controller = controller(library, cache, scope)
            withTimeout(3000) { controller.overlay.first { it.counts["song"] == 42L } }
            val stale = requireNotNull(cache.value)
            library.value = LibrarySnapshot(tracks = listOf(song.copy(title = "Renamed"), other))
            withTimeout(3000) { controller.overlay.first { it.counts["song"] == null } }
            assertEquals(2L, WorkCounters.lastFmMatches.get())
            assertNotEquals(stale.libraryKey, requireNotNull(cache.value).libraryKey)
        } finally { scope.cancel() }
    }
}

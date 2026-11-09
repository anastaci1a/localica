package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmSummaryCacheTest {
    private val cached = LastFmSettingsSummary("user", 10, LastFmAccountStats(15000, 75000, 15000, 3908, 0, 0),
        LastFmDashboardCounts(6456, 6400, 56, 2828, 5716, 2))
    private val consent = object : NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    @Test fun persistedSummaryRoundTripsAlbumAvailabilityAndUnknownCacheVersionsFailClosed() {
        assertEquals(cached, LastFmSummaryCodec.decode(LastFmSummaryCodec.encode(cached)))
        val knownAlbums = cached.copy(stats = cached.stats.copy(albums = 123, entriesWithAlbums = 400))
        assertEquals(knownAlbums, LastFmSummaryCodec.decode(LastFmSummaryCodec.encode(knownAlbums)))
        assertTrue(runCatching { LastFmSummaryCodec.decode(LastFmSummaryCodec.encode(cached).replace("\"version\":1", "\"version\":2")) }.isFailure)
    }
    @Test fun cachedOverviewPublishesBeforeArchiveReadAndRefreshNeverChangesArchiveOrChoices() = runBlocking<Unit> {
        val archiveRead = CompletableDeferred<Unit>()
        val archive = SavedLastFm("user", snapshot = LastFmSnapshot("user", 20,
            listOf(LastFmTrack("Artist", "Song", 7, "Album")), localBaseline = emptyMap()), mappings = mapOf("kept" to "choice"))
        var writes = 0
        val persisted = MutableStateFlow<LastFmSettingsSummary?>(cached)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = object : LastFmStore {
            override suspend fun readSummary() = cached
            override suspend fun writeSummary(value: LastFmSettingsSummary?) { persisted.value = value }
            override suspend fun read(): SavedLastFm { archiveRead.await(); return archive }
            override suspend fun write(value: SavedLastFm) { writes++ }
        }
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> error("Offline fixture") }),
            flowOf(LibrarySnapshot()), true, consent, scope)
        try {
            assertEquals(cached, withTimeout(5000) { controller.summary.filterNotNull().first() })
            assertTrue(controller.state.value.loading)
            archiveRead.complete(Unit)
            val refreshed = withTimeout(5000) { persisted.filterNotNull().first { it.syncedAt == 20L && it.counts != null } }
            assertEquals(7L, refreshed.stats.scrobbles); assertEquals(1, refreshed.stats.albums)
            assertEquals(archive, controller.state.value.saved); assertEquals(0, writes)
            controller.deleteSyncedData()
            withTimeout(5000) { controller.state.first { !it.loading && it.saved.snapshot == null } }
            withTimeout(5000) { persisted.first { it == null } }
        } finally { scope.cancel() }
    }
}

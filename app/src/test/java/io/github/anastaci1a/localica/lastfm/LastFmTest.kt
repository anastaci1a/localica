package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmTest {
    private fun consent(initial: Boolean = true) = object : io.github.anastaci1a.localica.network.NetworkConsent {
        override val allowed = MutableStateFlow(initial)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }

    private val mbid = "11111111-1111-1111-1111-111111111111"
    private fun track(id: String, album: String? = "Studio", title: String = "Learning to Live", artist: String = "Dream Theater") =
        Track(id, title, artist, album, 100_000, "$id.mp3", "$id.mp3")
    private fun fixture(name: String) = javaClass.getResourceAsStream("/lastfm/$name")!!.bufferedReader().use { it.readText() }
    private class MemoryStore(var saved: SavedLastFm = SavedLastFm()) : LastFmStore {
        override suspend fun read() = saved
        override suspend fun write(value: SavedLastFm) { saved = value }
    }
    private suspend fun LastFmController.ready() = withTimeout(2000) { state.first { !it.loading } }
    private suspend fun LastFmController.finished() = withTimeout(2000) { state.first { !it.loading && !it.syncing && (it.saved.snapshot != null || it.problem != null) } }

    @Test fun responseFixturesParseStringsNumbersSingletonsAndOptionalMetadata() {
        val first = LastFmResponses.parse(fixture("toptracks-page1.json")); val second = LastFmResponses.parse(fixture("toptracks-page2.json"))
        assertEquals(1, first.number); assertEquals(2, first.totalPages); assertEquals(42L, first.tracks.first().plays)
        assertEquals(mbid, first.tracks.first().mbid); assertNull(first.tracks.last().mbid)
        assertEquals(12L, second.tracks.single().plays); assertEquals("Live", second.tracks.single().album)
        val empty = LastFmResponses.parse("""{"toptracks":{"track":[],"@attr":{"page":"1","totalPages":"0"}}}""")
        assertTrue(empty.tracks.isEmpty())
        for (invalid in listOf("{", "{}", """{"error":6,"message":"SECRET SHOULD NOT LEAK"}""")) {
            try { LastFmResponses.parse(invalid); fail("Malformed/API error must fail") }
            catch (failure: LastFmFailure) { assertFalse(failure.message!!.contains("SECRET")) }
        }
    }

    @Test fun paginationPacesEveryRequestAndRetriesTransientErrorsWithoutPartialPublication() = runBlocking {
        val requests = mutableListOf<Int>(); val delays = mutableListOf<Long>(); var rateLimited = false
        val sync = LastFmSync(LastFmApi { _, page ->
            requests += page
            if (page == 2 && !rateLimited) { rateLimited = true; LastFmResponses.parse(fixture("rate-limit.json")) }
            LastFmResponses.parse(fixture("toptracks-page$page.json"))
        }, { delays += it })
        assertEquals(3, sync.fetch("fixture-user").size)
        assertEquals(listOf(1, 2, 2), requests); assertEquals(listOf(500L, 2000L), delays)
        try {
            LastFmSync(LastFmApi { _, _ -> LastFmPage(2, 2, emptyList()) }, {}).fetch("user"); fail("Wrong page")
        } catch (_: LastFmFailure) { }
    }

    @Test fun matchingNormalizesNamesUsesAlbumAndRecordingIdsAndExposesAmbiguity() {
        val studio = track("studio"); val live = track("live", "Live")
        val strong = track("strong", title = "Renamed recording").copy(musicBrainzId = mbid)
        val snapshot = LastFmSnapshot("user", 10, listOf(
            LastFmTrack("  DREAM   THEATER ", "Learning to Live", 5),
            LastFmTrack("Dream Theater", "Learning to Live", 12, "Live"),
            LastFmTrack("Dream Theater", "Old title", 42, mbid = mbid),
            LastFmTrack("Missing", "Absent", 3)))
        val matched = LastFmMatcher.match(snapshot, listOf(studio, live, strong))
        assertEquals(mapOf("live" to 12L, "strong" to 42L), matched.counts)
        assertEquals(1, matched.review.size); assertEquals(1, matched.notInLibrary); assertEquals(2, matched.review.first().candidates.size)
        val chosen = LastFmMatcher.match(snapshot, listOf(studio, live, strong), mapOf(snapshot.tracks.first().key to "studio"))
        assertEquals(5L, chosen.counts["studio"])
        assertTrue(LastFmMatcher.match(LastFmSnapshot("u", 1, listOf(LastFmTrack("Dream Theater", "Learning to Live", 5, mbid = "other"))),
            listOf(studio.copy(musicBrainzId = mbid))).counts.isEmpty())
        assertEquals("é", normalize("e\u0301"))
        assertEquals("ac/dc", normalize("AC/DC"))
    }

    @Test fun distinctRemoteAliasesAccumulateWhileDuplicatePaginationRowsKeepMaximum() = runBlocking {
        val remote = LastFmTrack("Dream Theater", "Learning to Live", 5, mbid = mbid)
        val sync = LastFmSync(LastFmApi { _, page -> LastFmPage(page, 2, listOf(remote.copy(plays = if (page == 1) 5 else 7))) }, {})
        assertEquals(7L, sync.fetch("user").single().plays)
        val result = LastFmMatcher.match(LastFmSnapshot("u", 1, listOf(remote, remote.copy(title = "Old title", plays = 2))),
            listOf(track("song").copy(musicBrainzId = mbid)))
        assertEquals(7L, result.counts["song"])
    }

    @Test fun persistedSnapshotRoundTripsAndRetainsAnArchiveAfterDisconnect() {
        val remote = LastFmTrack("Dream Theater", "Learning to Live", 42, mbid = mbid)
        val saved = SavedLastFm("user", true, LastFmSnapshot("user", 20, listOf(remote)), mapOf(remote.key to "song"), 10)
        assertEquals(saved, LastFmSnapshotCodec.decode(LastFmSnapshotCodec.encode(saved)))
        assertEquals(SavedLastFm(), LastFmSnapshotCodec.decode(LastFmSnapshotCodec.encode(SavedLastFm())))
        val disconnected = saved.copy(username = "", active = false)
        assertEquals(disconnected, LastFmSnapshotCodec.decode(LastFmSnapshotCodec.encode(disconnected)))
        assertEquals("user", disconnected.snapshot!!.username)
    }

    @Test fun syncSettingAndDisconnectChangeEffectiveCountsWithoutWritingLocalState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = MemoryStore(); var clock = 100_000L; var requests = 0
        val source = MutableStateFlow(LibrarySnapshot(tracks = listOf(track("song"), track("local", title = "Local only"))))
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> requests++; LastFmPage(1, 1,
            listOf(LastFmTrack("Dream Theater", "Learning to Live", 42))) }, {}), source, true, consent(), scope, { clock })
        val local = ListeningStatistics(mapOf("song" to 7L, "local" to 3L), mapOf("playlist" to 2L))
        try {
            controller.ready(); controller.sync("user"); controller.finished()
            withTimeout(2000) { controller.matches.first { it.counts["song"] == 42L } }
            assertEquals(local, controller.applyTo(flowOf(local)).first())
            controller.useCounts(true)
            withTimeout(2000) { controller.state.first { it.saved.useCounts } }
            assertEquals(mapOf("song" to 42L, "local" to 3L), controller.applyTo(flowOf(local)).first().tracks)
            assertEquals(mapOf("playlist" to 2L), controller.applyTo(flowOf(local)).first().playlists)
            assertEquals(mapOf("song" to 7L, "local" to 3L), local.tracks)
            assertTrue(controller.counts(ListeningWindow(1, clock)).first().isEmpty())
            controller.sync("user"); withTimeout(2000) { controller.state.first { it.problem?.contains("minute") == true } }
            assertEquals(1, requests)
            controller.useCounts(false); withTimeout(2000) { controller.state.first { !it.saved.useCounts } }
            assertEquals(local, controller.applyTo(flowOf(local)).first())
            controller.disconnect(); withTimeout(2000) { controller.state.first { it.saved.username.isEmpty() && !it.saved.active } }
            assertNotNull(store.saved.snapshot)
            controller.deleteSyncedData(); withTimeout(2000) { controller.state.first { it.saved.snapshot == null } }
            assertTrue(store.saved.mappings.isEmpty()); assertFalse(store.saved.useCounts)
        } finally { scope.cancel() }
    }

    @Test fun offlineAndPartialPageFailuresKeepLastCompleteSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = SavedLastFm("user", true, LastFmSnapshot("user", 1, listOf(LastFmTrack("A", "Old", 7))))
        val store = MemoryStore(old)
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, page ->
            if (page == 1) LastFmPage(1, 2, listOf(LastFmTrack("A", "New", 99))) else throw LastFmFailure("Offline")
        }, {}), flowOf(LibrarySnapshot()), true, consent(), scope, { 100_000 })
        try {
            controller.ready(); controller.sync("user")
            withTimeout(2000) { controller.state.first { it.problem == "Offline" } }
            assertEquals(old.snapshot!!.tracks, store.saved.snapshot!!.tracks); assertEquals(old.snapshot!!.syncedAt, store.saved.snapshot!!.syncedAt); assertTrue(store.saved.useCounts)
        } finally { scope.cancel() }
    }

    @Test fun disconnectCancelsInFlightSyncAndDisabledBuildNeverRequestsTheApi() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var requests = 0; val pending = CompletableDeferred<LastFmPage>()
        val api = LastFmApi { _, _ -> requests++; pending.await() }
        val store = MemoryStore()
        val controller = LastFmController(store, LastFmSync(api, {}), flowOf(LibrarySnapshot()), true, consent(), scope, { 100_000 })
        val disabled = LastFmController(MemoryStore(), LastFmSync(api, {}), flowOf(LibrarySnapshot()), false, consent(), scope)
        try {
            controller.ready(); controller.sync("user"); withTimeout(2000) { controller.state.first { it.syncing } }
            controller.disconnect(); withTimeout(2000) { controller.state.first { it.saved.username.isEmpty() && !it.syncing } }
            pending.complete(LastFmPage(1, 1, listOf(LastFmTrack("A", "Song", 9))))
            yield(); assertNull(store.saved.snapshot)
            disabled.ready(); disabled.sync("user")
            withTimeout(2000) { disabled.state.first { it.problem?.contains("disabled") == true } }
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }
    @Test fun explicitNullOptionalIdsDoNotPreventNameMatchingWithTaggedLocalSongs() {
        val page = LastFmResponses.parse("""{"toptracks":{"track":[{"artist":{"name":"Dream Theater"},"name":"Learning to Live","playcount":"5","mbid":null,"album":{"#text":null}}],"@attr":{"page":"1","totalPages":"1"}}}""")
        assertNull(page.tracks.single().mbid); assertNull(page.tracks.single().album)
        val matches = LastFmMatcher.match(LastFmSnapshot("user", 1, page.tracks), listOf(track("song").copy(musicBrainzId = mbid)))
        assertEquals(mapOf("song" to 5L), matches.counts); assertTrue(matches.review.isEmpty())
    }

}

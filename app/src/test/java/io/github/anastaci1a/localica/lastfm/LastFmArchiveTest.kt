package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmArchiveTest {
    private class Consent : NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private class Store(initial: SavedLastFm) : LastFmStore {
        var bytes = LastFmSnapshotCodec.encode(initial)
        override suspend fun read() = LastFmSnapshotCodec.decode(bytes)
        override suspend fun write(value: SavedLastFm) { bytes = LastFmSnapshotCodec.encode(value) }
    }
    private val song = Track("song", "Song", "Artist", "Album", 200_000, "song.flac", "song.flac")
    private val deluxe = song.copy(uri = "deluxe", id = "deluxe", album = "Album (Deluxe)")
    private val remote = LastFmTrack("Artist", "Song", 42, durationMs = 200_000)
    private fun local() = MutableStateFlow(ListeningStatistics(mapOf("song" to 7L, "deluxe" to 3L, "local-only" to 2L), mapOf("playlist" to 9L)))
    private fun saved(local: ListeningStatistics, rows: List<LastFmTrack> = listOf(remote)) = SavedLastFm("user", true,
        LastFmSnapshot("user", 1000, rows, local.tracks, 1), mapOf("other-saved-choice" to "song"))
    private fun controller(store: Store, raw: Flow<ListeningStatistics>, scope: CoroutineScope, consent: Consent = Consent(),
        api: LastFmApi = LastFmApi { _, _ -> error("No request expected") }, now: () -> Long = { 100_000 }) =
        LastFmController(store, LastFmSync(api, {}), flowOf(LibrarySnapshot(tracks = listOf(song, deluxe))), true, consent, scope, now,
            localStatistics = raw)
    private suspend fun LastFmController.ready() = withTimeout(3000) { state.first { !it.loading } }
    private suspend fun count(controller: LastFmController, raw: Flow<ListeningStatistics>, expected: Long): ListeningStatistics =
        withTimeout(3000) { controller.applyTo(raw).first { it.tracks["song"] == expected } }

    @Test fun featureAndConsentOffOnKeepTheArchiveChoicesAndJudgmentsWithoutResync() = runBlocking {
        val raw = local(); val uploader = remote.copy(artist = "Channel", title = "Artist - Song")
        val original = saved(raw.value, listOf(uploader)); val store = Store(original); val consent = Consent()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = controller(store, raw, scope, consent)
        try {
            controller.ready(); count(controller, raw, 42)
            withTimeout(3000) { controller.matches.first { it.judgments.size == 1 } }
            controller.setActive(false); withTimeout(3000) { controller.state.first { !it.saved.active } }
            assertEquals(7L, count(controller, raw, 7).tracks["song"])
            assertEquals(original.snapshot, store.read().snapshot); assertEquals(original.mappings, store.read().mappings)
            assertEquals(1, controller.matches.value.judgments.size)
            controller.setActive(true); count(controller, raw, 42)
            consent.setAllowed(false); count(controller, raw, 7)
            assertEquals(original.snapshot, store.read().snapshot)
            consent.setAllowed(true); count(controller, raw, 42)
            assertEquals(original.mappings, store.read().mappings); assertEquals(1, controller.matches.value.judgments.size)
        } finally { scope.cancel() }
    }

    @Test fun disconnectForgetsTheConnectionButOnlyExplicitDeleteRemovesTheArchive() = runBlocking {
        val raw = local(); val original = saved(raw.value); val store = Store(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val controller = controller(store, raw, scope)
        try {
            controller.ready(); count(controller, raw, 42)
            controller.disconnect(); withTimeout(3000) { controller.state.first { it.saved.username.isEmpty() && !it.saved.active } }
            count(controller, raw, 7)
            assertEquals(original.snapshot, store.read().snapshot); assertEquals(original.mappings, store.read().mappings)
            assertTrue(store.read().useCounts)
            controller.setActive(true); count(controller, raw, 42)
            assertEquals("user", store.read().username)
            controller.deleteSyncedData(); withTimeout(3000) { controller.state.first { it.saved.snapshot == null } }
            withTimeout(3000) { controller.matches.first { it.counts.isEmpty() && it.judgments.isEmpty() } }
            assertTrue(store.read().mappings.isEmpty()); assertFalse(store.read().useCounts)
            assertEquals(7L, raw.value.tracks["song"]); assertEquals(9L, raw.value.playlists["playlist"])
        } finally { scope.cancel() }
    }

    @Test fun playsAfterSyncAddOncePerVersionAndRefreshRebasesOnlyTheTemporaryOverlay() = runBlocking {
        val raw = local(); val store = Store(saved(raw.value)); var clock = 100_000L; var authoritative = 43L; var requests = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = controller(store, raw, scope, api = LastFmApi { _, _ -> requests++; LastFmPage(1, 1, listOf(remote.copy(plays = authoritative))) }, now = { clock })
        try {
            controller.ready(); count(controller, raw, 42)
            raw.value = raw.value.copy(tracks = raw.value.tracks + ("song" to 8L))
            val plusOne = count(controller, raw, 43)
            assertEquals(43L, plusOne.tracks["deluxe"]); assertEquals(2L, plusOne.tracks["local-only"])
            withTimeout(3000) { controller.overlay.first { it.additions == 1L } }
            assertEquals(42L, controller.matches.value.counts["song"])
            raw.value = raw.value.copy(tracks = raw.value.tracks + ("local-only" to 3L))
            count(controller, raw, 43)
            withTimeout(3000) { controller.overlay.first { it.additions == 2L } }
            controller.sync("user", fullRescan = true)
            withTimeout(3000) { controller.state.first { it.saved.snapshot?.syncedAt == clock && !it.syncing } }
            count(controller, raw, 43)
            withTimeout(3000) { controller.overlay.first { it.sourceSnapshot?.syncedAt == clock && it.additions == 0L } }
            assertEquals(8L, store.read().snapshot!!.localBaseline!!["song"]); assertEquals(8L, raw.value.tracks["song"])
            raw.value = raw.value.copy(tracks = raw.value.tracks + ("song" to 9L))
            count(controller, raw, 44)
            authoritative = 50; clock += 60_000; controller.sync("user", fullRescan = true)
            withTimeout(3000) { controller.state.first { it.saved.snapshot?.syncedAt == clock && !it.syncing } }
            count(controller, raw, 50)
            assertEquals(9L, raw.value.tracks["song"]); assertEquals(9L, raw.value.playlists["playlist"]); assertEquals(2, requests)
            // A real authoritative zero is still a base; it must not resurrect old local totals.
            authoritative = 0; clock += 60_000; controller.sync("user", fullRescan = true)
            withTimeout(3000) { controller.state.first { it.saved.snapshot?.syncedAt == clock && !it.syncing } }
            count(controller, raw, 0); assertEquals(9L, raw.value.tracks["song"])
        } finally { scope.cancel() }
    }

    @Test fun baselineAndTemporaryPlaysSurviveControllerRecreationAndLegacyArchivesAdoptASafeBoundary() = runBlocking {
        val raw = local(); val store = Store(saved(raw.value).copy(snapshot = saved(raw.value).snapshot!!.copy(localBaseline = null)))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = controller(store, raw, firstScope)
        first.ready(); count(first, raw, 42)
        assertEquals(raw.value.tracks, store.read().snapshot!!.localBaseline)
        raw.value = raw.value.copy(tracks = raw.value.tracks + ("song" to 8L)); count(first, raw, 43)
        firstScope.cancel()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val reopened = controller(Store(store.read()), raw, secondScope)
            reopened.ready(); count(reopened, raw, 43)
            assertEquals(8L, raw.value.tracks["song"])
        } finally { secondScope.cancel() }
    }

    @Test fun aDifferentConnectionCannotUseAnArchivedAccountsCounts() = runBlocking {
        val raw = local(); val original = saved(raw.value); val store = Store(original.copy(username = "other"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = controller(store, raw, scope)
            controller.ready(); withTimeout(3000) { controller.matches.first { it.matched == 1 } }
            assertFalse(controller.settings.first().useExternalCounts); count(controller, raw, 7)
            assertEquals(original.snapshot, store.read().snapshot)
        } finally { scope.cancel() }
    }

    @Test fun failedRefreshKeepsTheArchiveAndItsTemporaryPlaysThenSuccessfulRefreshRebases() = runBlocking {
        val raw = local(); val original = saved(raw.value); val store = Store(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = controller(store, raw, scope, api = LastFmApi { _, number ->
            if (number == 1) LastFmPage(1, 2, listOf(remote.copy(plays = 90)))
            else throw LastFmFailure("Fixture failure")
        })
        try {
            controller.ready(); count(controller, raw, 42)
            raw.value = raw.value.copy(tracks = raw.value.tracks + ("song" to 8L))
            count(controller, raw, 43)
            controller.sync("user", fullRescan = true)
            withTimeout(3000) { controller.state.first { !it.syncing && it.problem != null } }
            assertEquals(original.snapshot, store.read().snapshot)
            count(controller, raw, 43)
            withTimeout(3000) { controller.overlay.first { it.additions == 1L } }
            assertEquals(8L, raw.value.tracks["song"])
            assertEquals(9L, raw.value.playlists["playlist"])
        } finally { scope.cancel() }
    }

    @Test fun aFullRefreshCarriesManualChoicesAcrossMetadataOnlyKeyChanges() = runBlocking {
        val raw = local(); val before = remote.copy(album = "Old title")
        val original = saved(raw.value, listOf(before)).copy(mappings = mapOf(before.key to "song"))
        val after = before.copy(album = "New title", plays = 45)
        val store = Store(original); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = controller(store, raw, scope, api = LastFmApi { _, _ -> LastFmPage(1, 1, listOf(after)) })
        try {
            controller.ready(); count(controller, raw, 42); controller.sync("user", fullRescan = true)
            withTimeout(3000) { controller.state.first { !it.syncing && it.saved.snapshot?.tracks == listOf(after) } }
            assertEquals("song", store.read().mappings[after.key]); count(controller, raw, 45)
            assertEquals(7L, raw.value.tracks["song"])
        } finally { scope.cancel() }
    }

    @Test fun deleteAndFeatureOffCancelIncompleteSyncWithoutPublishingItsLatePages() = runBlocking {
        for (delete in listOf(false, true)) {
            val raw = local(); val original = saved(raw.value); val store = Store(original)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val pending = CompletableDeferred<LastFmPage>()
            val controller = controller(store, raw, scope, api = LastFmApi { _, page -> if (page == 1)
                LastFmPage(1, 2, listOf(remote.copy(plays = 99))) else pending.await() })
            try {
                controller.ready(); count(controller, raw, 42); controller.sync("user", fullRescan = true)
                withTimeout(3000) { controller.state.first { it.progress?.page == 1 } }
                if (delete) controller.deleteSyncedData() else controller.setActive(false)
                withTimeout(3000) { controller.state.first { !it.syncing && if (delete) it.saved.snapshot == null else !it.saved.active } }
                pending.complete(LastFmPage(2, 2, listOf(remote.copy(title = "Late", plays = 500))))
                yield()
                assertEquals(if (delete) null else original.snapshot, store.read().snapshot)
                assertEquals(7L, raw.value.tracks["song"])
            } finally { scope.cancel() }
        }
    }
}

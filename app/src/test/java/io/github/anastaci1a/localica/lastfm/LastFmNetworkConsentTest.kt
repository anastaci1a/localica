package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmNetworkConsentTest {
    private class Consent(initial: Boolean) : NetworkConsent {
        override val allowed = MutableStateFlow(initial)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private class Store(var saved: SavedLastFm) : LastFmStore {
        var writes = 0
        override suspend fun read() = saved
        override suspend fun write(value: SavedLastFm) { writes++; saved = value }
    }
    @Test fun consentOffMeansZeroApiCallsAndKeepsExistingSnapshotAndMappings() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = SavedLastFm("user", true, LastFmSnapshot("user", 1, listOf(LastFmTrack("A", "Old", 7))), mapOf("remote" to "local"))
        val store = Store(old); var calls = 0
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> calls++; error("Forbidden") }, {}),
            flowOf(LibrarySnapshot()), true, Consent(false), scope)
        try {
            withTimeout(2000) { controller.state.first { !it.loading } }
            controller.sync("user")
            withTimeout(2000) { controller.state.first { it.problem?.contains("Online features are off") == true } }
            assertEquals(0, calls); assertEquals(0, store.writes); assertEquals(old, store.saved)
            try { HttpLastFmApi("fixture-key", Consent(false), connectionFactory = { calls++; error("No live transport in tests") }).page("user", 1); fail("HTTP gate missing") }
            catch (failure: LastFmFailure) { assertTrue(failure.message!!.contains("Online features are off")) }
            assertEquals(0, calls)
        } finally { scope.cancel() }
    }
    @Test fun revokingConsentDuringPagePacingPreventsNextPageAndRetry() = runBlocking {
        for (retry in listOf(false, true)) {
            val consent = Consent(true); var calls = 0
            val sync = LastFmSync(LastFmApi { _, _ ->
                calls++
                if (retry) throw LastFmFailure("Rate limited", true)
                LastFmPage(1, 2, listOf(LastFmTrack("A", "Song", 1)))
            }, { consent.setAllowed(false) })
            try { sync.fetch("user") { consent.allowed.value }; fail("Revoked request allowed") }
            catch (_: LastFmFailure) { }
            assertEquals(1, calls)
        }
    }
    @Test fun revocationCancelsPendingSyncAndDoesNotPublishPartialResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val consent = Consent(true); val pending = CompletableDeferred<LastFmPage>()
        val old = SavedLastFm("user", false, LastFmSnapshot("user", 1, listOf(LastFmTrack("A", "Old", 7))))
        val store = Store(old); var calls = 0
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> calls++; pending.await() }, {}),
            flowOf(LibrarySnapshot()), true, consent, scope)
        try {
            withTimeout(2000) { controller.state.first { !it.loading } }
            controller.sync("user")
            withTimeout(2000) { controller.state.first { it.syncing } }
            consent.setAllowed(false)
            pending.complete(LastFmPage(1, 1, listOf(LastFmTrack("A", "New", 99))))
            withTimeout(2000) { controller.state.first { !it.syncing } }
            assertEquals(old.snapshot, store.saved.snapshot); assertEquals(1, calls)
        } finally { scope.cancel() }
    }
}

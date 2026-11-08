package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmSyncLifecycleTest {
    private class Consent : NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private class Store(initial: SavedLastFm) : LastFmStore {
        var bytes = LastFmSnapshotCodec.encode(initial)
        override suspend fun read() = LastFmSnapshotCodec.decode(bytes)
        override suspend fun write(value: SavedLastFm) { bytes = LastFmSnapshotCodec.encode(value) }
    }
    private val old = SavedLastFm("user", snapshot = LastFmSnapshot("user", 1, listOf(LastFmTrack("Old", "Song", 7))))
    private suspend fun LastFmController.ready() = withTimeout(2000) { state.first { !it.loading } }
    private fun controller(store: Store, scope: CoroutineScope, api: LastFmApi, consent: Consent = Consent(),
        clock: Long = 100_000, background: LastFmBackground? = null) =
        LastFmController(store, LastFmSync(api, {}), flowOf(LibrarySnapshot()), true, consent, scope, { clock }, background)

    @Test fun pageProgressIsLiveButCountsPublishOnlyAfterTheLastPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(old); val second = CompletableDeferred<LastFmPage>()
        val controller = controller(store, scope, LastFmApi { _, page -> if (page == 1)
            LastFmPage(1, 2, listOf(LastFmTrack("New", "First", 20))) else second.await() })
        try {
            controller.ready(); controller.sync("user")
            withTimeout(2000) { controller.state.first { it.progress?.page == 1 } }
            assertEquals(LastFmProgress(1, 2, 1), controller.state.value.progress)
            assertEquals(50, controller.state.value.progress!!.percent)
            assertEquals(old.snapshot, store.read().snapshot)
            assertEquals(old.snapshot, controller.state.value.saved.snapshot)
            second.complete(LastFmPage(2, 2, listOf(LastFmTrack("New", "Second", 30))))
            withTimeout(2000) { controller.state.first { !it.syncing && it.saved.snapshot!!.tracks.size == 2 } }
            assertNull(controller.state.value.progress); assertEquals(2, store.read().snapshot!!.tracks.size)
        } finally { scope.cancel() }
    }

    @Test fun failedOrRevokedSyncNeverPublishesItsProgressAsASnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(old); val consent = Consent(); val second = CompletableDeferred<LastFmPage>()
        val controller = controller(store, scope, LastFmApi { _, page -> if (page == 1)
            LastFmPage(1, 2, listOf(LastFmTrack("New", "First", 20))) else second.await() }, consent)
        try {
            controller.ready(); controller.sync("user")
            withTimeout(2000) { controller.state.first { it.progress?.page == 1 } }
            consent.setAllowed(false)
            withTimeout(2000) { controller.state.first { !it.syncing } }
            assertNull(controller.state.value.progress); assertEquals(old.snapshot, store.read().snapshot)
            second.complete(LastFmPage(2, 2, listOf(LastFmTrack("New", "Second", 30))))
            yield(); assertEquals(old.snapshot, store.read().snapshot)
        } finally { scope.cancel() }
    }

    @Test fun recreatedControllerResumesTheSameDurableWorkIdentityWithinThrottleButRejectsAnother() = runBlocking {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(old); val blocked = CompletableDeferred<LastFmPage>()
        val first = controller(store, firstScope, LastFmApi { _, page -> if (page == 1)
            LastFmPage(1, 2, listOf(LastFmTrack("New", "First", 20))) else blocked.await() })
        first.ready()
        val work = firstScope.launch { first.syncInBackground("user", "durable-work-id") }
        withTimeout(2000) { first.state.first { it.progress?.page == 1 } }
        assertEquals(old.snapshot, store.read().snapshot)
        assertEquals("durable-work-id", store.read().activeAttempt)
        work.cancelAndJoin(); firstScope.cancel()
        // Only codec bytes cross this boundary; no controller, page list or coroutine is reused.
        val reopened = Store(LastFmSnapshotCodec.decode(store.bytes))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var requests = 0
        val fresh = controller(reopened, scope, LastFmApi { _, page -> requests++
            LastFmPage(page, 1, listOf(LastFmTrack("New", "Complete", 90))) }, clock = 100_001)
        try {
            fresh.ready()
            assertFalse(fresh.syncInBackground("user", "another-work-id")); assertEquals(0, requests)
            assertEquals(old.snapshot, reopened.read().snapshot)
            assertTrue(fresh.syncInBackground("user", "durable-work-id")); assertEquals(1, requests)
            assertEquals("Complete", reopened.read().snapshot!!.tracks.single().title)
            assertNull(reopened.read().activeAttempt)
        } finally { scope.cancel() }
    }

    @Test fun defaultBackgroundChoiceSchedulesOnlyManualSyncAndOffUsesTheInProcessPath() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(old); var requests = 0; val enqueued = mutableListOf<String>(); var cancelled = 0
        val background = object : LastFmBackground {
            override suspend fun enqueue(username: String) { enqueued += username }
            override fun cancel() { cancelled++ }
        }
        val consent = Consent()
        val controller = controller(store, scope, LastFmApi { _, page -> requests++
            LastFmPage(page, 1, listOf(LastFmTrack("New", "Complete", 90))) }, consent, background = background)
        try {
            controller.ready(); assertTrue(store.read().keepSyncingInBackground)
            assertTrue(enqueued.isEmpty()); assertEquals(0, requests)
            controller.sync("user"); yield(); assertEquals(listOf("user"), enqueued); assertEquals(0, requests)
            controller.keepSyncingInBackground(false)
            withTimeout(2000) { controller.state.first { !it.saved.keepSyncingInBackground } }
            controller.sync("user")
            withTimeout(2000) { controller.state.first { it.saved.snapshot?.tracks?.single()?.title == "Complete" } }
            assertEquals(1, requests); assertEquals(listOf("user"), enqueued)
            consent.setAllowed(false); yield(); assertEquals(1, cancelled)
            controller.disconnect()
            withTimeout(2000) { controller.state.first { it.saved.snapshot == null } }
            assertFalse(store.read().keepSyncingInBackground)
        } finally { scope.cancel() }
    }

    @Test fun legacySnapshotDefaultsToBackgroundOnAndAccountStatsNeedNoExtraRequests() {
        assertTrue(LastFmSnapshotCodec.decode("""{"username":"user","mappings":{}}""").keepSyncingInBackground)
        val snapshot = LastFmSnapshot("u", 1, listOf(LastFmTrack("Beyoncé", "Song", 40, "Album"),
            LastFmTrack("BEYONCE", "Song - Remaster", 2, "Album (Deluxe Edition)"), LastFmTrack("Other", "Song", 3)))
        assertEquals(LastFmAccountStats(3, 45, 2, 2, 1, 2), snapshot.accountStats())
        assertEquals(Long.MAX_VALUE, snapshot.copy(tracks = listOf(snapshot.tracks[0].copy(plays = Long.MAX_VALUE), snapshot.tracks[1])).accountStats().scrobbles)
        assertEquals(100, LastFmProgress(3, 3, 200).percent)
        assertEquals("Page 1 of 2 · 50% · 200 songs fetched", LastFmProgress(1, 2, 200).label)
    }
    @Test fun restoredBackgroundProgressDoesNotLetOldFinishedWorkClearANewInProcessSync() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(old)
        val events = MutableStateFlow(LastFmBackgroundState(true, LastFmProgress(3, 10, 600)))
        val background = object : LastFmBackground {
            override val updates = events
            override suspend fun enqueue(username: String) = Unit
            override fun cancel() = Unit
        }
        val second = CompletableDeferred<LastFmPage>()
        val controller = controller(store, scope, LastFmApi { _, page -> if (page == 1)
            LastFmPage(1, 2, listOf(LastFmTrack("New", "First", 20))) else second.await() }, background = background)
        try {
            controller.ready()
            withTimeout(2000) { controller.state.first { it.progress?.songs == 600 } }
            assertEquals(old.snapshot, controller.state.value.saved.snapshot)
            events.value = LastFmBackgroundState(false)
            withTimeout(2000) { controller.state.first { !it.syncing } }
            controller.keepSyncingInBackground(false)
            withTimeout(2000) { controller.state.first { !it.saved.keepSyncingInBackground } }
            controller.sync("user")
            withTimeout(2000) { controller.state.first { it.progress?.page == 1 } }
            events.value = LastFmBackgroundState(false, problem = "Historical finished job")
            yield()
            assertTrue(controller.state.value.syncing)
            assertNull(controller.state.value.problem)
            second.complete(LastFmPage(2, 2, listOf(LastFmTrack("New", "Second", 30))))
            withTimeout(2000) { controller.state.first { !it.syncing } }
            assertEquals(2, store.read().snapshot!!.tracks.size)
        } finally { scope.cancel() }
    }
}

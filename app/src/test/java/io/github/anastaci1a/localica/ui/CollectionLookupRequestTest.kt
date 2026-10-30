package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionLookupRequestTest {
    @Test fun laterRequestWinsEvenWhenCanceledWorkFinishesAfterIt() = runTest {
        val requests = LatestUiRequest()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        requests.submit(this, dispatcher, { true }, {
            oldStarted.complete(Unit)
            withContext(NonCancellable) { releaseOld.await() }
            "old"
        }, published::add)
        runCurrent(); assertTrue(oldStarted.isCompleted)
        requests.submit(this, dispatcher, { true }, { "new" }, published::add)
        runCurrent(); assertEquals(listOf("new"), published)
        releaseOld.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("new"), published)
    }
    @Test fun explicitCancellationBeforeDispatchDoesNotEvaluateOrPublish() = runTest {
        val requests = LatestUiRequest()
        var evaluated = false
        var published = false
        requests.submit(this, StandardTestDispatcher(testScheduler), { true }, { evaluated = true; "value" }, { published = true })
        requests.cancel(); advanceUntilIdle()
        assertFalse(evaluated); assertFalse(published)
    }
    @Test fun dismissalOrSourceReplacementRejectsAnOtherwiseSuccessfulCompletion() = runTest {
        for (reason in listOf("dismissal", "source", "route", "occurrence")) {
            val requests = LatestUiRequest()
            val release = CompletableDeferred<Unit>()
            var owner = Any()
            val requestedOwner = owner
            val published = mutableListOf<String>()
            requests.submit(this, StandardTestDispatcher(testScheduler), { owner === requestedOwner }, { release.await(); reason }, published::add)
            runCurrent(); owner = Any(); release.complete(Unit); advanceUntilIdle()
            assertTrue("Superseded $reason cannot navigate", published.isEmpty())
        }
    }
    @Test fun parentDisposalAndExplicitCancellationRejectNonCooperativeLateResults() = runTest {
        for (dispose in listOf(false, true)) {
            val requests = LatestUiRequest()
            val parent = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent)
            val release = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            requests.submit(scope, StandardTestDispatcher(testScheduler), { true }, {
                withContext(NonCancellable) { release.await() }; "late"
            }, published::add)
            runCurrent()
            if (dispose) parent.cancel() else requests.cancel()
            release.complete(Unit); advanceUntilIdle()
            assertTrue(published.isEmpty()); parent.cancel()
        }
    }
    @Test fun noMatchIsSilentAndAnAcceptedOwnerPublishesOnlyOnce() = runTest {
        val requests = LatestUiRequest()
        val published = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        requests.submit<String>(this, dispatcher, { true }, { null }, published::add)
        advanceUntilIdle(); assertTrue(published.isEmpty())
        requests.submit(this, dispatcher, { true }, { "accepted" }, published::add)
        advanceUntilIdle(); advanceUntilIdle()
        assertEquals(listOf("accepted"), published)
    }
    @Test fun queryFailureRemainsInTheCauseChainAndCannotPublish() = runTest {
        val failure = IllegalStateException("provider query failed")
        val received = mutableListOf<Throwable>()
        val parent = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent + CoroutineExceptionHandler { _, error -> received += error })
        var published = false
        LatestUiRequest().submit<String>(scope, StandardTestDispatcher(testScheduler), { true }, { throw failure }, { published = true })
        advanceUntilIdle()
        assertEquals(1, received.size)
        val forwarded = received.single()
        assertEquals(failure.javaClass, forwarded.javaClass)
        assertEquals(failure.message, forwarded.message)
        // Coroutine stack-trace recovery may copy the exception and retain the original as its cause.
        assertTrue(generateSequence(forwarded) { it.cause }.take(8).any { it === failure })
        assertFalse(published)
        parent.cancel()
    }
    @Test fun freshnessUsesSourceIdentityWithoutTraversingPlaylistOrTrackLists() {
        val playlists = object : AbstractList<ManualPlaylist>() {
            override val size get(): Int = error("Do not read the playlist list")
            override fun get(index: Int): ManualPlaylist = error("Do not read the playlist list")
            override fun equals(other: Any?): Boolean = error("Do not compare list contents")
            override fun hashCode(): Int = error("Do not hash list contents")
        }
        val snapshot = LibrarySnapshot()
        val first = CollectionLookupSource(snapshot, playlists, BrowseSortSnapshot.Default)
        val same = CollectionLookupSource(snapshot, playlists, BrowseSortSnapshot.Default)
        assertEquals(first, same); assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, CollectionLookupSource(snapshot.copy(), playlists, BrowseSortSnapshot.Default))
        assertNotEquals(first, CollectionLookupSource(snapshot, emptyList(), BrowseSortSnapshot.Default))
        val sorts = BrowseSortSnapshot.fromValues(mapOf("catalog.Album.sort" to "Year"))
        assertNotEquals(first, CollectionLookupSource(snapshot, playlists, sorts))
    }
}

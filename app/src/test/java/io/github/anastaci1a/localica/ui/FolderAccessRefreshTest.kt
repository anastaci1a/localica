package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.FolderSelection
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderAccessRefreshTest {
    private val folders = listOf(FolderSelection("root", "Music"))

    @Test fun permissionReadRunsOnWorkerAndPublishesOnOwnerThread() = runBlocking {
        val owner = Thread.currentThread()
        var reader: Thread? = null
        var publisher: Thread? = null
        var result: Set<String>? = null
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val job = refreshFolderAccess(LatestUiRequest(), this, worker, folders, { folders }, {
                assertSame(folders, it); reader = Thread.currentThread(); setOf("root")
            }, { publisher = Thread.currentThread(); result = it })
            withTimeout(5000) { job.join() }
        }
        assertNotSame(owner, reader)
        assertNotNull(reader)
        assertSame(owner, publisher)
        assertEquals(setOf("root"), result)
    }

    @Test fun newerPermissionObservationWinsEvenWhenCanceledOldReadReturnsLater() = runTest {
        val requests = LatestUiRequest()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<Set<String>>()
        try {
            refreshFolderAccess(requests, this, dispatcher, folders, { folders }, {
                withContext(NonCancellable) { release.await() }; setOf("root")
            }, published::add)
            runCurrent()
            refreshFolderAccess(requests, this, dispatcher, folders, { folders }, { emptySet() }, published::add)
            runCurrent()
            assertEquals(listOf(emptySet<String>()), published)
            release.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(emptySet<String>()), published)
        } finally { release.complete(Unit); requests.cancel(); advanceUntilIdle() }
    }

    @Test fun changedFolderOwnerAndDisposedScopeCannotPublishLateMissingFolders() = runTest {
        for (dispose in listOf(false, true)) {
            var current = folders
            val release = CompletableDeferred<Unit>()
            val parent = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent)
            val published = mutableListOf<Set<String>>()
            try {
                refreshFolderAccess(LatestUiRequest(), scope, StandardTestDispatcher(testScheduler), folders,
                    { current }, { withContext(NonCancellable) { release.await() }; setOf("root") }, published::add)
                runCurrent()
                if (dispose) parent.cancel() else current = listOf(FolderSelection("other", "Other"))
                release.complete(Unit); advanceUntilIdle()
                assertTrue(published.isEmpty())
            } finally { release.complete(Unit); parent.cancel(); advanceUntilIdle() }
        }
    }

    @Test fun repeatedRefreshReadsCurrentGrantStateWithoutCachingThePriorDecision() = runTest {
        val requests = LatestUiRequest()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var granted = false
        var reads = 0
        val published = mutableListOf<Set<String>>()
        suspend fun read(): Set<String> { reads++; return if (granted) emptySet() else setOf("root") }
        refreshFolderAccess(requests, this, dispatcher, folders, { folders }, { read() }, published::add)
        advanceUntilIdle(); granted = true
        refreshFolderAccess(requests, this, dispatcher, folders, { folders }, { read() }, published::add)
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(listOf(setOf("root"), emptySet()), published)
    }
}

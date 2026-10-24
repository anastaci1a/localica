package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadbackCompletionTest {
    @Test fun normalReadReleasesBeforeReturningItsValue() = runTest {
        val owner = ReadbackCompletion()
        val lease = Any()
        val events = mutableListOf<String>()
        val value = owner.capture(
            acquire = { events += "acquire"; lease },
            read = { assertSame(lease, it); events += "read"; 42 },
            release = { assertSame(lease, it); events += "release" },
        )
        events += "publish"
        assertEquals(42, value)
        assertEquals(listOf("acquire", "read", "release", "publish"), events)
    }

    @Test fun cancellationCannotReleasePendingReadOrPublishItsResult() = runTest {
        val owner = ReadbackCompletion()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val lease = Any()
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.capture(
                acquire = { events += "acquire"; lease },
                read = {
                    assertSame(lease, it)
                    entered.complete(Unit)
                    finish.await()
                    events += "read-complete"
                    42
                },
                release = { assertSame(lease, it); events += "release" },
            )
            events += "publish"
        }
        assertTrue(entered.isCompleted)
        job.cancel()
        runCurrent()
        assertFalse("Pending native work still owns its lease", job.isCompleted)
        assertEquals(listOf("acquire"), events)
        finish.complete(Unit)
        job.join()
        assertEquals(listOf("acquire", "read-complete", "release"), events)
        assertTrue(job.isCancelled)
    }

    @Test fun readFailureReleasesExactlyOnceAndPreservesTheException() = runTest {
        val owner = ReadbackCompletion()
        val failure = IllegalStateException("read failed")
        val lease = Any()
        var releases = 0
        try {
            owner.capture<Any, Int>(
                acquire = { lease },
                read = { throw failure },
                release = { assertSame(lease, it); releases++ },
            )
            fail("Read failure must escape")
        } catch (actual: IllegalStateException) {
            assertTrue("The original failure remains observable", generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
        assertEquals(1, releases)
    }

    @Test fun acquireFailureCannotReadOrReleaseAnUnownedLease() = runTest {
        val owner = ReadbackCompletion()
        val failure = IllegalArgumentException("acquire failed")
        var reads = 0
        var releases = 0
        try {
            owner.capture<Any, Int>(
                acquire = { throw failure },
                read = { reads++; 1 },
                release = { releases++ },
            )
            fail("Acquire failure must escape")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
        assertEquals(0, reads)
        assertEquals(0, releases)
    }

    @Test fun alreadyCancelledCallerCannotAcquire() = runTest {
        val owner = ReadbackCompletion()
        var acquisitions = 0
        var releases = 0
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            // Execute capture itself with a cancelled job, rather than cancelling an unstarted body.
            currentCoroutineContext().job.cancel()
            owner.capture(
                acquire = { acquisitions++; Any() },
                read = { 1 },
                release = { releases++ },
            )
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, acquisitions)
        assertEquals(0, releases)
        assertFalse(published)
    }

    @Test fun supersedingReadWaitsForCancelledPredecessorsCopyAndRelease() = runTest {
        val owner = ReadbackCompletion()
        val finishFirst = CompletableDeferred<Unit>()
        val enteredFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val publications = mutableListOf<Int>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            publications += owner.capture(
                acquire = { events += "acquire-first"; 1 },
                read = { enteredFirst.complete(Unit); finishFirst.await(); events += "copy-first"; it },
                release = { events += "release-first" },
            )
        }
        assertTrue(enteredFirst.isCompleted)
        first.cancel()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            publications += owner.capture(
                acquire = { events += "acquire-second"; 2 },
                read = { events += "copy-second"; it },
                release = { events += "release-second" },
            )
        }
        runCurrent()
        assertEquals(listOf("acquire-first"), events)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        finishFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("acquire-first", "copy-first", "release-first", "acquire-second", "copy-second", "release-second"), events)
        assertEquals(listOf(2), publications)
    }
}

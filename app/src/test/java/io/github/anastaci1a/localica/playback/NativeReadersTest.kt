package io.github.anastaci1a.localica.playback

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class NativeReadersTest {
    private fun assertCloseFailure(cause: Throwable, failure: Throwable?) {
        assertTrue(failure is IOException)
        // Coroutine stack-trace recovery can add another copy of the IOException.
        assertTrue(generateSequence(failure) { it.cause }.any { it === cause })
    }

    @Test fun blockWaitsForEveryAdmittedOpeningAndRejectsLaterOpens() = runTest {
        val readers = NativeReaders()
        val current = readers.opening("song")
        val preload = readers.opening("song")
        val block = readers.block("song")
        val drained = async { block.awaitClosed() }
        testScheduler.runCurrent()
        assertFalse(drained.isCompleted)
        assertTrue(runCatching { readers.opening("song") }.exceptionOrNull() is IOException)
        readers.opening("unrelated").closed()
        current.closed(); current.closed()
        testScheduler.runCurrent()
        assertFalse(drained.isCompleted)
        preload.closed()
        drained.await()
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        block.release()
        readers.opening("song").closed()
    }

    @Test fun cancelledWaitDoesNotReleaseAdmissionOrCancelAnotherWaiter() = runTest {
        val readers = NativeReaders()
        val reader = readers.opening("song")
        val block = readers.block("song")
        val abandoned = launch { block.awaitClosed() }
        testScheduler.runCurrent()
        abandoned.cancelAndJoin()
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        val next = async { block.awaitClosed() }
        reader.closed()
        next.await()
        block.release()
    }

    @Test fun duplicateAndOldReleaseCannotUnblockNewOwner() = runTest {
        val readers = NativeReaders()
        val first = readers.block("song")
        val second = readers.block("song")
        first.awaitClosed(); second.awaitClosed()
        first.release(); first.release()
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        second.release()
        val third = readers.block("song")
        first.release(); second.release()
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        third.release()
        readers.opening("song").closed()
    }

    @Test fun failedCloseStaysUnsafeAcrossReleaseAndReacquisition() = runTest {
        val readers = NativeReaders()
        val reader = readers.opening("song")
        val first = readers.block("song")
        val cause = IOException("Provider close failed")
        reader.failedClose(cause)
        reader.closed() // A duplicate cleanup cannot turn unknown ownership into a close proof.
        assertCloseFailure(cause, runCatching { first.awaitClosed() }.exceptionOrNull())
        first.release()
        val second = readers.block("song")
        assertCloseFailure(cause, runCatching { second.awaitClosed() }.exceptionOrNull())
        second.release()
        val unrelated = readers.block("unrelated")
        unrelated.awaitClosed(); unrelated.release()
    }

    @Test fun drainContinuationNeverRunsUnderRegistryMonitor() = runBlocking {
        val readers = NativeReaders()
        val reader = readers.opening("song")
        val block = readers.block("song")
        var opener: Thread? = null
        val waiter = async(Dispatchers.Unconfined) {
            block.awaitClosed()
            val opened = CountDownLatch(1)
            opener = thread { readers.opening("unrelated").closed(); opened.countDown() }
            assertTrue("An inline writer continuation must not hold the registry lock", opened.await(5, TimeUnit.SECONDS))
        }
        try { reader.closed(); waiter.await() }
        finally { block.release(); opener?.join(5000) }
    }

    @Test fun registerAndBlockRaceHasNoUncountedAdmission() = runBlocking {
        repeat(100) {
            val readers = NativeReaders()
            val start = CountDownLatch(1)
            val attempted = CountDownLatch(1)
            var reader: NativeReaders.Reader? = null
            val opener = thread {
                start.await()
                reader = runCatching { readers.opening("song") }.getOrNull()
                attempted.countDown()
            }
            start.countDown()
            val block = readers.block("song")
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            opener.join()
            val drain = async(start = CoroutineStart.UNDISPATCHED) { block.awaitClosed() }
            assertEquals(reader == null, drain.isCompleted)
            reader?.closed()
            withTimeout(5000) { drain.await() }
            block.release()
        }
    }
    @Test fun unresolvedStartupBlocksBeforeRegistrationAndKnownRecoveryIsSelective() {
        val readers = NativeReaders(RecoveryAdmission.Checking)
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        assertTrue(runCatching { readers.opening("other") }.isFailure)
        readers.publishRecovery(RecoveryAdmission.Pending("song"))
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        readers.opening("other").closed()
        readers.publishRecovery(RecoveryAdmission.Unknown("Unreadable journal"))
        assertTrue(runCatching { readers.opening("other") }.isFailure)
    }

    @Test fun unsafePublicationSurvivesTemporaryReleaseAndClearStillRespectsOtherLeases() = runTest {
        val readers = NativeReaders()
        val opening = readers.opening("song")
        val first = readers.block("song")
        readers.publishRecovery(RecoveryAdmission.Pending("song"))
        val drain = async { first.awaitClosed() }
        testScheduler.runCurrent()
        assertFalse(drain.isCompleted)
        opening.closed(); drain.await()
        first.release(); first.release()
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        val recovery = readers.block("song")
        recovery.awaitClosed()
        readers.publishRecovery(RecoveryAdmission.Clear)
        assertTrue(runCatching { readers.opening("song") }.isFailure)
        recovery.release()
        readers.opening("song").closed()
    }

    @Test fun recoveryNotificationRunsOutsideMonitorAndAlwaysReadsLatestSnapshot() = runBlocking {
        val readers = NativeReaders()
        var checked = 0
        val collector = launch(Dispatchers.Unconfined) {
            readers.recoveryChanges.collect {
                if (readers.recovery() is RecoveryAdmission.Pending) {
                    val opened = CountDownLatch(1)
                    val other = thread { readers.opening("other").closed(); opened.countDown() }
                    assertTrue(opened.await(5, TimeUnit.SECONDS))
                    other.join(); checked++
                }
            }
        }
        try {
            readers.publishRecovery(RecoveryAdmission.Pending("song"))
            assertEquals(1, checked)
            readers.publishRecovery(RecoveryAdmission.Clear)
            assertEquals(RecoveryAdmission.Clear, readers.recovery())
        } finally { collector.cancelAndJoin() }
    }
}

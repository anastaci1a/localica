package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic streams only. Latch deadlines are failure bounds, not throughput assertions. */
@RunWith(AndroidJUnit4::class)
class PrivateDiagnosticWriterTest {
    private class GateSink(
        private val writeFailure: IOException? = null,
        private val failWriteNumber: Int = 1,
        private val closeFailure: IOException? = null,
    ) : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val writes = AtomicInteger()
        val closes = AtomicInteger()
        val owner = AtomicReference<Thread?>()
        private val bytes = ByteArrayOutputStream()

        private fun observeOwner() {
            val current = Thread.currentThread()
            val previous = owner.get()
            if (previous == null) owner.compareAndSet(null, current)
            check(owner.get() === current) { "Sink write/close changed worker ownership" }
        }

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(data: ByteArray, offset: Int, length: Int) {
            observeOwner()
            val call = writes.incrementAndGet()
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Test sink gate was not released")
            if (writeFailure != null && call == failWriteNumber) throw writeFailure
            synchronized(bytes) { bytes.write(data, offset, length) }
        }

        override fun close() {
            observeOwner()
            closes.incrementAndGet()
            try {
                closeFailure?.let { throw it }
            } finally {
                closed.countDown()
            }
        }

        fun snapshot(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
    }

    private fun await(latch: CountDownLatch, message: String) {
        assertTrue(message, latch.await(5, TimeUnit.SECONDS))
    }

    private fun ioFailure(action: () -> Unit): IOException {
        try {
            action()
        } catch (failure: IOException) {
            return failure
        }
        throw AssertionError("Expected explicit diagnostic IOException")
    }

    private fun cleanup(writer: PrivateDiagnosticWriter, sink: GateSink) {
        sink.release.countDown()
        // Error-path tests already assert close's exact failure in their bodies.
        runCatching { writer.close() }
        await(sink.closed, "Owned sink must eventually close after releasing the test gate")
        val worker = requireNotNull(sink.owner.get())
        worker.join(5_000)
        assertFalse("Only the owned worker is joined; no leaked worker", worker.isAlive)
    }

    @Test fun fifoCopiesReusedInputAndSuccessfulCloseDrainsAndClosesExactlyOnce() {
        assertEquals(4 * 1024 * 1024, PrivateDiagnosticWriter.DEFAULT_MAX_OUTSTANDING_BYTES)
        assertEquals(15_000L, PrivateDiagnosticWriter.DEFAULT_CLOSE_TIMEOUT_MILLIS)
        val sink = GateSink()
        val writer = PrivateDiagnosticWriter(sink)
        try {
            val reused = byteArrayOf(0, 1, 2, 3)
            writer.write(reused, 1, 2)
            await(sink.entered, "First owned write must be in flight")
            reused.fill(9)
            writer.write(reused, 0, 2)
            reused.fill(5)
            writer.write(0x107)
            writer.write(byteArrayOf(), 0, 0)
            sink.release.countDown()
            writer.close()
            assertArrayEquals(byteArrayOf(1, 2, 9, 9, 7), sink.snapshot())
            assertEquals(1, sink.closes.get())
            assertTrue("Hung diagnostic work cannot keep the process alive", requireNotNull(sink.owner.get()).isDaemon)
            assertNotSame(Thread.currentThread(), sink.owner.get())
            writer.close()
            assertEquals(1, sink.closes.get())
            ioFailure { writer.write(8) }
        } finally {
            cleanup(writer, sink)
        }
    }

    @Test fun blockedSinkDoesNotHoldTheProducerLockOrBlockTheNextOffer() {
        val sink = GateSink()
        val writer = PrivateDiagnosticWriter(sink, maxOutstandingBytes = 8)
        val offered = CountDownLatch(1)
        val producerFailure = AtomicReference<Throwable?>()
        val producer = Thread({
            try {
                writer.write(byteArrayOf(3, 4))
            } catch (failure: Throwable) {
                producerFailure.set(failure)
            } finally {
                offered.countDown()
            }
        }, "OwnedDiagnosticOfferTest")
        try {
            writer.write(byteArrayOf(1, 2))
            await(sink.entered, "Worker must enter the blocked sink")
            producer.start()
            await(offered, "The second offer must finish while sink I/O remains blocked")
            assertNull(producerFailure.get())
            assertEquals("Sink gate is still closed, independent of elapsed time", 1L, sink.release.count)
            assertEquals(0, sink.closes.get())
            sink.release.countDown()
            writer.close()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.snapshot())
        } finally {
            sink.release.countDown()
            try {
                producer.join(5_000)
                assertFalse("Join only this test's producer", producer.isAlive)
            } finally {
                cleanup(writer, sink)
            }
        }
    }

    @Test fun outstandingBudgetIncludesInFlightBytesAndOverflowFailsWithoutLosingAcceptedBytes() {
        val sink = GateSink()
        val writer = PrivateDiagnosticWriter(sink, maxOutstandingBytes = 4)
        try {
            writer.write(byteArrayOf(1, 2, 3))
            await(sink.entered, "Three bytes must remain in flight")
            writer.write(byteArrayOf(4))
            val capacity = ioFailure { writer.write(byteArrayOf(5)) }
            assertTrue(capacity.message.orEmpty().contains("byte budget exhausted"))
            assertSame(capacity, ioFailure { writer.write(6) })
            assertEquals(1L, sink.release.count)
            assertEquals(0, sink.closes.get())
            sink.release.countDown()
            assertSame("Failed offer prevents a successful-result claim", capacity, ioFailure { writer.close() })
            assertArrayEquals("All accepted bytes still drain in FIFO order", byteArrayOf(1, 2, 3, 4), sink.snapshot())
            assertEquals(1, sink.closes.get())
        } finally {
            cleanup(writer, sink)
        }
    }

    @Test fun workerIoFailureReachesWriteAndCloseAndPreservesPartialBytesAndCleanupFailure() {
        val writeFailure = IOException("Synthetic write failure")
        val closeFailure = IOException("Synthetic close failure")
        val sink = GateSink(writeFailure, failWriteNumber = 2, closeFailure = closeFailure)
        val writer = PrivateDiagnosticWriter(sink)
        try {
            writer.write(byteArrayOf(1))
            await(sink.entered, "First write must be owned by the worker")
            writer.write(byteArrayOf(2))
            writer.write(byteArrayOf(3))
            sink.release.countDown()
            await(sink.closed, "Worker must own error-path cleanup")
            assertSame(writeFailure, ioFailure { writer.write(4) })
            assertSame(writeFailure, ioFailure { writer.close() })
            assertArrayEquals("Already-written evidence remains available on failure", byteArrayOf(1), sink.snapshot())
            assertTrue(writeFailure.suppressed.any { it === closeFailure })
            assertSame(writeFailure, ioFailure { writer.close() })
            assertEquals(1, sink.closes.get())
        } finally {
            cleanup(writer, sink)
        }
    }

    @Test fun underlyingCloseFailureCannotBecomeASuccessfulDiagnosticResult() {
        val failure = IOException("Synthetic close-only failure")
        val sink = GateSink(closeFailure = failure)
        val writer = PrivateDiagnosticWriter(sink)
        try {
            writer.write(byteArrayOf(7, 8))
            await(sink.entered, "Worker must receive bytes before close")
            sink.release.countDown()
            assertSame(failure, ioFailure { writer.close() })
            assertArrayEquals(byteArrayOf(7, 8), sink.snapshot())
            assertSame(failure, ioFailure { writer.write(9) })
            assertSame(failure, ioFailure { writer.close() })
            assertEquals(1, sink.closes.get())
        } finally {
            cleanup(writer, sink)
        }
    }

    @Test fun closeTimeoutFailsWithoutClosingOrInterruptingTheInFlightSink() {
        val sink = GateSink()
        val writer = PrivateDiagnosticWriter(sink, maxOutstandingBytes = 8, closeTimeoutMillis = 50)
        try {
            writer.write(byteArrayOf(1, 2))
            await(sink.entered, "Worker must be blocked in the sink before close")
            writer.write(byteArrayOf(3, 4))
            val timeout = ioFailure { writer.close() }
            assertTrue(timeout.message.orEmpty().contains("timed out"))
            assertEquals("Caller cannot close a resource still being written", 0, sink.closes.get())
            assertEquals("Test still withholds native completion", 1L, sink.release.count)
            assertFalse(requireNotNull(sink.owner.get()).isInterrupted)
            assertSame(timeout, ioFailure { writer.write(5) })
            sink.release.countDown()
            await(sink.closed, "Worker must finish its own eventual cleanup")
            assertSame("Timed-out result remains explicitly failed", timeout, ioFailure { writer.close() })
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.snapshot())
            assertEquals(1, sink.closes.get())
        } finally {
            cleanup(writer, sink)
        }
    }
}

package io.github.anastaci1a.localica

import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * Test-only diagnostic output. A successful write means copied bytes were accepted;
 * only a successful close proves that every accepted byte was written and the sink closed.
 * The inherited OutputStream.flush is not a drain/completion operation.
 *
 * One daemon worker owns all sink writes and its one close. No sink I/O runs under
 * the producer lock. Capacity failure is terminal and explicit, but accepted data
 * still drains. A failed/timeout close never closes or interrupts an in-flight sink
 * from the caller: the worker retains cleanup ownership and can finish later.
 *
 * The budget bounds queued plus in-flight payload bytes, not object/allocator overhead.
 * This removes sink-I/O/backpressure waits from write, not allocation or scheduling cost.
 */
internal class PrivateDiagnosticWriter(
    private val sink: OutputStream,
    private val maxOutstandingBytes: Int = DEFAULT_MAX_OUTSTANDING_BYTES,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
) : OutputStream() {
    companion object {
        const val DEFAULT_MAX_OUTSTANDING_BYTES = 4 * 1024 * 1024
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 15_000L
    }

    private val lock = Object()
    private val queued = ArrayDeque<ByteArray>()
    private var outstandingBytes = 0
    private var closing = false
    private var recordedFailure: IOException? = null
    private val worker = Thread({ runWorker() }, "LocalicaPrivateDiagnosticWriter").apply {
        // A blocked diagnostic sink must not keep the instrumentation process alive.
        // Daemon status does not authorize anyone else to close its resource.
        isDaemon = true
    }

    init {
        require(maxOutstandingBytes > 0)
        require(closeTimeoutMillis > 0)
        worker.start()
    }

    @Throws(IOException::class)
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    @Throws(IOException::class)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        synchronized(lock) {
            recordedFailure?.let { throw it }
            if (closing) throw IOException("Private diagnostic writer is closed")
            if (length == 0) return
            if (length > maxOutstandingBytes - outstandingBytes) {
                val failure = IOException(
                    "Private diagnostic byte budget exhausted: outstanding=" + outstandingBytes +
                        ", offered=" + length + ", budget=" + maxOutstandingBytes
                )
                rememberFailureLocked(failure)
                closing = true
                lock.notifyAll()
                throw failure
            }
            // Copy before returning; capture buffers may be reused immediately.
            val copy = bytes.copyOfRange(offset, offset + length)
            queued.addLast(copy)
            outstandingBytes += length
            lock.notifyAll()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        synchronized(lock) {
            closing = true
            lock.notifyAll()
        }
        if (Thread.currentThread() === worker) {
            throw rememberFailure(IOException("Diagnostic worker cannot join itself"))
        }
        try {
            worker.join(closeTimeoutMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure = InterruptedIOException(
                "Interrupted awaiting diagnostic drain; worker retains sink ownership"
            ).apply { initCause(interrupted) }
            throw rememberFailure(failure)
        }
        if (worker.isAlive) {
            throw rememberFailure(IOException(
                "Diagnostic close timed out after " + closeTimeoutMillis +
                    " ms; worker retains sink ownership"
            ))
        }
        synchronized(lock) { recordedFailure?.let { throw it } }
    }

    private fun runWorker() {
        try {
            while (true) {
                val bytes = synchronized(lock) {
                    while (queued.isEmpty() && !closing) lock.wait()
                    if (queued.isEmpty()) null else queued.removeFirst()
                } ?: break
                try {
                    sink.write(bytes)
                } finally {
                    synchronized(lock) { outstandingBytes -= bytes.size }
                }
            }
        } catch (failure: Throwable) {
            rememberFailure(failure)
        } finally {
            try {
                sink.close()
            } catch (failure: Throwable) {
                rememberFailure(failure)
            } finally {
                synchronized(lock) {
                    // A worker I/O failure makes the result explicitly unsuccessful.
                    // Do not retain unwritable buffers after owned sink cleanup.
                    queued.clear()
                    outstandingBytes = 0
                }
            }
        }
    }

    private fun rememberFailure(failure: Throwable): IOException = synchronized(lock) {
        rememberFailureLocked(
            failure as? IOException ?: IOException("Private diagnostic worker failed", failure)
        )
    }

    private fun rememberFailureLocked(failure: IOException): IOException {
        val first = recordedFailure
        if (first == null) recordedFailure = failure
        else if (first !== failure) first.addSuppressed(failure)
        return recordedFailure!!
    }
}

package io.github.anastaci1a.localica

/**
 * Test-only primitive buffer, not a frame listener or a presentation measurement.
 * Construct, record and finish on the same owning thread. Snapshots may be read on
 * another thread after normal safe publication; they share no mutable buffer.
 *
 * The caller supplies nanoseconds in one explicitly compatible
 * monotonic clock domain for frame timestamps, arrivals and window bounds. This
 * class neither samples a clock nor proves clock compatibility; signed clock
 * origins are allowed and comparisons never subtract timestamps. Frames are kept
 * in delivery order, including duplicates, out-of-order timestamps and records
 * outside the eventual window. Capacity overflow retains aggregate loss counts,
 * not the rejected records; it must not be interpreted as zero bad frames.
 */
internal class NavigationFrameWindow(
    val capacity: Int,
    val deadlineMetricsSupported: Boolean
) {
    companion object {
        const val MAX_CAPACITY = 16_384
        const val UNAVAILABLE = -1L
    }

    enum class Position { BEFORE, IN_WINDOW, AFTER }

    /** A bounded drain is the caller's observation, not proof of all native delivery. */
    enum class TailStatus { INCOMPLETE, BOUNDED_DRAIN_FINISHED }

    private val owner = Thread.currentThread()
    private var finished = false
    private val frameTimes: LongArray
    private val arrivals: LongArray
    private val durations: LongArray
    private val deadlines: LongArray
    private val reportedDrops: IntArray
    private var size = 0
    private var received = 0L
    private var listenerDrops = 0L
    private var overflow = 0L

    init {
        require(capacity in 1..MAX_CAPACITY) { "Capacity must be between 1 and $MAX_CAPACITY" }
        frameTimes = LongArray(capacity)
        arrivals = LongArray(capacity)
        durations = LongArray(capacity)
        deadlines = LongArray(capacity)
        reportedDrops = IntArray(capacity)
    }

    /**
     * Copies primitives only. Returns false if the bounded buffer is full.
     * Reported listener drops are counted even for an overflowing observation;
     * they are listener-report loss, not compositor/display-frame loss, and
     * cannot be attributed to this window from a callback's count alone.
     * Durations are elapsed metrics, not CPU attribution or input-to-pixel latency.
     * No refresh-derived deadline is substituted for an unavailable metric.
     */
    fun record(
        frameTimestampNanos: Long,
        callbackArrivalNanos: Long,
        totalDurationNanos: Long,
        deadlineDurationNanos: Long = UNAVAILABLE,
        listenerDroppedCount: Int = 0
    ): Boolean {
        checkOpenOnOwner()
        require(totalDurationNanos >= 0 && listenerDroppedCount >= 0)
        require(deadlineDurationNanos == UNAVAILABLE || deadlineDurationNanos >= 0)
        require(deadlineMetricsSupported || deadlineDurationNanos == UNAVAILABLE) {
            "Unsupported deadline metrics must remain unavailable"
        }
        // Validate arithmetic before changing any counters or buffers.
        val nextReceived = Math.addExact(received, 1L)
        val nextDrops = Math.addExact(listenerDrops, listenerDroppedCount.toLong())
        val nextOverflow = if (size == capacity) Math.addExact(overflow, 1L) else overflow
        received = nextReceived
        listenerDrops = nextDrops
        overflow = nextOverflow
        if (size == capacity) return false
        frameTimes[size] = frameTimestampNanos
        arrivals[size] = callbackArrivalNanos
        durations[size] = totalDurationNanos
        deadlines[size] = deadlineDurationNanos
        reportedDrops[size] = listenerDroppedCount
        size++
        return true
    }

    /**
     * Finalizes once, after collection/drain has stopped on the owner thread.
     * A nonempty half-open [startInclusiveNanos, endExclusiveNanos) window uses
     * frame timestamps only, regardless of arrival or the reported duration.
     * The default tail is incomplete: simply calling finish is not a drain.
     * Invalid bounds leave the collector open; a successful finish forbids all
     * further records and finalizations, rather than silently losing late data.
     */
    fun finish(
        startInclusiveNanos: Long,
        endExclusiveNanos: Long,
        tailStatus: TailStatus = TailStatus.INCOMPLETE
    ): Snapshot {
        checkOpenOnOwner()
        require(endExclusiveNanos > startInclusiveNanos)
        val result = Snapshot(startInclusiveNanos, endExclusiveNanos, deadlineMetricsSupported,
            tailStatus, received, listenerDrops, overflow, frameTimes.copyOf(size),
            arrivals.copyOf(size), durations.copyOf(size), deadlines.copyOf(size), reportedDrops.copyOf(size))
        finished = true
        return result
    }

    private fun checkOpenOnOwner() {
        check(Thread.currentThread() === owner) { "Collector access must stay on its owning thread" }
        check(!finished) { "Collector is finalized" }
    }

    data class Observation(
        val frameTimestampNanos: Long,
        val callbackArrivalNanos: Long,
        val totalDurationNanos: Long,
        val deadlineDurationNanos: Long?,
        val listenerDroppedCount: Int,
        val position: Position,
        val arrivedAtOrAfterEnd: Boolean
    ) {
        val lateInWindow: Boolean get() = position == Position.IN_WINDOW && arrivedAtOrAfterEnd
        val exceedsReportedDeadline: Boolean? get() = deadlineDurationNanos?.let { totalDurationNanos > it }
    }

    class Snapshot internal constructor(
        val startInclusiveNanos: Long,
        val endExclusiveNanos: Long,
        val deadlineMetricsSupported: Boolean,
        val tailStatus: TailStatus,
        val receivedCount: Long,
        val reportedListenerDropCount: Long,
        val bufferOverflowCount: Long,
        frameTimes: LongArray,
        arrivals: LongArray,
        durations: LongArray,
        deadlines: LongArray,
        reportedDrops: IntArray
    ) {
        init {
            require(endExclusiveNanos > startInclusiveNanos)
            require(frameTimes.size <= MAX_CAPACITY)
            require(arrivals.size == frameTimes.size && durations.size == frameTimes.size &&
                deadlines.size == frameTimes.size && reportedDrops.size == frameTimes.size)
            require(receivedCount >= frameTimes.size && bufferOverflowCount >= 0 &&
                receivedCount - frameTimes.size == bufferOverflowCount)
            require(durations.all { it >= 0 } && reportedDrops.all { it >= 0 })
            require(reportedListenerDropCount >= reportedDrops.sumOf { it.toLong() })
            require(deadlines.all { it == UNAVAILABLE || (deadlineMetricsSupported && it >= 0) })
        }

        // Defend even the internal constructor against a caller retaining its arrays.
        private val frameTimes = frameTimes.copyOf()
        private val arrivals = arrivals.copyOf()
        private val durations = durations.copyOf()
        private val deadlines = deadlines.copyOf()
        private val reportedDrops = reportedDrops.copyOf()
        val storedCount: Int get() = frameTimes.size
        val beforeWindowCount: Int = frameTimes.count { it < startInclusiveNanos }
        val inWindowCount: Int = frameTimes.count { it >= startInclusiveNanos && it < endExclusiveNanos }
        val afterWindowCount: Int = frameTimes.count { it >= endExclusiveNanos }
        val lateInWindowCount: Int = frameTimes.indices.count {
            frameTimes[it] >= startInclusiveNanos && frameTimes[it] < endExclusiveNanos && arrivals[it] >= endExclusiveNanos
        }
        val incompleteTail: Boolean get() = tailStatus == TailStatus.INCOMPLETE
        /** False means no *reported* quality failure, not proof of complete native delivery. */
        val hasQualityFailure: Boolean get() = reportedListenerDropCount != 0L || bufferOverflowCount != 0L || incompleteTail

        /** Returns immutable values; no array, mutable list or backing storage escapes. */
        operator fun get(index: Int): Observation {
            val frame = frameTimes[index]
            val position = when {
                frame < startInclusiveNanos -> Position.BEFORE
                frame < endExclusiveNanos -> Position.IN_WINDOW
                else -> Position.AFTER
            }
            val deadline = deadlines[index].takeIf { deadlineMetricsSupported && it != UNAVAILABLE }
            return Observation(frame, arrivals[index], durations[index], deadline,
                reportedDrops[index], position, arrivals[index] >= endExclusiveNanos)
        }
    }
}

package io.github.anastaci1a.localica.diagnostics

import kotlin.math.ceil

/** Bounded received-frame sample. No idle-vsync inference: Android need not draw while idle. */
internal class FrameTiming(private val capacity: Int = 36_000) {
    init { require(capacity > 0) }
    data class Frame(val timestamp: Long, val duration: Long, val budget: Long, val deadline: Long)
    private val frames = ArrayList<Frame>()
    var listenerDrops = 0L; private set
    var overflow = 0L; private set
    var invalid = 0L; private set
    @Synchronized fun add(timestamp: Long, duration: Long, budget: Long, deadline: Long, drops: Int) {
        listenerDrops += drops.coerceAtLeast(0)
        if (timestamp < 0 || duration < 0 || budget <= 0) { invalid++; return }
        if (frames.size == capacity) { overflow++; return }
        frames += Frame(timestamp, duration, budget, deadline)
    }
    @Synchronized fun snapshot(start: Long = 0, end: Long = Long.MAX_VALUE): Summary {
        val selected = frames.filter { it.timestamp >= start && it.timestamp < end }
        val sorted = selected.map { it.duration }.sorted()
        fun percentile(p: Double) = if (sorted.isEmpty()) null else sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)] / 1e6
        return Summary(selected.size, percentile(.5), percentile(.9), percentile(.99),
            selected.count { it.duration > it.budget },
            selected.sumOf { ((it.duration - 1).coerceAtLeast(0) / it.budget) },
            selected.count { it.deadline > 0 && it.duration > it.deadline },
            selected.count { it.deadline > 0 }, listenerDrops, overflow, invalid, selected)
    }
    data class Summary(val count: Int, val p50Ms: Double?, val p90Ms: Double?, val p99Ms: Double?,
        val overBudget: Int, val estimatedMissedVsyncs: Long, val deadlineMisses: Int,
        val deadlineSamples: Int, val listenerDrops: Long, val overflow: Long, val invalid: Long,
        val frames: List<Frame>) {
        val jankPercent: Double? get() = if (count == 0) null else 100.0 * overBudget / count
    }
}

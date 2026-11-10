package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** The plan key that carries the entry behind the shuffle start; the tests pin its persisted shape. */
private const val BEHIND = Long.MIN_VALUE

/** #826 at operator scale: Previous costs the same at any history depth and keeps one entry of extra state. */
class QueueJourneyShufflePreviousScaleTest {
    /** Counts the entries an index lookup touches, a device-independent measure of per-step work. */
    private class CountedIds(private val values: List<String>) : AbstractList<String>() {
        var scanned = 0L
        override val size get() = values.size
        override fun get(index: Int) = values[index]
        override fun indexOf(element: String): Int {
            for (index in values.indices) {
                scanned++
                if (values[index] == element) return index
            }
            return -1
        }
    }

    private class Gc {
        private val beans = runCatching {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            @Suppress("UNCHECKED_CAST") factory.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<Any>
        }.getOrDefault(emptyList())
        private val bean = runCatching { Class.forName("java.lang.management.GarbageCollectorMXBean") }.getOrNull()
        private fun sum(name: String) = beans.sumOf { (bean!!.getMethod(name).invoke(it) as Long).coerceAtLeast(0) }
        val count get() = sum("getCollectionCount")
        val millis get() = sum("getCollectionTime")
    }

    private fun heapMb(): Long { System.gc(); val rt = Runtime.getRuntime(); return (rt.totalMemory() - rt.freeMemory()) shr 20 }

    private fun measure(size: Int, steps: Int) {
        val values = CountedIds(List(size) { "occurrence-$it" })
        val start = size * 3 / 4
        val ordered = QueueJourney.start(values, values[start], JourneyModes(), Random(7))
        var path = ordered.reconcile(values, values[start], JourneyModes(shuffle = true), Random(11))
        val plansSize = path.plans.values.sumOf { it.size }
        val heapBefore = heapMb()
        val gc = Gc(); val gcBefore = gc.count; val gcTimeBefore = gc.millis
        val perStep = LongArray(steps); val nanos = LongArray(steps)
        for (step in 0 until steps) {
            val expected = values[start - 1 - step]
            assertEquals(expected, path.previous?.entryId)
            val before = values.scanned
            val clock = System.nanoTime()
            path = path.arrive(path.previous!!.token, values, Random(13))
            nanos[step] = System.nanoTime() - clock
            perStep[step] = values.scanned - before
            assertEquals(expected, path.current?.entryId)
            assertEquals("One neighbor id and index, whatever the queue size", 2, path.plans.getValue(BEHIND).size)
            assertTrue(path.window().size <= 129)
        }
        val gcCount = gc.count - gcBefore; val gcMillis = gc.millis - gcTimeBefore
        val heapAfter = heapMb()
        assertEquals("History grows by exactly the visits walked", steps + 1, path.history.size)
        assertEquals("Plan state is unchanged by walking back", plansSize, path.plans.values.sumOf { it.size })
        // One index lookup per step, never growing with how far back the traversal has gone.
        assertEquals("Going back never scans the queue", 0L, perStep.max())
        assertTrue("Step ${steps - 1} must not cost more than the first (${perStep.first()} vs ${perStep.last()})",
            perStep.last() <= perStep.first())
        // Return forward through the retraced visits to prove the way back is a real history.
        repeat(steps) { path = path.arrive(path.next!!.token, values, Random(17)) }
        assertEquals(values[start], path.current?.entryId)
        // The reference: the same number of ordinary Next steps through the shuffle plan of an identical journey.
        var reference = ordered.reconcile(values, values[start], JourneyModes(shuffle = true), Random(11))
        val referenceNanos = LongArray(steps)
        val referenceGcBefore = gc.count; val referenceGcTimeBefore = gc.millis
        for (step in 0 until steps) {
            val clock = System.nanoTime()
            reference = reference.arrive(reference.next!!.token, values, Random(19))
            referenceNanos[step] = System.nanoTime() - clock
        }
        val referenceGcCount = gc.count - referenceGcBefore; val referenceGcMillis = gc.millis - referenceGcTimeBefore
        val sorted = nanos.sorted(); val referenceSorted = referenceNanos.sorted()
        println("RESULT826 size=$size steps=$steps scansPerStepFirst=${perStep.first()} scansPerStepLast=${perStep.last()} " +
            "scansPerStepMax=${perStep.max()} stepMsMedian=${sorted[steps / 2] / 1e6} stepMsP99=${sorted[steps * 99 / 100] / 1e6} " +
            "stepMsMax=${sorted.last() / 1e6} heapMbBefore=$heapBefore heapMbAfter=$heapAfter gcCount=$gcCount gcMillis=$gcMillis " +
            "nextStepMsMedian=${referenceSorted[steps / 2] / 1e6} nextStepMsP99=${referenceSorted[steps * 99 / 100] / 1e6} " +
            "nextGcCount=$referenceGcCount nextGcMillis=$referenceGcMillis")
    }

    @Test fun previousAtThirteenThousandFourHundredEntriesDoesNotGrowWithDepthOrQueueSize() = measure(13_400, 400)
    @Test fun previousAtFiftyThousandEntriesDoesNotGrowWithDepthOrQueueSize() = measure(50_000, 400)

    @Test fun savedStateCostsTwoRowsWhateverTheQueueSize() {
        val rows = listOf(13_400, 50_000).map { size ->
            val values = List(size) { "occurrence-$it" }
            val start = QueueJourney.start(values, values[size / 2], JourneyModes(), Random(7))
            val shuffled = start.reconcile(values, values[size / 2], JourneyModes(shuffle = true), Random(11))
            shuffled.cycleRecords("q").count { it.cycle == BEHIND }
        }
        assertEquals("The neighbor id and its index", listOf(2, 2), rows)
    }
}

package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.mapView
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyPreparationTest {
    private fun identity(visit: QueueVisit?) = visit?.let { it.entryId to it.cycle }

    @Test fun shufflePredecessorsAtOneMillionEntriesReuseRanksAndRetraceTheAnchor() {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val bean = Class.forName("java.lang.management.GarbageCollectorMXBean")
        val collectors = factory.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        fun gc(method: String) = collectors.sumOf { (bean.getMethod(method).invoke(it) as Long).coerceAtLeast(0) }
        fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        for (size in listOf(13_400, 50_000, 1_000_000)) {
            var reads = 0L
            val values = List(size) { "entry:$it" }
            val source = object : AbstractList<String>() {
                override val size get() = values.size
                override fun get(index: Int): String { reads++; return values[index] }
            }
            val ids = source.mapView { it }
            val start = size * 3 / 4
            val heapBefore = heap()
            val gcBefore = gc("getCollectionCount")
            val gcTimeBefore = gc("getCollectionTime")
            var path = QueueJourney.start(ids, values[start], JourneyModes(), Random(7))
                .reconcile(ids, values[start], JourneyModes(shuffle = true), Random(11))
            val anchor = path.current!!
            val permutation = path.plans.getValue(path.cycle)
            val heapPrepared = heap()
            val moveGc = gc("getCollectionCount")
            val moveGcTime = gc("getCollectionTime")
            val perStep = LongArray(128)
            val times = LongArray(128)
            for (step in perStep.indices) {
                val before = reads
                val time = System.nanoTime()
                val previous = path.previous!!
                path = path.arrive(previous.token, ids, Random(13))
                times[step] = System.nanoTime() - time
                perStep[step] = reads - before
                assertEquals(values[start - step - 1], path.current!!.entryId)
                assertSame("Previous retains the prepared permutation", permutation, path.plans.getValue(path.cycle))
                assertTrue(path.window().size <= 129)
            }
            val heapMoved = heap()
            val previousGc = gc("getCollectionCount") - moveGc
            val previousGcMillis = gc("getCollectionTime") - moveGcTime
            assertEquals(129, path.history.size)
            assertEquals(129, path.visited.size)
            repeat(128) { step ->
                val next = path.next!!
                assertEquals(values[start - 127 + step], next.entryId)
                path = path.arrive(next.token, ids, Random(17))
            }
            assertEquals(anchor, path.current)
            assertFalse("Next past the anchor excludes backward arrivals", path.next!!.entryId in path.visited)
            val sorted = times.sorted()
            println("SHUFFLE_PREVIOUS_SCALE entries=$size steps=${perStep.size} readsFirst=${perStep.first()} " +
                "readsLast=${perStep.last()} readsMax=${perStep.max()} heapBefore=$heapBefore heapPrepared=$heapPrepared " +
                "heapMoved=$heapMoved heapAfter=${heap()} gcCount=${gc("getCollectionCount") - gcBefore} " +
                "gcMillis=${gc("getCollectionTime") - gcTimeBefore} previousGc=$previousGc previousGcMillis=$previousGcMillis " +
                "previousMedianNs=${sorted[64]} previousMaxNs=${sorted.last()}")
            assertTrue("Prepared Previous must not scan the queue: ${perStep.max()} reads", perStep.max() <= 129)
        }
    }

    @Test fun seededTracesPreserveFrozenEntryCycleHistoryAndPermutationSemantics() {
        for (seed in 0..31) {
            val choices = Random(seed)
            var serial = 7
            var ids = List(serial) { "entry:$it" }
            var modes = JourneyModes()
            var path = QueueJourney.start(ids, ids[3], modes, Random(seed))
            var original = OriginalQueueJourney.start(ids, ids[3], modes, Random(seed))
            repeat(150) { step ->
                val draw = seed * 1000 + step
                when (choices.nextInt(9)) {
                    0, 1 -> if (original.next != null) {
                        path = path.arrive(path.next!!.token, ids, Random(draw))
                        original = original.arrive(original.next!!.token, ids, Random(draw))
                    }
                    2 -> if (original.previous != null) {
                        path = path.arrive(path.previous!!.token, ids, Random(draw))
                        original = original.arrive(original.previous!!.token, ids, Random(draw))
                    }
                    3 -> {
                        val selected = ids.random(choices)
                        path = path.select(selected, ids, Random(draw))
                        original = original.select(selected, ids, Random(draw))
                    }
                    4 -> ids = ids.shuffled(choices)
                    5 -> modes = modes.copy(shuffle = !modes.shuffle)
                    6 -> modes = modes.copy(loop = !modes.loop, reshuffle = choices.nextBoolean())
                    7 -> {
                        val added = listOf("entry:${serial++}", "entry:${serial++}")
                        ids = ids + added
                        path = path.playNext(added, ids, Random(draw))
                        original = original.playNext(added, ids, Random(draw))
                    }
                    else -> if (ids.size > 1) ids = ids - ids.random(choices)
                }
                path = path.reconcile(ids, path.current?.entryId, modes, Random(draw))
                original = original.reconcile(ids, original.current?.entryId, modes, Random(draw))
                assertEquals("seed $seed step $step", identity(original.current), identity(path.current))
                assertEquals(original.history.map(::identity), path.history.map(::identity))
                assertEquals(original.visited, path.visited)
                assertEquals(original.plans[original.cycle], path.plans[path.cycle])
                assertEquals(identity(original.next), identity(path.next))
                if (original.previous != null) assertEquals(identity(original.previous), identity(path.previous))
                assertTrue(path.window().size <= 129)
                assertEquals(path.window().size, path.window().map { it.token }.toSet().size)
            }
        }
    }

    @Test fun repeatedPlayNowInsertionsRepairTheSelectedRevisionWithFrozenRandomDraws() {
        var ids = List(12) { "entry:$it" }
        val modes = JourneyModes(true, true)
        var path = QueueJourney.start(ids, ids[3], modes, Random(9))
        var original = OriginalQueueJourney.start(ids, ids[3], modes, Random(9))
        repeat(24) { edit ->
            val added = List(4) { "insert:$edit:$it" }
            ids = ids + added
            path = path.select(added[1], ids, Random(edit))
            original = original.select(added[1], ids, Random(edit))
            assertEquals(original.history.map(::identity), path.history.map(::identity))
            assertEquals(original.plans, path.plans)
            assertEquals(original.visited, path.visited)
            assertEquals(identity(original.current), identity(path.current))
            assertEquals(identity(original.next), identity(path.next))
            val window = path.window()
            val center = window.indexOf(path.current)
            assertEquals(original.future.take(64).map(::identity), window.drop(center + 1).map(::identity))
            assertEquals(path.current, window[center])
            assertTrue(path.sourceOrdinal(ids, path.current!!.entryId)!! >= 0)
        }
    }

    @Test fun repeatedInsertionsRetainOnlyTheThreeLookaheadPermutations() {
        var ids = List(50_000) { "entry:$it" }
        var path = QueueJourney.start(ids, ids.first(), JourneyModes(true, true), Random(2))
        assertSame(path.plans.getValue(0), path.plans.getValue(1))
        assertSame(path.plans.getValue(1), path.plans.getValue(2))
        repeat(12) { edit ->
            val added = List(4) { "insert:$edit:$it" }
            ids = ids + added
            path = path.playNext(added, ids, Random(edit))
            assertEquals((path.cycle..path.cycle + 2).toSet(), path.plans.keys)
            val retained = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<List<String>, Boolean>())
            retained.addAll(path.plans.values)
            assertTrue(retained.size <= 3)
            assertTrue(retained.sumOf { it.size.toLong() } <= 3L * ids.size)
            path.plans.values.forEach { assertEquals(ids.toSet(), it.toSet()) }
        }
        assertEquals("The bound is exercised with three distinct edited sequences", 3, path.plans.values.distinct().size)
    }

    @Test fun identicalEditedLookaheadsShareTheirPermutationAfterTheRequiredRandomDraws() {
        var ids = List(100) { "entry:$it" }
        val modes = JourneyModes(true, true)
        var path = QueueJourney.start(ids, ids.first(), modes, Random(2))
        var original = OriginalQueueJourney.start(ids, ids.first(), modes, Random(2))
        repeat(12) { edit ->
            val added = listOf("insert:$edit")
            ids = ids + added
            path = path.playNext(added, ids, Random(edit))
            original = original.playNext(added, ids, Random(edit))
            assertEquals(original.plans, path.plans)
            assertSame(path.plans.getValue(0), path.plans.getValue(1))
            assertSame(path.plans.getValue(1), path.plans.getValue(2))
        }
    }

    @Test fun immutableOccurrenceRevisionsReusePreparationWithoutReadingAnElement() {
        val track = Track("content://same", "Same", fileName = "same.mp3", relativePath = "same.mp3")
        var reads = 0
        val entries = object : AbstractList<QueueEntry>() {
            override val size = 50_000
            override fun get(index: Int): QueueEntry { reads++; return QueueEntry("entry:$index", track) }
        }
        val queue = ListeningQueue(name = "Before", entries = entries)
        for (shuffle in listOf(false, true)) {
            val path = QueueJourney.start(queue.ids, "entry:25000", JourneyModes(shuffle, true), Random(7))
            reads = 0
            assertSame(path, path.reconcile(queue.copy(name = "After", positionMs = 4500).ids, "entry:25000", path.modes))
            val moved = path.arrive(path.next!!.token, queue.ids)
            assertSame(moved, moved.reconcile(queue.ids, moved.current!!.entryId, moved.modes))
            assertTrue("Worker finds only missing source coordinates with at most one order scan: $reads", reads <= entries.size + 1024)
            reads = 0
            repeat(30) { moved.window(); moved.next; moved.previous; moved.artworkWindow() }
            assertEquals("Published reads use prepared coordinates", 0, reads)
        }
    }

    @Test fun sourceCoordinatesRejectNegativeOrdinalsAndDifferentRevisionsAndResolveTheShufflePredecessor() {
        val projection: (String) -> String = { it }
        val ids = listOf("A", "B", "C").mapView(projection)
        val ordered = QueueJourney.start(ids, "B", JourneyModes(loop = true), Random(9))
        val shuffled = ordered.reconcile(ids, "B", JourneyModes(true, true), Random(9))
        assertEquals("A", shuffled.previous!!.entryId)
        assertEquals(0, shuffled.sourceOrdinal(ids, shuffled.previous!!.entryId))
        val revision = ids.toList().mapView(projection)
        assertEquals(ids, revision)
        assertNull(shuffled.sourceOrdinal(revision, "B"))
        val repaired = shuffled.reconcile(revision, "B", shuffled.modes)
        assertEquals(1, repaired.sourceOrdinal(revision, "B"))
        assertEquals(shuffled.window(), repaired.window())

        // Deliberately bypass repair to exercise the defensive negative-coordinate read itself.
        val malformed = QueueJourney(history = listOf(QueueVisit("removed", 0), QueueVisit("B", 0)), cursor = 1,
            visited = setOf("B"), plans = mapOf(0L to ids), modes = JourneyModes(shuffle = true), orderedTraversalId = "fixture")
        val prepare = QueueJourney::class.java.declaredMethods.single { it.name == "prepare" }.apply { isAccessible = true }
        val exposed = prepare.invoke(malformed, ids, Random(9), null, null, null, null) as QueueJourney
        assertEquals("removed", exposed.previous!!.entryId)
        assertNull(exposed.sourceOrdinal(ids, "removed"))
        val normalized = exposed.copy().reconcile(ids, "B", exposed.modes)
        assertTrue(normalized.window().none { it.entryId == "removed" })
        normalized.window().forEach { visit ->
            val ordinal = normalized.sourceOrdinal(ids, visit.entryId)
            assertNotNull(ordinal)
            assertEquals(visit.entryId, ids[ordinal!!])
        }
    }

    @Test fun mutableInputsAreIsolatedAndColdCopiesAndRestoresNormalizeBeforePublication() {
        val ids = mutableListOf("A", "B", "C")
        for (modes in listOf(JourneyModes(loop = true), JourneyModes(true, true, true))) {
            val path = QueueJourney.start(ids, "B", modes, Random(9))
            val window = path.window()
            ids.reverse()
            assertEquals(window, path.window())
            val changed = path.reconcile(ids, "B", modes)
            assertEquals(if (modes.shuffle) path.plans else mapOf(path.cycle to ids), changed.plans)
            val cold = changed.copy()
            assertNotSame(cold, cold.reconcile(ids, "B", modes))
            val restored = changed.record("q").restore(changed.visitRecords("q"), changed.cycleRecords("q").toList())
                .reconcile(ids, "B", modes)
            assertEquals(changed.history, restored.history)
            assertEquals(changed.window(), restored.window())
        }
    }

    @Test fun duplicateOccurrencesAndRestoredMembersRemainColdWithStableTokenAliases() {
        for ((ids, plan) in listOf(listOf("A", "B", "B") to emptyList(), listOf("A", "B") to listOf("A", "B", "B"))) {
            val raw = QueueJourney(history = listOf(QueueVisit("A", 0, "anchor")), cursor = 0,
                visited = setOf("A"), plans = if (plan.isEmpty()) emptyMap() else mapOf(0L to plan), modes = JourneyModes(shuffle = true))
            val view = ids.mapView { it }
            val first = raw.reconcile(view, "A", raw.modes, Random(3))
            val second = first.reconcile(view, "A", raw.modes, Random(3))
            assertNotSame(first, second)
            assertEquals(listOf("A", "B", "B"), second.window().map { it.entryId })
            assertEquals(first.next!!.token, second.next!!.token)
            assertEquals(second.window()[1].token, second.window()[2].token)
        }
    }

    @Test fun duplicatePlusMissingPlanCoversTheSourceButCannotBecomeWarmByCardinality() {
        val ids = listOf("A", "B", "C", "D").mapView { it }
        val raw = QueueJourney(history = listOf(QueueVisit("A", 0, "anchor")), cursor = 0,
            visited = setOf("A"), plans = mapOf(0L to listOf("A", "B", "B", "D")),
            modes = JourneyModes(shuffle = true))
        val prepared = raw.reconcile(ids, "A", raw.modes, Random(9))
        assertEquals(ids.toSet(), prepared.plans.getValue(0).toSet())
        assertEquals(5, prepared.plans.getValue(0).size)
        assertNotSame(prepared, prepared.reconcile(ids, "A", raw.modes, Random(9)))
        assertEquals(2, prepared.plans.getValue(0).count { it == "B" })
        assertEquals(1, prepared.plans.getValue(0).count { it == "C" })
    }

    @Test fun fixedSeedShufflePredecessorNeverBecomesAGeneratedVisitOrTruncatesForwardHistory() {
        val ids = listOf("A", "B", "C", "D")
        val initial = QueueJourney.start(ids, "D", JourneyModes()).reconcile(ids, "D",
            JourneyModes(shuffle = true), Random(9))
        assertEquals(listOf("D", "A", "C", "B"), initial.plans.getValue(0))
        val predecessor = initial.previous!!
        val generated = initial.window().single { it.entryId == "C" && it.token != predecessor.token }
        val advanced = initial.arrive(initial.next!!.token, ids)
        assertEquals("A", advanced.current!!.entryId)
        assertEquals(advanced.window().size, advanced.window().map { it.token }.toSet().size)
        assertEquals(generated, advanced.next)
        val forward = advanced.arrive(generated.token, ids)
        assertEquals("A", forward.previous!!.entryId)
        assertEquals("B", forward.next!!.entryId)
        val jumped = advanced.arrive(predecessor.token, ids, receipt = predecessor)
        assertEquals(predecessor, jumped.current)
        assertEquals(0, jumped.cursor)
        assertEquals(advanced.history, jumped.history.drop(1))
        assertEquals(jumped.window().size, jumped.window().map { it.token }.toSet().size)
        var retraced = jumped
        for (visit in advanced.history) {
            assertEquals(visit, retraced.next)
            retraced = retraced.arrive(visit.token, ids)
        }
        assertEquals("B", retraced.next!!.entryId)
    }

    @Test fun shuffleEnablePredecessorsBecomeHistoryOnlyOnArrivalThenRetraceAndExhaust() {
        val ids = listOf("A", "B", "C", "D", "E")
        var path = QueueJourney.start(ids, "D", JourneyModes()).reconcile(ids, "D", JourneyModes(shuffle = true), Random(12))
        val anchor = path.current!!
        val pending = path.window().single { it.entryId == "E" }
        assertEquals(listOf(anchor), path.history)
        for (id in listOf("C", "B", "A")) {
            assertEquals(id, path.previous!!.entryId)
            val previous = path.previous!!
            assertNotEquals(path.next?.token, previous.token)
            path = path.arrive(previous.token, ids)
            assertEquals(previous, path.current)
        }
        assertNull(path.previous)
        for (id in listOf("B", "C", "D")) {
            assertEquals(id, path.next!!.entryId)
            path = path.arrive(path.next!!.token, ids)
        }
        assertEquals(anchor, path.current)
        assertEquals(pending, path.next)
        assertEquals("E", path.next!!.entryId)
        path = path.arrive(path.next!!.token, ids)
        assertNull(path.next)
    }

    @Test fun nativeReceiptSurvivesAWindowDisplacingBatchButRejectsStaleNamespaces() {
        val ids = listOf("A", "B", "C")
        val path = QueueJourney.start(ids, "A", JourneyModes(true, true), Random(2))
        val receipt = path.next!!
        val added = List(100) { "new:$it" }
        val updated = ids + added
        val requested = path.playNext(added, updated)
        assertTrue(requested.window().none { it.token == receipt.token })
        assertSame(requested, requested.arrive(receipt.token, updated))
        val arrived = requested.arrive(receipt.token, updated, receipt = receipt)
        assertEquals(receipt, arrived.current)
        assertEquals(requested.history.drop(1), arrived.history.drop(arrived.cursor + 1))
        val restarted = requested.restart(updated, "A")
        assertSame(restarted, restarted.arrive(receipt.token, updated, receipt = receipt))
    }
}

package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QueueTraversalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun queue(size: Int, current: Int, modes: JourneyModes): ListeningQueue {
        val namespace = java.util.UUID.randomUUID().toString()
        val entries = List(size) { QueueEntry(id = "$namespace:occurrence:$it", track = Track("song:${it % 11}", "Song ${it % 11}",
            fileName = "song.mp3", relativePath = "song.mp3")) }
        return ListeningQueue(id = namespace, name = "Traversal", entries = entries, currentEntryId = entries[current].id,
            journey = QueueJourney.start(entries.map { it.id }, entries[current].id, modes, kotlin.random.Random(31)))
    }

    private fun compare(queue: ListeningQueue) {
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            runBlocking { room.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            val db = room.openHelper.writableDatabase
            QueueSequence.createSchema(db)
            QueueTraversal.createSchema(db)
            db.beginTransaction()
            try {
                val sequence = QueueSequence(db)
                QueueSequenceMigration(sequence).import(queue.id)
                val traversal = QueueTraversal(sequence)
                val window = traversal.restore(queue.id, queue.currentEntryId, requireNotNull(queue.journey).modes)
                assertEquals(queue.journey!!.window(), window.visits)
                assertEquals(queue.journey!!.current, window.current)
                assertEquals(queue.journey!!.previous, window.previous)
                assertEquals(queue.journey!!.next, window.next)
                assertTrue(window.visits.size <= QueuePlaybackWindow.MaximumVisits)
                assertEquals(window.visits.map { it.entryId }.toSet(), sequence.entries(queue.id, window.positions.keys).map { it.id }.toSet())
                assertEquals(window, traversal.window(queue.id))
                assertEquals(window, traversal.restore(queue.id, queue.currentEntryId, queue.journey!!.modes))
                db.query("SELECT COUNT(*) FROM queue_sequence_plan WHERE queueId = ? AND cycle = ?",
                    arrayOf(queue.id, QueueJourney.BEHIND_START)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                val behind = queue.journey!!.plans[QueueJourney.BEHIND_START].orEmpty()
                db.query("SELECT entryId,queuePosition FROM queue_sequence_behind WHERE queueId = ?",
                    arrayOf(queue.id)).use {
                    assertEquals(behind.isNotEmpty(), it.moveToFirst())
                    if (behind.isNotEmpty()) {
                        assertEquals(behind[0], it.getString(0))
                        assertEquals(behind.getOrNull(1)?.toIntOrNull(), if (it.isNull(1)) null else it.getInt(1))
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { room.close() }
    }

    @Test fun orderedProjectionMatchesTheExistingTokensAcrossBothLoopBoundaries() {
        for (size in listOf(1, 7, 2103)) for (index in setOf(0, size / 2, size - 1)) {
            compare(queue(size, index, JourneyModes(loop = true)))
        }
    }

    @Test fun orderedProjectionMatchesTheExistingNonLoopingEndpoints() {
        for (index in listOf(0, 1050, 2102)) compare(queue(2103, index, JourneyModes()))
    }

    @Test fun shuffledProjectionPreservesRewoundHistoryConcreteFutureAndScheduledVisits() {
        var queue = queue(2103, 0, JourneyModes(shuffle = true, loop = true, reshuffle = true))
        val ids = queue.entries.map { it.id }
        var journey = queue.journey!!
        for (index in listOf(17, 41, 999, 1001)) journey = journey.select(ids[index], ids)
        journey = journey.arrive(journey.history[1].token, ids)
        queue = queue.copy(currentEntryId = journey.current!!.entryId, journey = journey)
        compare(queue)
        val inserted = QueueEdits.insert(queue, listOf(queue.entries[7].track, queue.entries[9].track), SongTap.PlayNext)
        val added = inserted.entries.filter { it.id !in ids }.map { it.id }
        val scheduled = journey.playNext(added, inserted.entries.map { it.id })
        compare(inserted.copy(journey = scheduled))
    }

    @Test fun shuffledBehindNeighborMatchesTheExactLegacyWindowAtEveryBoundary() {
        for (loop in listOf(false, true)) for (current in listOf(0, 170)) {
            val queue = queue(300, current, JourneyModes(loop = loop))
            val ids = queue.entries.map { it.id }
            val modes = JourneyModes(shuffle = true, loop = loop)
            var journey = queue.journey!!.reconcile(ids, queue.currentEntryId, modes, kotlin.random.Random(43))
            for (cursor in 0..100) {
                if (cursor in setOf(0, 1, 63, 64, 65, 66, 100)) {
                    compare(queue.copy(currentEntryId = journey.current!!.entryId, journey = journey))
                }
                journey = journey.arrive(journey.next!!.token, ids)
            }
        }
    }

    @Test fun shuffledBehindIndexNeverBecomesAnOccurrenceEvenWhenAnIdIsNumeric() {
        val entries = List(20) { QueueEntry(id = it.toString(), track = Track("song:$it", "Song $it",
            fileName = "song.mp3", relativePath = "song.mp3")) }
        // Use nonnumeric IDs for the anchor and neighbor, leaving "9" as a real but unrelated occurrence.
        val numbered = entries.mapIndexed { index, entry -> when (index) {
            0 -> entry.copy(id = "9")
            9, 10 -> entry.copy(id = "entry:$index")
            else -> entry
        } }
        val ids = numbered.map { it.id }
        val modes = JourneyModes(shuffle = true)
        val journey = QueueJourney.start(ids, ids[10], JourneyModes())
            .reconcile(ids, ids[10], modes, kotlin.random.Random(43))
        compare(ListeningQueue(id = "numeric", name = "Numeric IDs", entries = numbered,
            currentEntryId = ids[10], journey = journey))
    }

    @Test fun shuffledBehindProjectionSurvivesClosingRoomWithoutReimportingLegacyMetadata() {
        val name = "queue-behind-${java.util.UUID.randomUUID()}.db"
        var room = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        try {
            val queue = queue(300, 170, JourneyModes(loop = true))
            val ids = queue.entries.map { it.id }
            val journey = queue.journey!!.reconcile(ids, queue.currentEntryId,
                JourneyModes(shuffle = true, loop = true), kotlin.random.Random(43))
            val saved = queue.copy(journey = journey, shuffle = true)
            runBlocking {
                room.queues().save(QueueLibrary(), QueueLibrary(listOf(saved), saved.id))
                val first = QueueWindowStore(room).restore(QueueSettings()).active!!.playbackWindow!!
                assertEquals(journey.window(), first.visits)
                room.openHelper.writableDatabase.execSQL("DELETE FROM queue_cycle_members WHERE queueId = ? AND cycle = ?",
                    arrayOf(queue.id, QueueJourney.BEHIND_START))
                room.close()
                room = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
                val restored = QueueWindowStore(room).restore(QueueSettings()).active!!.playbackWindow!!
                assertEquals(first, restored)
                assertEquals(journey.previous, restored.previous)
            }
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun shuffledBehindProjectionUsesTheLiveOrderAfterEverySequenceEdit() {
        for (loop in listOf(false, true)) for (current in listOf(0, 170)) for (cursor in listOf(0, 64)) {
            val queue = queue(300, current, JourneyModes(loop = loop))
            val ids = queue.entries.map { it.id }
            val modes = JourneyModes(shuffle = true, loop = loop)
            var journey = queue.journey!!.reconcile(ids, queue.currentEntryId, modes, kotlin.random.Random(43))
            // Concrete forward history isolates this projection oracle from the still-unimplemented plan edit seam.
            val neighbor = if (current > 0) ids[current - 1] else if (loop) ids.last() else null
            // An empty reserved plan has no legacy rows and therefore no persisted marker on reopen.
            if (neighbor == null) journey = journey.copy(plans = journey.plans - QueueJourney.BEHIND_START)
            ids.filter { it != queue.currentEntryId && it != neighbor }.take(150).forEach {
                journey = journey.select(it, ids)
            }
            journey = journey.arrive(journey.history[cursor].token, ids)
            val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
            try {
                val saved = queue.copy(currentEntryId = journey.current!!.entryId, journey = journey)
                runBlocking { room.queues().save(QueueLibrary(), QueueLibrary(listOf(saved), saved.id)) }
                val db = room.openHelper.writableDatabase
                QueueSequence.createSchema(db); QueueTraversal.createSchema(db)
                db.beginTransaction()
                try {
                    val sequence = QueueSequence(db)
                    QueueSequenceMigration(sequence).import(queue.id)
                    val traversal = QueueTraversal(sequence)
                    assertEquals(journey.window(), traversal.restore(queue.id, saved.currentEntryId, modes).visits)
                    fun verifyEdit() {
                        val live = sequence.page(queue.id, 0, QueueSequence.PageSize).entries.map { it.entry.id } +
                            sequence.page(queue.id, QueueSequence.PageSize, QueueSequence.PageSize).entries.map { it.entry.id }
                        journey = journey.reconcile(live, saved.currentEntryId, modes, kotlin.random.Random(43))
                        val window = traversal.window(queue.id)
                        assertEquals(journey.window(), window.visits)
                        assertEquals(journey.previous, window.previous)
                        assertTrue(window.visits.size <= QueuePlaybackWindow.MaximumVisits)
                    }
                    if (neighbor != null) {
                        sequence.move(queue.id, neighbor, ids[1])
                        verifyEdit()
                        sequence.remove(queue.id, neighbor)
                        verifyEdit()
                    }
                    sequence.insert(queue.id, requireNotNull(sequence.position(queue.id, ids[current])),
                        listOf(QueueEntry(id = "inserted", track = queue.entries.first().track)))
                    verifyEdit()
                    sequence.reverse(queue.id)
                    verifyEdit()
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { room.close() }
        }
    }

    @Test fun shuffledBehindArrivalRecordsExactHistoryMembershipAndRetraceTokens() {
        for (loop in listOf(false, true)) for (current in listOf(0, 17)) for (advancedCycle in listOf(false, true)) {
            if (advancedCycle && !loop) continue
            val queue = queue(20, current, JourneyModes(loop = loop))
            val ids = queue.entries.map { it.id }
            val modes = JourneyModes(shuffle = true, loop = loop)
            var journey = queue.journey!!.reconcile(ids, queue.currentEntryId, modes, kotlin.random.Random(43))
            if (advancedCycle) {
                repeat(25) { journey = journey.arrive(journey.next!!.token, ids, kotlin.random.Random(43)) }
                journey = journey.arrive(journey.history.first().token, ids)
                assertTrue(journey.history.first().cycle < journey.cycle)
            }
            val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
            try {
                val saved = queue.copy(journey = journey, currentEntryId = journey.current!!.entryId)
                runBlocking { room.queues().save(QueueLibrary(), QueueLibrary(listOf(saved), saved.id)) }
                val db = room.openHelper.writableDatabase
                QueueSequence.createSchema(db); QueueTraversal.createSchema(db)
                db.beginTransaction()
                try {
                    val sequence = QueueSequence(db)
                    QueueSequenceMigration(sequence).import(queue.id)
                    val traversal = QueueTraversal(sequence)
                    var window = traversal.restore(queue.id, saved.currentEntryId, modes)
                    assertEquals(journey.window(), window.visits)
                    repeat(25) {
                        val previous = journey.previous
                        if (previous == null) {
                            assertNull(traversal.arriveBehind(queue.id, "missing"))
                        } else {
                            val originalAnchor = journey.current
                            val retainedHistory = journey.history
                            journey = journey.arrive(previous.token, ids)
                            window = requireNotNull(traversal.arriveBehind(queue.id, previous.token))
                            assertEquals(listOf(previous) + retainedHistory, journey.history)
                            assertEquals(journey.window(), window.visits)
                            assertEquals(journey.current, window.current)
                            assertEquals(originalAnchor, window.next)
                            assertEquals(journey.next, window.next)
                            assertTrue(window.visits.size <= QueuePlaybackWindow.MaximumVisits)
                            assertNull(traversal.arriveBehind(queue.id, previous.token))
                        }
                        db.query("SELECT entryId,cycle,token FROM queue_sequence_history WHERE queueId = ? ORDER BY position",
                            arrayOf(queue.id)).use { rows ->
                            val actual = buildList { while (rows.moveToNext()) add(QueueVisit(rows.getString(0), rows.getLong(1), rows.getString(2))) }
                            assertEquals(journey.history, actual)
                        }
                        db.query("SELECT entryId FROM queue_sequence_plan WHERE queueId = ? AND cycle = ? AND visited = 1",
                            arrayOf(queue.id, journey.cycle)).use { rows ->
                            val visited = buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
                            assertEquals(journey.visited, visited)
                        }
                        assertEquals(window, traversal.window(queue.id))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { room.close() }
        }
    }

    @Test fun shuffledBehindArrivalRollsBackHistoryCursorAndMembershipTogether() {
        val queue = queue(20, 17, JourneyModes(loop = true))
        val ids = queue.entries.map { it.id }
        val journey = queue.journey!!.reconcile(ids, queue.currentEntryId,
            JourneyModes(shuffle = true, loop = true), kotlin.random.Random(43))
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val before = runBlocking {
                val saved = queue.copy(journey = journey)
                room.queues().save(QueueLibrary(), QueueLibrary(listOf(saved), saved.id))
                QueueWindowStore(room).restore(QueueSettings()).active!!.playbackWindow!!
            }
            val db = room.openHelper.writableDatabase
            db.beginTransaction()
            try {
                val traversal = QueueTraversal(QueueSequence(db))
                assertNotNull(traversal.arriveBehind(queue.id, before.previous!!.token))
                assertNotEquals(before, traversal.window(queue.id))
                // No successful marker: emulate a failure before the owning transaction commits.
            } finally { db.endTransaction() }
            db.beginTransaction()
            try {
                assertEquals(before, QueueTraversal(QueueSequence(db)).window(queue.id))
                db.query("SELECT historySize,cursor FROM queue_sequence_traversal WHERE queueId = ?", arrayOf(queue.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(0, it.getInt(1))
                }
                db.query("SELECT COUNT(*) FROM queue_sequence_history WHERE queueId = ?", arrayOf(queue.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                db.query("SELECT entryId FROM queue_sequence_plan WHERE queueId = ? AND cycle = ? AND visited = 1",
                    arrayOf(queue.id, journey.cycle)).use { rows ->
                    assertEquals(journey.visited, buildSet { while (rows.moveToNext()) add(rows.getString(0)) })
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { room.close() }
    }

    @Test fun missingLegacyJourneyInitializesExactShuffleOrderWithBoundedProjection() {
        for (size in listOf(1, 13, 211)) for (loop in listOf(false, true)) for (reshuffle in listOf(false, true)) {
            val modes = JourneyModes(shuffle = true, loop = loop, reshuffle = reshuffle)
            val queue = queue(size, size / 2, modes).copy(journey = null)
            val reference = QueueJourney.start(queue.entries.map { it.id }, queue.currentEntryId, modes, kotlin.random.Random(31))
            val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
            try {
                runBlocking { room.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
                val db = room.openHelper.writableDatabase
                QueueSequence.createSchema(db); QueueTraversal.createSchema(db)
                db.beginTransaction()
                try {
                    val sequence = QueueSequence(db)
                    QueueSequenceMigration(sequence).import(queue.id)
                    val traversal = QueueTraversal(sequence)
                    val window = traversal.restore(queue.id, queue.currentEntryId, modes, kotlin.random.Random(31))
                    assertEquals(reference.window().map { it.entryId to it.cycle }, window.visits.map { it.entryId to it.cycle })
                    assertEquals(reference.next?.entryId, window.next?.entryId)
                    assertEquals(reference.previous?.entryId, window.previous?.entryId)
                    assertTrue(window.visits.size <= QueuePlaybackWindow.MaximumVisits)
                    assertEquals(window, traversal.restore(queue.id, queue.currentEntryId, modes, kotlin.random.Random(31)))
                    db.query("SELECT COUNT(*) FROM queue_sequence_history WHERE queueId = ?", arrayOf(queue.id)).use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { room.close() }
        }
    }

    @Test fun storeRestoresOnlyTheActiveWindowAndRetainsExactLogicalCountsAndCursors() {
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val first = queue(2103, 1050, JourneyModes())
            val second = queue(700, 400, JourneyModes())
            val saved = QueueLibrary(listOf(first, second), first.id)
            runBlocking {
                room.queues().save(QueueLibrary(), saved)
                val restored = QueueWindowStore(room).restore(QueueSettings())
                assertEquals(saved.activeId, restored.activeId)
                assertEquals(listOf(2103, 700), restored.queues.map { it.entryCount })
                assertEquals(first.currentEntryId, restored.active!!.currentEntryId)
                assertEquals(1050, restored.active!!.currentIndex)
                assertEquals(first.journey!!.window(), restored.active!!.playbackWindow!!.visits)
                assertTrue(restored.active!!.entries.size <= QueuePlaybackWindow.MaximumVisits)
                assertTrue(restored.queues[1].entries.isEmpty())
                assertEquals(second.currentEntryId, restored.queues[1].currentEntryId)
                assertEquals(400, restored.queues[1].currentIndex)
                val active = restored.active!!
                val token = active.playbackWindow!!.current!!.token
                val store = QueueWindowStore(room)
                val legacyOrder = room.openHelper.writableDatabase.query("SELECT orderState FROM queues WHERE id = ?",
                    arrayOf(first.id)).use { assertTrue(it.moveToFirst()); it.getString(0) }
                assertFalse(store.checkpoint(first.id, first.currentEntryId!!, "stale-token", 88))
                assertFalse(store.checkpoint(first.id, first.entries.first().id, token, 99))
                assertFalse(store.checkpoint(second.id, second.currentEntryId!!, token, 111))
                assertTrue(store.checkpoint(first.id, first.currentEntryId!!, token, 1234))
                val afterCheckpoint = store.restore(QueueSettings()).active!!
                assertEquals(1234L, afterCheckpoint.positionMs)
                assertEquals(active.storage, afterCheckpoint.storage)
                assertEquals(active.playbackWindow, afterCheckpoint.playbackWindow)
                room.openHelper.writableDatabase.query("SELECT orderState FROM queues WHERE id = ?",
                    arrayOf(first.id)).use { assertTrue(it.moveToFirst()); assertEquals(legacyOrder, it.getString(0)) }

            }
        } finally { room.close() }
    }
    @Test fun concurrentFirstWindowOperationsInstallSchemaOnceWithoutDeadlock() {
        val installations = java.util.concurrent.atomic.AtomicInteger()
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java)
            .setQueryCallback(androidx.room.RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("CREATE TABLE IF NOT EXISTS queue_sequence_state")) installations.incrementAndGet()
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val store = QueueWindowStore(room)
            runBlocking {
                withTimeout(10000) {
                    val first = async(Dispatchers.IO) { store.checkpoint("missing", "entry", "token", 1) }
                    val second = async(Dispatchers.IO) { store.checkpoint("missing", "entry", "token", 2) }
                    assertFalse(first.await())
                    assertFalse(second.await())
                }
            }
            assertEquals(1, installations.get())
        } finally { room.close() }
    }

}

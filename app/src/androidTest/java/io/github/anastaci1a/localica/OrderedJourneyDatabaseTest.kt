package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrderedJourneyDatabaseTest {
    private val ids = listOf("A", "B", "C", "D", "E", "F", "X")
    private val history = listOf(QueueVisit("A", 0, "actual-A"), QueueVisit("D", 0, "actual-D"), QueueVisit("X", 0, "explicit-X"))
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, QueueDatabase::class.java, name)
        .addMigrations(QueueDatabase.MIGRATION_6_7, QueueDatabase.MIGRATION_7_8).build()
    private fun durableFields(library: QueueLibrary) = library.copy(queues = library.queues.map { queue ->
        queue.copy(journey = queue.journey?.let { if (it.modes.shuffle) it else it.copy(plans = emptyMap()) })
    })

    /** Independently seed released SQL schemas, including generated suffix and branch-pruned membership. */
    private fun seed(name: String, version: Int, shuffle: Boolean): ListeningQueue {
        context.deleteDatabase(name)
        val track = Track("content://missing/same", "Duplicate", fileName = "same.mp3", relativePath = "same.mp3")
        val queue = ListeningQueue("legacy", "Preserved queue", 123, ids.map { QueueEntry(it, track, "origin-$it") }, "D", 2345,
            repeat = QueueRepeat.All, lastActivatedAt = 456, order = SavedOrder(ids.reversed()))
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.anastaci1a.localica.queue.QueueDatabase/$version.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("PRAGMA foreign_keys=ON")
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (at in 0 until indices.length()) old.execSQL(indices.getJSONObject(at).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO queues VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(queue.id, queue.name, queue.createdAt,
                queue.currentEntryId, queue.positionMs, 0, queue.repeat.name, 0, queue.lastActivatedAt, SavedOrderCodec.encode(queue.order)))
            queue.entries.forEachIndexed { index, entry -> old.execSQL("INSERT INTO queue_entries VALUES (?,?,?,?,?)",
                arrayOf<Any?>(entry.id, queue.id, index, TrackSnapshot.encode(entry.track), entry.sourcePlaylistId)) }
            old.execSQL("INSERT INTO queue_state VALUES (1,?)", arrayOf(queue.id))
            val columns = if (version == 6) "?,?,?,?,?,?,?" else "?,?,?,?,?,?,?,?,?"
            val header = arrayOf<Any?>(queue.id, history.size, 1, 0, if (shuffle) 1 else 0, 1, 0)
            old.execSQL("INSERT INTO queue_journeys VALUES ($columns)", if (version == 6) header else
                arrayOf(*header, if (shuffle) null else "ordered-namespace", if (shuffle) null else "actual-D"))
            val future = listOf("F", "C", "E").map { QueueVisit(it, 0, "legacy-future-$it") } +
                (1L..2L).flatMap { cycle -> ids.map { QueueVisit(it, cycle, "legacy-future-$cycle-$it") } }
            (history + future).forEachIndexed { ordinal, visit -> old.execSQL("INSERT INTO queue_visits VALUES (?,?,?,?,?)",
                arrayOf<Any?>(visit.token, queue.id, ordinal, visit.entryId, visit.cycle)) }
            for (cycle in 0L..2L) listOf("A", "D", "F", "B", "C", "E", "X").forEachIndexed { ordinal, id ->
                old.execSQL("INSERT INTO queue_cycle_members VALUES (?,?,?,?,?)",
                    arrayOf<Any?>(queue.id, cycle, id, ordinal, if (cycle == 0L && id in setOf("A", "B", "D")) 1 else 0))
            }
            old.version = version
        }
        return queue
    }

    @Test fun releasedSchemaSixPreservesRowsAndOrderedIdentitySurvivesRollbackRemovalAndReopen() = runBlocking {
        for (version in listOf(6, 7)) for (shuffle in listOf(false, true)) {
            val name = "journey-$version-$shuffle.db"
            val queue = seed(name, version, shuffle)
            var db = open(name)
            try {
                val restored = db.queues().read().active!!
                assertEquals(queue, restored.copy(journey = null))
                assertEquals(history, restored.journey!!.history)
                assertEquals(history[1], restored.journey.current)
                assertEquals(setOf("A", "B", "D"), restored.journey.visited)
                val rows = db.queues().readQueues().single()
                assertEquals(3, rows.visits.size)
                assertEquals(if (shuffle) 21 else 3, rows.cycleMembers.size)
                if (shuffle) { assertNotNull(restored.journey.orderedTraversalId); assertNull(restored.journey.orderedAnchorToken) }
                else assertEquals(mapOf(restored.journey.cycle to restored.ids), restored.journey.plans)
                val prepared = restored.journey.reconcile(restored.ids, "D", JourneyModes(shuffle, true))
                if (shuffle) {
                    assertEquals("X", prepared.next!!.entryId)
                    val next = prepared.arrive(prepared.next!!.token, restored.ids)
                    assertEquals("F", next.next!!.entryId)
                    assertTrue(next.next!!.token != "legacy-future-F")
                } else {
                    assertEquals("C", prepared.previous!!.entryId)
                    assertEquals("E", prepared.next!!.entryId)
                }
                var durable = QueueLibrary(listOf(restored.copy(journey = prepared)), restored.id)
                db.queues().save(QueueLibrary(listOf(restored), restored.id), durable)
                db.close(); db = open(name)
                val reopened = db.queues().read().active!!
                assertEquals(durableFields(durable), durableFields(QueueLibrary(listOf(reopened), reopened.id)))
                assertEquals(prepared.window(), reopened.journey!!.reconcile(reopened.ids, "D", prepared.modes).window())
                val visit = prepared.next!!
                val arrived = prepared.arrive(visit.token, restored.ids)
                val proposed = durable.update(durable.active!!.copy(journey = arrived, currentEntryId = visit.entryId, positionMs = 0))
                val rawBefore = db.queues().readQueues()
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_arrival BEFORE INSERT ON queue_visits BEGIN SELECT RAISE(ABORT, 'injected arrival'); END")
                // X was an explicit persisted request; use a fresh actual ordered neighbor to exercise the insert.
                val target = if (shuffle) arrived.arrive(arrived.next!!.token, restored.ids) else arrived
                val targetLibrary = proposed.update(proposed.active!!.copy(journey = target, currentEntryId = target.current!!.entryId))
                try { db.queues().save(durable, targetLibrary); fail("Arrival insert failure must roll back") }
                catch (_: android.database.SQLException) { }
                assertEquals(rawBefore, db.queues().readQueues())
                assertEquals(durableFields(durable), durableFields(db.queues().read()))
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_arrival")
                db.queues().save(durable, targetLibrary); durable = targetLibrary
                val expectedNext = target.next
                val kept = restored.ids - setOf("A", "D")
                val removed = durable.active!!.copy(entries = restored.entries.filter { it.id in kept },
                    order = restored.order.reconcile(kept), journey = target.reconcile(kept, target.current!!.entryId, target.modes))
                val next = durable.update(removed)
                db.queues().save(durable, next)
                db.close(); db = open(name)
                val final = db.queues().read()
                assertEquals(durableFields(next), durableFields(final))
                val reconciled = final.active!!.journey!!.reconcile(final.active!!.ids, removed.currentEntryId, target.modes)
                assertEquals(removed.journey!!.window(), reconciled.window())
                assertEquals(prepared.orderedTraversalId, reconciled.orderedTraversalId)
                assertEquals(target.current, reconciled.current)
                assertEquals(expectedNext, reconciled.next)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun migrationRejectsMalformedHistoryAndRollsBackEarlierCleanupBeforeRetry() = runBlocking {
        for (failure in listOf("cleanup", "size", "negative")) {
            val name = "journey-migration-failure-$failure.db"
            seed(name, 7, true)
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
                when (failure) {
                    "size" -> raw.execSQL("UPDATE queue_journeys SET historySize = 999")
                    "negative" -> raw.execSQL("INSERT INTO queue_visits VALUES ('invalid-negative', 'legacy', -1, 'F', 0)")
                    else -> {
                        raw.execSQL("CREATE TRIGGER fail_cleanup BEFORE DELETE ON queue_cycle_members BEGIN SELECT RAISE(ABORT, 'injected migration'); END")
                        raw.execSQL("UPDATE queue_journeys SET shuffle = 0")
                    }
                }
            }
            val failed = open(name)
            try { failed.queues().read(); fail("Invalid migration must fail atomically") }
            catch (_: Exception) { }
            finally { failed.close() }
            val startup = open(name)
            val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val controller = withContext(Dispatchers.Main) { QueueController(startup.queues(), QueuePreferences(context),
                    (context.applicationContext as LocalicaApplication).library, actor) }
                withTimeout(10000) { while (controller.state.value.loading) delay(10) }
                assertTrue(controller.state.value.library.queues.isEmpty())
                assertNull(controller.state.value.library.activeId)
                val rejected = CompletableDeferred<Boolean>()
                controller.act(emptyList(), SongTap.Append) { rejected.complete(it) }
                assertFalse(withTimeout(10000) { rejected.await() })
                assertEquals("Queues are unavailable. Reopen the app to retry restoring them.", controller.state.value.problem)
            } finally { actor.coroutineContext[Job]!!.cancelAndJoin(); startup.close() }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
                assertEquals(7, raw.version)
                raw.rawQuery("SELECT COUNT(*) FROM queue_visits", null).use { it.moveToFirst(); assertEquals(if (failure == "negative") 21 else 20, it.getInt(0)) }
                raw.rawQuery("SELECT orderedTraversalId FROM queue_journeys", null).use { it.moveToFirst(); assertTrue(it.isNull(0)) }
                when (failure) {
                    "size" -> raw.execSQL("UPDATE queue_journeys SET historySize = 3")
                    "negative" -> raw.execSQL("DELETE FROM queue_visits WHERE token = 'invalid-negative'")
                    else -> raw.execSQL("DROP TRIGGER fail_cleanup")
                }
            }
            val retry = open(name)
            try { assertEquals(history, retry.queues().read().active!!.journey!!.history) }
            finally { retry.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun negativeRepeatWrapAndSingleOccurrenceTokensSurviveFileReopen() = runBlocking {
        val name = "ordered-wrap.db"
        context.deleteDatabase(name)
        var db = open(name)
        try {
            val track = Track("missing", "Only", fileName = "only.mp3", relativePath = "only.mp3")
            val queue = QueueEdits.create(listOf(track), 0, "Single")
            val initial = QueueJourney.start(queue.ids, queue.ids.single(), JourneyModes(loop = true))
            val previous = initial.arrive(initial.previous!!.token, queue.ids)
            assertEquals(-1L, previous.current!!.cycle)
            assertEquals(initial.current, previous.next)
            val saved = QueueLibrary(listOf(queue.copy(journey = previous)), queue.id)
            db.queues().save(QueueLibrary(), saved)
            db.close(); db = open(name)
            val restored = db.queues().read().active!!
            val prepared = restored.journey!!.reconcile(restored.ids, restored.currentEntryId, previous.modes)
            assertEquals(previous.history, prepared.history)
            assertEquals(previous.window(), prepared.window())
            assertEquals(129, prepared.window().map { it.token }.toSet().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrderedJourneyDatabaseTest {
    @Test fun releasedSchemaSixPreservesRowsAndOrderedIdentitySurvivesRollbackRemovalAndReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "ordered-journey-migration.db"
        context.deleteDatabase(name)
        val track = Track("content://missing/same", "Duplicate", fileName = "same.mp3", relativePath = "same.mp3")
        val originalQueue = QueueEdits.create(List(7) { track }, 0, "Preserved queue")
        val ids = originalQueue.entries.map { it.id }
        var legacy = QueueJourney.start(ids, ids[0], JourneyModes(shuffle = true, loop = true))
        for (index in listOf(3, 1, 5)) legacy = legacy.select(ids[index], ids)
        legacy = legacy.arrive(legacy.history[1].token, ids).copy(modes = JourneyModes(loop = true))
        val queue = originalQueue.copy(currentEntryId = ids[3], positionMs = 2345, journey = legacy,
            order = SavedOrder(ids.reversed()), lastActivatedAt = 123456)
        val original = QueueLibrary(listOf(queue), queue.id)
        // Build the actual released schema, not a hand-maintained approximation of its constraints/defaults.
        val schema = JSONObject(instrumentation.context.assets.open("io.github.anastaci1a.localica.queue.QueueDatabase/6.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        assertEquals(6, schema.getInt("version"))
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
            old.execSQL("INSERT INTO queue_journeys VALUES (?,?,?,?,?,?,?)", arrayOf<Any?>(queue.id, legacy.history.size,
                legacy.cursor, legacy.cycle, 0, 1, 0))
            legacy.visitRecords(queue.id).forEach { old.execSQL("INSERT INTO queue_visits VALUES (?,?,?,?,?)",
                arrayOf<Any?>(it.token, it.queueId, it.ordinal, it.entryId, it.cycle)) }
            legacy.cycleRecords(queue.id).forEach { old.execSQL("INSERT INTO queue_cycle_members VALUES (?,?,?,?,?)",
                arrayOf<Any?>(it.queueId, it.cycle, it.entryId, it.ordinal, if (it.visited) 1 else 0)) }
            old.version = 6
        }
        fun open() = Room.databaseBuilder(context, QueueDatabase::class.java, name).addMigrations(QueueDatabase.MIGRATION_6_7).build()
        var db = open()
        try {
            assertEquals("Migration must preserve all released queue values and actual history", original, db.queues().read())
            assertNull(db.queues().read().active!!.journey!!.orderedTraversalId)
            val ordered = legacy.reconcile(ids, queue.currentEntryId, legacy.modes)
            var durable = original.update(queue.copy(journey = ordered))
            db.queues().save(original, durable)
            db.close(); db = open()
            assertEquals(durable, db.queues().read())
            assertEquals(ordered.window(), db.queues().read().active!!.journey!!.window())
            assertEquals(legacy.current, ordered.current)
            assertEquals(ids[2], ordered.previous!!.entryId)
            assertEquals(ids[4], ordered.next!!.entryId)

            val arrived = ordered.arrive(ordered.next!!.token, ids)
            val proposed = durable.update(durable.active!!.copy(currentEntryId = ids[4], positionMs = 0, journey = arrived))
            val rawBefore = db.queues().readQueues()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ordered_fail BEFORE INSERT ON queue_visits WHEN NEW.entryId = '${ids[4]}' BEGIN SELECT RAISE(ABORT, 'injected ordered visit'); END")
            try { db.queues().save(durable, proposed); fail("Injected visit write must roll back header, cursor and identity") }
            catch (_: android.database.SQLException) { }
            assertEquals(rawBefore, db.queues().readQueues())
            assertEquals(durable, db.queues().read())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER ordered_fail")
            db.queues().save(durable, proposed); durable = proposed

            val expectedNext = arrived.next
            val kept = ids.filterNot { it == ids[0] || it == ids[3] }
            val removed = durable.active!!.copy(entries = durable.active!!.entries.filter { it.id in kept },
                order = durable.active!!.order.reconcile(kept), journey = arrived.reconcile(kept, ids[4], arrived.modes))
            val next = durable.update(removed)
            db.queues().save(durable, next)
            db.close(); db = open()
            val reopened = db.queues().read()
            assertEquals(next, reopened)
            assertEquals(ordered.orderedTraversalId, reopened.active!!.journey!!.orderedTraversalId)
            assertEquals(expectedNext, reopened.active!!.journey!!.next)
            assertEquals(arrived.current, reopened.active!!.journey!!.current)
            assertEquals(listOf(track), reopened.active!!.entries.map { it.track }.distinct())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun negativeRepeatWrapAndSingleOccurrenceTokensSurviveFileReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ordered-wrap.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        try {
            val track = Track("missing", "Only", fileName = "only.mp3", relativePath = "only.mp3")
            val queue = QueueEdits.create(listOf(track), 0, "Single")
            val ids = queue.entries.map { it.id }
            val initial = QueueJourney.start(ids, ids.single(), JourneyModes(loop = true))
            val previous = initial.arrive(initial.previous!!.token, ids)
            assertEquals(-1L, previous.current!!.cycle)
            assertEquals(initial.current, previous.next)
            val saved = QueueLibrary(listOf(queue.copy(journey = previous)), queue.id)
            db.queues().save(QueueLibrary(), saved)
            db.close(); db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
            val restored = db.queues().read()
            assertEquals(saved, restored)
            assertEquals(previous.window(), restored.active!!.journey!!.window())
            assertEquals(129, previous.window().map { it.token }.toSet().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

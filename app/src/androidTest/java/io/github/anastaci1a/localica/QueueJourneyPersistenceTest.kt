package io.github.anastaci1a.localica

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class QueueJourneyPersistenceTest {
    @Test fun thirteenThousandOccurrencePlanReorderUpdatesRetainedRowsAndSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-large-journey-update.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val before = queue(13_000, loop = true)
        val after = reorderedPlans(before, loop = true)
        try {
            db.queues().save(QueueLibrary(), library(before))
            // Retained keys must take the batch UPDATE path, without even attempting duplicate INSERTs.
            for (table in listOf("queue_visits", "queue_cycle_members")) {
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_insert_$table BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'Retained row inserted'); END")
            }
            val started = SystemClock.elapsedRealtime()
            db.queues().save(library(before), library(after))
            Log.i("QueueScale", "entries=13000 action=synthetic-retained-plan-reorder-room-save elapsedMs=${SystemClock.elapsedRealtime() - started}")
            assertEquals(library(after), db.queues().read())
            db.close(); db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
            assertEquals(library(after), db.queues().read())
            assertEquals(13_000, after.entries.map { it.id }.toSet().size)
            assertEquals(before.journey!!.current!!.token, after.journey!!.current!!.token)
            assertEquals(before.journey.history, after.journey.history)
            assertEquals(before.journey.visited, after.journey.visited)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun missingRowRepairRollsBackAllBatchesAndRetriesFromTheSameDurableSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val before = queue(64, loop = false)
        val after = reorderedPlans(before, loop = true)
        try {
            db.queues().save(QueueLibrary(), library(before))
            val oldVisits = before.journey!!.visitRecords(before.id).associateBy { it.token }
            val oldMembers = before.journey.cycleRecords(before.id).associateBy { it.cycle to it.entryId }
            val missingVisit = after.journey!!.visitRecords(after.id).first { it.token in oldVisits && oldVisits[it.token] != it }
            val missingMember = after.journey.cycleRecords(after.id).first { it.cycle to it.entryId in oldMembers && oldMembers[it.cycle to it.entryId] != it }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DELETE FROM queue_visits WHERE token = ?", arrayOf(missingVisit.token))
            sql.execSQL("DELETE FROM queue_cycle_members WHERE queueId = ? AND cycle = ? AND entryId = ?",
                arrayOf<Any?>(before.id, missingMember.cycle, missingMember.entryId))
            val damaged = db.queues().read()
            sql.execSQL("CREATE TRIGGER fail_member_repair BEFORE INSERT ON queue_cycle_members BEGIN SELECT RAISE(ABORT, 'Injected member repair failure'); END")
            try { db.queues().save(library(before), library(after)); fail("Repair failure must roll back earlier updates and inserts") }
            catch (_: android.database.SQLException) { }
            assertEquals(damaged, db.queues().read())
            sql.execSQL("DROP TRIGGER fail_member_repair")
            // The prior durable snapshot is deliberately unchanged, just as QueueController retries it.
            db.queues().save(library(before), library(after))
            assertEquals(library(after), db.queues().read())
        } finally { db.close() }
    }

    @Test fun retainedUpdatesUseScalarCountsAndDirectBatchFailureRollsBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val measuring = AtomicBoolean()
        val countQueries = AtomicInteger()
        val visitUpdates = AtomicInteger()
        val memberUpdates = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (measuring.get()) when {
                    sql == "SELECT changes()" -> countQueries.incrementAndGet()
                    sql.startsWith("UPDATE OR ABORT `queue_visits`") -> visitUpdates.incrementAndGet()
                    sql.startsWith("UPDATE OR ABORT `queue_cycle_members`") -> memberUpdates.incrementAndGet()
                }
            }, Executor { it.run() }).build()
        val before = queue(64, loop = true)
        val after = reorderedPlans(before, loop = true)
        val oldVisits = before.journey!!.visitRecords(before.id).associateBy { it.token }
        val oldMembers = before.journey.cycleRecords(before.id).associateBy { it.cycle to it.entryId }
        val changedVisits = after.journey!!.visitRecords(after.id).count { oldVisits[it.token] != it }
        val changedMembers = after.journey.cycleRecords(after.id).filter { oldMembers[it.cycle to it.entryId] != it }
        try {
            db.queues().save(QueueLibrary(), library(before))
            val failOrdinal = changedMembers[changedMembers.size / 2].ordinal
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scalar_update BEFORE UPDATE ON queue_cycle_members WHEN NEW.ordinal = $failOrdinal BEGIN SELECT RAISE(ABORT, 'Injected scalar batch failure'); END")
            try { db.queues().save(library(before), library(after)); fail("A direct batch failure must roll back every earlier update") }
            catch (_: android.database.SQLException) { }
            assertEquals(library(before), db.queues().read())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_scalar_update")
            measuring.set(true)
            db.queues().save(library(before), library(after))
            measuring.set(false)
            assertEquals(changedVisits, visitUpdates.get())
            assertEquals(changedMembers.size, memberUpdates.get())
            // Queue metadata and the two unchanged Room upsert paths still read their scalar counts.
            assertEquals(3, countQueries.get())
            assertEquals(library(after), db.queues().read())
        } finally { db.close() }
    }

    private fun queue(size: Int, loop: Boolean, shuffle: Boolean = true): ListeningQueue {
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val seed = QueueEdits.create(List(size) { track }, 0, "Queue")
        val ids = seed.entries.map { it.id }
        var journey = QueueJourney.start(ids, ids.first(), JourneyModes(shuffle = shuffle, loop = loop))
        repeat(3) { journey = journey.arrive(journey.next!!.token, ids) }
        return seed.copy(currentEntryId = journey.current!!.entryId, journey = journey)
    }
    /** Storage fixture: retain every old key while changing concrete plan/visit ordinals.
     * Real OFF→ON deliberately replaces planned token identities and is covered separately. */
    private fun reorderedPlans(queue: ListeningQueue, loop: Boolean): ListeningQueue {
        val journey = queue.journey!!
        val ids = queue.entries.map { it.id }.reversed()
        val added = if (loop && !journey.modes.loop) (1L..2L).associateWith { ids } else emptyMap()
        return queue.copy(journey = journey.copy(
            future = journey.future.groupBy { it.cycle }.values.flatMap { it.reversed() } +
                added.flatMap { (cycle, order) -> order.map { QueueVisit(it, cycle) } },
            plans = journey.plans.mapValues { (_, order) -> order.reversed() } + added,
            modes = journey.modes.copy(loop = loop)))
    }
    private fun library(queue: ListeningQueue) = QueueLibrary(listOf(queue), queue.id)
}

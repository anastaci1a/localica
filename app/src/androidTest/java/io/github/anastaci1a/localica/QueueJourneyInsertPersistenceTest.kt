package io.github.anastaci1a.localica

import android.content.Context
import android.database.SQLException
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyInsertPersistenceTest {
    @Test fun mixedNewAndExistingRowsRepairAndRollBackAcrossBothTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "journey-insert-repair.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val target = library()
        val queue = target.active!!
        val visits = queue.journey!!.visitRecords(queue.id)
        val members = queue.journey.cycleRecords(queue.id)
        try {
            db.queues().save(QueueLibrary(), target)
            val expected = snapshot(db)
            val sql = db.openHelper.writableDatabase
            fun damage() = db.runInTransaction {
                sql.execSQL("DELETE FROM queue_visits")
                sql.execSQL("DELETE FROM queue_cycle_members")
                val visit = visits[1]
                sql.execSQL("INSERT INTO queue_visits(token,queueId,ordinal,entryId,cycle) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(visit.token, visit.queueId, 999, visit.entryId, -7))
                val member = members[1]
                sql.execSQL("INSERT INTO queue_cycle_members(queueId,cycle,entryId,ordinal,visited) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(member.queueId, member.cycle, member.entryId, 999, 1))
            }
            damage()
            val damaged = snapshot(db)
            // Both batches encounter a duplicate after a successful insert, then reuse the failed INSERT statement.
            sql.execSQL("CREATE TRIGGER fail_new_member BEFORE INSERT ON queue_cycle_members " +
                "WHEN NEW.entryId = '${members[10].entryId}' BEGIN SELECT RAISE(ABORT, 'injected new member failure'); END")
            try {
                db.queues().save(QueueLibrary(), target)
                fail("A second-table failure must roll back earlier inserts and uniqueness repairs")
            } catch (error: SQLException) {
                assertTrue(error.message.orEmpty().contains("injected new member failure"))
            } finally { sql.execSQL("DROP TRIGGER fail_new_member") }
            assertEquals(damaged, snapshot(db))
            db.queues().save(QueueLibrary(), target)
            assertEquals(expected, snapshot(db))

            damage()
            val invalidMembers = members.mapIndexed { index, row -> if (index == 10) row.copy(queueId = "missing-parent") else row }
            try {
                db.runInTransaction {
                    assertForeignKeys(sql)
                    val writer = QueueJourneyWriter(sql::compileStatement)
                    writer.upsertVisits(visits)
                    writer.upsertMembers(invalidMembers)
                }
                fail("Foreign-key failures must not become uniqueness repairs")
            } catch (error: SQLException) {
                assertTrue(error.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
            }
            assertEquals(damaged, snapshot(db))
            db.queues().save(QueueLibrary(), target)
            assertEquals(expected, snapshot(db))
            observeWriteForeignKeys(db)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun zeroRowRepairAndDuplicateInputKeysKeepPinnedUpsertSemantics() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val target = library()
        val queue = target.active!!
        try {
            db.queues().save(QueueLibrary(), target)
            val before = db.queues().readQueues().single()
            val visit = before.visits.first()
            val member = before.cycleMembers.first()
            val newVisit = visit.copy(token = "new-visit", ordinal = 999, cycle = 2)
            val newMember = member.copy(entryId = "new-member", ordinal = 999, visited = false)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER ignore_visit_repair BEFORE UPDATE ON queue_visits " +
                "WHEN OLD.token = '${visit.token}' BEGIN SELECT RAISE(IGNORE); END")
            sql.execSQL("CREATE TRIGGER ignore_member_repair BEFORE UPDATE ON queue_cycle_members " +
                "WHEN OLD.entryId = '${member.entryId}' BEGIN SELECT RAISE(IGNORE); END")
            db.runInTransaction {
                assertForeignKeys(sql)
                val writer = QueueJourneyWriter(sql::compileStatement)
                writer.upsertVisits(listOf(visit.copy(cycle = 99), newVisit, newVisit.copy(cycle = 3)))
                writer.upsertMembers(listOf(member.copy(ordinal = 99), newMember, newMember.copy(visited = true)))
            }
            val after = db.queues().readQueues().single()
            assertEquals(before.visits.associateBy { it.token } + (newVisit.token to newVisit.copy(cycle = 3)),
                after.visits.associateBy { it.token })
            assertEquals(before.cycleMembers.associateBy { it.cycle to it.entryId } +
                ((newMember.cycle to newMember.entryId) to newMember.copy(visited = true)),
                after.cycleMembers.associateBy { it.cycle to it.entryId })
            assertEquals(before.copy(visits = emptyList(), cycleMembers = emptyList()),
                after.copy(visits = emptyList(), cycleMembers = emptyList()))
            observeWriteForeignKeys(db)
        } finally { db.close() }
    }

    private fun library(): QueueLibrary {
        val track = Track("content://fixture", "Fixture", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val seed = QueueEdits.create(List(40) { track }, 10, "New-row repair")
        val queue = seed.copy(journey = QueueJourney.start(seed.entries.map { it.id }, seed.currentEntryId, JourneyModes()))
        return QueueLibrary(listOf(queue), queue.id)
    }

    private suspend fun snapshot(db: QueueDatabase) = Triple(db.queues().read(), db.queues().readActive(),
        db.queues().readQueues().map { stored -> stored.copy(entries = stored.entries.sortedBy { it.id },
            journeys = stored.journeys.sortedBy { it.queueId }, visits = stored.visits.sortedBy { it.token },
            cycleMembers = stored.cycleMembers.sortedWith(compareBy<CycleMemberRecord> { it.cycle }.thenBy { it.entryId })) })

    private fun observeWriteForeignKeys(db: QueueDatabase) {
        val sql = db.openHelper.writableDatabase
        val unbound = foreignKeys(sql)
        // An unbound WAL cursor can use a pooled read connection; writes own a transaction connection.
        db.runInTransaction {
            val write = foreignKeys(sql)
            Log.i("QueueScale", "foreignKeysProbe unbound=$unbound owningWrite=$write inTransaction=${sql.inTransaction()}")
            assertForeignKeys(sql)
        }
    }

    private fun assertForeignKeys(sql: SupportSQLiteDatabase) {
        assertTrue("Observe foreign keys on the owning write transaction", sql.inTransaction())
        assertEquals(1, foreignKeys(sql))
    }

    private fun foreignKeys(sql: SupportSQLiteDatabase): Int =
        sql.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); it.getInt(0) }
}

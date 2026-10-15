package io.github.anastaci1a.localica

import android.content.Context
import android.database.SQLException
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Reproduces the pinned fresh-insert strategy; timings isolate statement lifetime, not a complete save. */
class QueueInsertPreparationBenchmarkTest {
    private enum class Preparation { PerRow, Reused }
    private data class Work(var insertPrepares: Int = 0, var inserted: Int = 0, var repaired: Int = 0)
    private class InjectedFailure : RuntimeException()

    @Test fun identicalNewRowsComparePerRowAndReusedInsertPreparation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-insert-preparation.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val target = library(13_000, 6500)
        val queue = target.active!!
        val visits = queue.journey!!.visitRecords(queue.id)
        val members = queue.journey.cycleRecords(queue.id)
        assertEquals(6500, visits.size)
        assertEquals(13_000, members.size)
        try {
            db.queues().save(QueueLibrary(), target)
            val baseline = snapshot(db)
            val sql = db.openHelper.writableDatabase
            // Setup warms the same pages. Reset is outside timing; ABBA does not remove all cache/order effects.
            for ((sample, mode) in listOf(Preparation.PerRow, Preparation.Reused, Preparation.Reused, Preparation.PerRow).withIndex()) {
                clearRows(db, sql)
                val work = Work()
                val started = SystemClock.elapsedRealtime()
                db.runInTransaction { upsertRows(sql, mode, visits, members, work) }
                val elapsed = SystemClock.elapsedRealtime() - started
                assertEquals(19_500, work.inserted)
                assertEquals(0, work.repaired)
                assertEquals(if (mode == Preparation.PerRow) 19_500 else 2, work.insertPrepares)
                assertEquals(baseline, snapshot(db))
                db.runInTransaction { assertForeignKeys(sql) }
                Log.i("QueueScale", "microbenchmark=insert-preparation entries=13000 rows=19500 mode=$mode sample=$sample insertPrepares=${work.insertPrepares} transactionMs=$elapsed warmedPages=true")
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun bothPreparationModesMatchDaoRepairAndRollBackBothTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-insert-repair.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val target = library(40, 10)
        val queue = target.active!!
        val visits = queue.journey!!.visitRecords(queue.id)
        val members = queue.journey.cycleRecords(queue.id)
        try {
            db.queues().save(QueueLibrary(), target)
            val baseline = snapshot(db)
            val sql = db.openHelper.writableDatabase
            fun damagedRows() {
                clearRows(db, sql)
                db.runInTransaction {
                    upsertRows(sql, Preparation.PerRow, listOf(visits.first().copy(cycle = -7)),
                        listOf(members.first().copy(ordinal = 999, visited = true)), Work())
                }
            }
            for (mode in Preparation.entries) {
                damagedRows()
                val damaged = snapshot(db)
                val failed = Work()
                try {
                    db.runInTransaction {
                        upsertRows(sql, mode, visits, members, failed) { index ->
                            if (index == visits.size + 10) throw InjectedFailure()
                        }
                    }
                    fail("Failure in the second table must roll back the first table and both repaired rows")
                } catch (_: InjectedFailure) { }
                assertEquals(2, failed.repaired)
                assertEquals(damaged, snapshot(db))
                val retried = Work()
                db.runInTransaction { upsertRows(sql, mode, visits, members, retried) }
                assertEquals(2, retried.repaired)
                assertEquals(baseline, snapshot(db))

                damagedRows()
                val nonUnique = Work()
                val invalid = members.mapIndexed { index, row -> if (index == 10) row.copy(queueId = "missing-parent") else row }
                try {
                    db.runInTransaction { upsertRows(sql, mode, visits, invalid, nonUnique) }
                    fail("A foreign-key failure must propagate instead of being treated as duplicate repair")
                } catch (error: SQLException) {
                    assertTrue(error.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
                }
                assertEquals(2, nonUnique.repaired)
                assertEquals(damaged, snapshot(db))
                db.runInTransaction { upsertRows(sql, mode, visits, members, Work()) }
                assertEquals(baseline, snapshot(db))
                db.runInTransaction { assertForeignKeys(sql) }
            }
            // Existing keys are not excluded merely because the caller's before-snapshot is empty.
            damagedRows()
            db.queues().save(QueueLibrary(), target)
            assertEquals(baseline, snapshot(db))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun library(count: Int, selected: Int): QueueLibrary {
        val track = Track("content://fixture", "Fixture", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val seed = QueueEdits.create(List(count) { track }, selected, "Insert diagnostic")
        val queue = seed.copy(journey = QueueJourney.start(seed.entries.map { it.id }, seed.currentEntryId, JourneyModes()))
        return QueueLibrary(listOf(queue), queue.id)
    }

    private suspend fun snapshot(db: QueueDatabase) = Triple(db.queues().read(), db.queues().readActive(),
        db.queues().readQueues().map { stored -> stored.copy(entries = stored.entries.sortedBy { it.id },
            journeys = stored.journeys.sortedBy { it.queueId }, visits = stored.visits.sortedBy { it.token },
            cycleMembers = stored.cycleMembers.sortedWith(compareBy<CycleMemberRecord> { it.cycle }.thenBy { it.entryId })) })

    private fun clearRows(db: QueueDatabase, sql: SupportSQLiteDatabase) = db.runInTransaction {
        sql.execSQL("DELETE FROM queue_visits")
        sql.execSQL("DELETE FROM queue_cycle_members")
    }

    private fun assertForeignKeys(sql: SupportSQLiteDatabase) {
        assertTrue("Observe foreign keys on the owning write transaction", sql.inTransaction())
        sql.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }

    private fun upsertRows(sql: SupportSQLiteDatabase, mode: Preparation, visits: List<VisitRecord>,
        members: List<CycleMemberRecord>, work: Work, afterRow: (Int) -> Unit = {}) {
        var index = 0
        fun <T> batch(rows: List<T>, insertSql: String, updateSql: String,
            bindInsert: (SupportSQLiteStatement, T) -> Unit, bindUpdate: (SupportSQLiteStatement, T) -> Unit) {
            fun prepare(): SupportSQLiteStatement { work.insertPrepares++; return sql.compileStatement(insertSql) }
            fun write(statement: SupportSQLiteStatement, row: T) {
                bindInsert(statement, row)
                try { statement.execute(); work.inserted++ } catch (error: SQLException) {
                    // Exact uniqueness-only predicate from pinned Room 2.7.2 EntityUpsertAdapter.
                    val message = error.message ?: throw error
                    if (!message.contains("unique", ignoreCase = true) && !message.contains("2067") && !message.contains("1555")) throw error
                    sql.compileStatement(updateSql).use { update ->
                        bindUpdate(update, row)
                        update.execute()
                        // Both modes retain the same pinned repair count path; repair is outside timed samples.
                        sql.query("SELECT changes()").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                    }
                    work.repaired++
                }
                afterRow(index++)
            }
            if (rows.isEmpty()) return
            if (mode == Preparation.Reused) prepare().use { statement ->
                rows.forEach { write(statement, it); statement.clearBindings() }
            } else rows.forEach { row -> prepare().use { write(it, row) } }
        }
        batch(visits, "INSERT OR ABORT INTO queue_visits(token,queueId,ordinal,entryId,cycle) VALUES(?,?,?,?,?)",
            "UPDATE OR ABORT queue_visits SET token=?,queueId=?,ordinal=?,entryId=?,cycle=? WHERE token=?",
            { statement, row -> bindVisit(statement, row) },
            { statement, row -> bindVisit(statement, row); statement.bindString(6, row.token) })
        batch(members, "INSERT OR ABORT INTO queue_cycle_members(queueId,cycle,entryId,ordinal,visited) VALUES(?,?,?,?,?)",
            "UPDATE OR ABORT queue_cycle_members SET queueId=?,cycle=?,entryId=?,ordinal=?,visited=? WHERE queueId=? AND cycle=? AND entryId=?",
            { statement, row -> bindMember(statement, row) },
            { statement, row -> bindMember(statement, row); statement.bindString(6, row.queueId)
                statement.bindLong(7, row.cycle); statement.bindString(8, row.entryId) })
    }

    private fun bindVisit(statement: SupportSQLiteStatement, row: VisitRecord) {
        statement.bindString(1, row.token); statement.bindString(2, row.queueId)
        statement.bindLong(3, row.ordinal.toLong()); statement.bindString(4, row.entryId); statement.bindLong(5, row.cycle)
    }

    private fun bindMember(statement: SupportSQLiteStatement, row: CycleMemberRecord) {
        statement.bindString(1, row.queueId); statement.bindLong(2, row.cycle); statement.bindString(3, row.entryId)
        statement.bindLong(4, row.ordinal.toLong()); statement.bindLong(5, if (row.visited) 1L else 0L)
    }
}

package io.github.anastaci1a.localica

import android.content.Context
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
import kotlin.random.Random

/** Isolates row-count retrieval, not the controller or a complete save, on the real Android SQLite adapter. */
class QueueSqlCountBenchmarkTest {
    private enum class Count { Cursor, Statement }
    @Test fun identicalPreparedUpdatesCompareCursorAndStatementCountsWithRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-sql-count-diagnostic.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val track = Track("content://fixture", "Fixture", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val seed = QueueEdits.create(List(1000) { track }, 0, "Count diagnostic")
        val ids = seed.entries.map { it.id }
        var before = QueueJourney.start(ids, ids.first(), JourneyModes(loop = true))
        repeat(3) { before = before.arrive(before.next!!.token, ids) }
        val after = before.reconcile(ids, before.current!!.entryId, JourneyModes(shuffle = true, loop = true), Random(19))
        val queue = seed.copy(journey = before, currentEntryId = before.current!!.entryId)
        val oldVisits = before.visitRecords(queue.id).associateBy { it.token }
        val newVisits = after.visitRecords(queue.id).associateBy { it.token }
        val oldMembers = before.cycleRecords(queue.id).associateBy { it.cycle to it.entryId }
        val newMembers = after.cycleRecords(queue.id).associateBy { it.cycle to it.entryId }
        val forwardVisits = newVisits.values.filter { oldVisits[it.token] != it }
        val forwardMembers = newMembers.values.filter { oldMembers[it.cycle to it.entryId] != it }
        val reverseVisits = forwardVisits.map { oldVisits.getValue(it.token) }
        val reverseMembers = forwardMembers.map { oldMembers.getValue(it.cycle to it.entryId) }
        val expected = forwardVisits.size + forwardMembers.size
        assertEquals(5991, expected)
        suspend fun assertRows(journey: QueueJourney) {
            val stored = db.queues().read().active!!.journey!!
            assertEquals(journey.visitRecords(queue.id), stored.visitRecords(queue.id))
            assertEquals(journey.cycleRecords(queue.id), stored.cycleRecords(queue.id))
            // Only the typed rows under comparison change; queue/journey metadata remains untouched.
            assertEquals(before.record(queue.id), stored.record(queue.id))
        }
        try {
            db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
            val sql = db.openHelper.writableDatabase
            // Both modes run in both directions, so warmed pages / direction cannot explain a consistent difference.
            for ((index, mode) in listOf(Count.Cursor, Count.Statement, Count.Statement, Count.Cursor).withIndex()) {
                val forward = index % 2 == 0
                val visits = if (forward) forwardVisits else reverseVisits
                val members = if (forward) forwardMembers else reverseMembers
                val started = SystemClock.elapsedRealtime()
                db.runInTransaction { assertEquals(expected, updateRows(sql, mode, visits, members)) }
                val elapsed = SystemClock.elapsedRealtime() - started
                assertRows(if (forward) after else before)
                Log.i("QueueScale", "microbenchmark=count-retrieval entries=1000 rows=$expected mode=$mode forward=$forward transactionMs=$elapsed")
            }
            for (mode in Count.entries) {
                try {
                    db.runInTransaction {
                        sql.execSQL("DELETE FROM queue_visits WHERE token = ?", arrayOf(forwardVisits.first().token))
                        updateRows(sql, mode, forwardVisits, forwardMembers) { index, count ->
                            assertEquals(if (index == 0) 0 else 1, count)
                            if (index == 30) throw InjectedFailure()
                        }
                    }
                    fail("Injected failure must roll back updates and the missing-row setup")
                } catch (_: InjectedFailure) { }
                assertRows(before)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private class InjectedFailure : RuntimeException()
    private fun updateRows(db: SupportSQLiteDatabase, mode: Count, visits: List<VisitRecord>,
        members: List<CycleMemberRecord>, afterRow: (Int, Int) -> Unit = { _, _ -> }): Int {
        var rows = 0
        var index = 0
        fun changed(statement: SupportSQLiteStatement) {
            val count = if (mode == Count.Statement) statement.executeUpdateDelete() else {
                // Mirrors Room 2.7.2's compatibility driver + EntityDeleteOrUpdateAdapter count path.
                statement.execute()
                db.query("SELECT changes()").use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
            }
            rows += count
            afterRow(index++, count)
        }
        db.compileStatement("UPDATE OR ABORT queue_visits SET token=?,queueId=?,ordinal=?,entryId=?,cycle=? WHERE token=?").use { statement ->
            for (row in visits) {
                statement.bindString(1, row.token); statement.bindString(2, row.queueId)
                statement.bindLong(3, row.ordinal.toLong()); statement.bindString(4, row.entryId)
                statement.bindLong(5, row.cycle); statement.bindString(6, row.token)
                changed(statement)
            }
        }
        db.compileStatement("UPDATE OR ABORT queue_cycle_members SET queueId=?,cycle=?,entryId=?,ordinal=?,visited=? WHERE queueId=? AND cycle=? AND entryId=?").use { statement ->
            for (row in members) {
                statement.bindString(1, row.queueId); statement.bindLong(2, row.cycle); statement.bindString(3, row.entryId)
                statement.bindLong(4, row.ordinal.toLong()); statement.bindLong(5, if (row.visited) 1L else 0L)
                statement.bindString(6, row.queueId); statement.bindLong(7, row.cycle); statement.bindString(8, row.entryId)
                changed(statement)
            }
        }
        return rows
    }
}

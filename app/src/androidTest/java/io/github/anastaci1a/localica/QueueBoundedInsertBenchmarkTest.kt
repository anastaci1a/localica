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
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** Test-only SQL comparison. Row payloads are prepared before timing; outer commit semantics are identical. */
class QueueBoundedInsertBenchmarkTest {
    private enum class Mode { Reused, MultiRow128 }
    private data class Work(var prepares: Int = 0, var inserts: Int = 0, var savepoints: Int = 0)

    @Test fun boundedJourneyAndStrictEntryBatchesCompareWithReusedStatements() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-bounded-inserts.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val target = library(13_000, 6500)
        try {
            db.queues().save(QueueLibrary(), target)
            val expected = snapshot(db)
            val stored = db.queues().readQueues().single()
            val sql = db.openHelper.writableDatabase
            // Warm pages, resets and exact state assertions are outside timing. ABBA is exploratory.
            for (journey in listOf(true, false)) {
                for ((sample, mode) in listOf(Mode.Reused, Mode.MultiRow128, Mode.MultiRow128, Mode.Reused).withIndex()) {
                    db.runInTransaction {
                        if (journey) clearJourney(sql) else sql.execSQL("DELETE FROM queue_entries")
                    }
                    val work = Work()
                    val at = SystemClock.elapsedRealtime()
                    db.runInTransaction {
                        if (journey) writeJourney(sql, mode, stored.visits, stored.cycleMembers, work)
                        else writeEntries(sql, mode, stored.entries, work)
                    }
                    val elapsed = SystemClock.elapsedRealtime() - at
                    assertEquals(expected, snapshot(db))
                    db.runInTransaction { assertForeignKeys(sql) }
                    val rows = if (journey) stored.visits.size + stored.cycleMembers.size else stored.entries.size
                    val batches = if (journey) chunks(stored.visits.size) + chunks(stored.cycleMembers.size) else chunks(rows)
                    assertEquals(if (mode == Mode.Reused) rows else batches, work.inserts)
                    assertEquals(if (mode == Mode.MultiRow128 && journey) batches * 2 else 0, work.savepoints)
                    Log.i("QueueScale", "microbenchmark=bounded-insert dataset=${if (journey) "journey-upsert" else "entries-strict"} rows=$rows mode=$mode sample=$sample transactionMs=$elapsed compileCalls=${work.prepares} insertCalls=${work.inserts} savepointStatements=${work.savepoints} maxParameters=640 warmedPages=true payloadEncoding=excluded")
                }
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun unexpectedKeysAndSecondTableFailuresMatchActualWriterAndDaoRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val target = library(260, 130)
        val queue = target.active!!
        val visits = queue.journey!!.visitRecords(queue.id)
        val members = queue.journey.cycleRecords(queue.id)
        try {
            db.queues().save(QueueLibrary(), target)
            val expected = snapshot(db)
            val sql = db.openHelper.writableDatabase
            fun damage() = db.runInTransaction {
                clearJourney(sql)
                writeJourney(sql, Mode.Reused, listOf(visits[1].copy(cycle = -7)),
                    listOf(members[1].copy(ordinal = 999, visited = true)), Work())
            }
            for (mode in Mode.entries) {
                damage()
                val damaged = snapshot(db)
                sql.execSQL("CREATE TRIGGER fail_later_member BEFORE INSERT ON queue_cycle_members " +
                    "WHEN NEW.entryId = '${members[200].entryId}' BEGIN SELECT RAISE(ABORT, 'injected later member'); END")
                try {
                    db.runInTransaction { assertForeignKeys(sql); writeJourney(sql, mode, visits, members, Work()) }
                    fail("Second-table failure must roll back earlier batches and repairs")
                } catch (error: SQLException) {
                    assertTrue(error.message.orEmpty().contains("injected later member"))
                } finally { sql.execSQL("DROP TRIGGER fail_later_member") }
                assertEquals(damaged, snapshot(db))
                db.runInTransaction { writeJourney(sql, mode, visits, members, Work()) }
                assertEquals(expected, snapshot(db))

                damage()
                val invalid = members.mapIndexed { i, row -> if (i == 200) row.copy(queueId = "missing-parent") else row }
                try {
                    db.runInTransaction { assertForeignKeys(sql); writeJourney(sql, mode, visits, invalid, Work()) }
                    fail("Non-uniqueness FK failure must propagate")
                } catch (error: SQLException) {
                    assertTrue(error.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
                }
                assertEquals(damaged, snapshot(db))
                db.queues().save(QueueLibrary(), target)
                assertEquals(expected, snapshot(db))
            }
        } finally { db.close() }
    }

    @Test fun strictEntryFailuresRollBackEarlierJourneyBatchesAndRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val target = library(260, 130)
        try {
            db.queues().save(QueueLibrary(), target)
            val expected = snapshot(db)
            val stored = db.queues().readQueues().single()
            val sql = db.openHelper.writableDatabase
            for (mode in Mode.entries) {
                for (duplicate in listOf(true, false)) {
                    db.runInTransaction { clearJourney(sql); sql.execSQL("DELETE FROM queue_entries") }
                    val emptyRows = snapshot(db)
                    val invalid = stored.entries.mapIndexed { index, row ->
                        if (index != 200) row else if (duplicate) row.copy(id = stored.entries[0].id)
                        else row.copy(queueId = "missing-parent")
                    }
                    try {
                        db.runInTransaction {
                            assertForeignKeys(sql)
                            writeJourney(sql, mode, stored.visits, stored.cycleMembers, Work())
                            writeEntries(sql, mode, invalid, Work())
                        }
                        fail("Strict entry INSERT must propagate constraint failure")
                    } catch (error: SQLException) {
                        if (duplicate) assertTrue(isJourneyUniquenessFailure(error.message))
                        else assertTrue(error.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
                    }
                    assertEquals(emptyRows, snapshot(db))
                    db.queues().save(QueueLibrary(), target)
                    assertEquals(expected, snapshot(db))
                }
            }
        } finally { db.close() }
    }

    @Test fun partialFailInSpeculativeBatchRollsBackBeforePerRowReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val target = library(260, 130)
        val queue = target.active!!
        val visits = queue.journey!!.visitRecords(queue.id)
        val members = queue.journey.cycleRecords(queue.id)
        try {
            db.queues().save(QueueLibrary(), target)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE diagnostic_effects(value INTEGER NOT NULL)")
            sql.execSQL("INSERT INTO diagnostic_effects VALUES(0)")
            sql.execSQL("CREATE TRIGGER partial_unique_failure BEFORE INSERT ON queue_visits " +
                "WHEN NEW.token = '${visits[80].token}' BEGIN UPDATE diagnostic_effects SET value=value+1; " +
                "SELECT RAISE(FAIL, 'unique diagnostic failure'); END")
            val results = mutableListOf<Any>()
            for (mode in Mode.entries) {
                val work = Work()
                db.runInTransaction {
                    clearJourney(sql)
                    sql.execSQL("UPDATE diagnostic_effects SET value=0")
                    assertForeignKeys(sql)
                    // No existing/duplicate keys precede the trigger: the speculative batch itself reaches FAIL.
                    writeJourney(sql, mode, visits, members, work)
                }
                val stored = db.queues().readQueues().single()
                assertEquals(visits.filterNot { it.token == visits[80].token }.toSet(), stored.visits.toSet())
                assertEquals(members.toSet(), stored.cycleMembers.toSet())
                sql.query("SELECT value FROM diagnostic_effects").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                assertEquals(if (mode == Mode.Reused) 0 else (chunks(visits.size) + chunks(members.size)) * 2 + 1, work.savepoints)
                results += snapshot(db)
            }
            assertEquals(results[0], results[1])
        } finally { db.close() }
    }

    @Test fun zeroRepairAndDuplicateInputsPreservePerRowEffects() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val target = library(260, 130)
        val queue = target.active!!
        val visits = queue.journey!!.visitRecords(queue.id)
        val members = queue.journey.cycleRecords(queue.id)
        try {
            db.queues().save(QueueLibrary(), target)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE diagnostic_effects(value INTEGER NOT NULL)")
            sql.execSQL("INSERT INTO diagnostic_effects VALUES(0)")
            // FAIL retains this statement's earlier effects. Speculative multi-row work must be undone before replay.
            sql.execSQL("CREATE TRIGGER partial_unique_failure BEFORE INSERT ON queue_visits " +
                "WHEN NEW.token = '${visits[80].token}' BEGIN UPDATE diagnostic_effects SET value=value+1; " +
                "SELECT RAISE(FAIL, 'unique diagnostic failure'); END")
            sql.execSQL("CREATE TRIGGER ignore_existing_visit BEFORE UPDATE ON queue_visits " +
                "WHEN OLD.token = '${visits[1].token}' BEGIN SELECT RAISE(IGNORE); END")
            sql.execSQL("CREATE TRIGGER ignore_existing_member BEFORE UPDATE ON queue_cycle_members " +
                "WHEN OLD.entryId = '${members[1].entryId}' BEGIN SELECT RAISE(IGNORE); END")
            val visitInput = visits.take(3) + visits[0].copy(cycle = 4) + visits.drop(3) + visits[0].copy(cycle = 9)
            val memberInput = members.take(3) + members[0].copy(ordinal = 444) + members.drop(3) + members[0].copy(ordinal = 999)
            val results = mutableListOf<Any>()
            for (mode in Mode.entries) {
                db.runInTransaction {
                    clearJourney(sql)
                    sql.execSQL("UPDATE diagnostic_effects SET value=0")
                    writeJourney(sql, Mode.Reused, listOf(visits[1].copy(cycle = -7)),
                        listOf(members[1].copy(ordinal = -7)), Work())
                    assertForeignKeys(sql)
                    writeJourney(sql, mode, visitInput, memberInput, Work())
                }
                val stored = db.queues().readQueues().single()
                assertFalse(stored.visits.any { it.token == visits[80].token }) // Pinned zero-row uniqueness repair.
                assertEquals(-7L, stored.visits.single { it.token == visits[1].token }.cycle)
                assertEquals(-7, stored.cycleMembers.single { it.entryId == members[1].entryId }.ordinal)
                assertEquals(9L, stored.visits.single { it.token == visits[0].token }.cycle)
                assertEquals(999, stored.cycleMembers.single { it.entryId == members[0].entryId }.ordinal)
                sql.query("SELECT value FROM diagnostic_effects").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                results += snapshot(db)
            }
            assertEquals(results[0], results[1])
        } finally { db.close() }
    }

    private fun writeJourney(sql: SupportSQLiteDatabase, mode: Mode, visits: List<VisitRecord>, members: List<CycleMemberRecord>, work: Work) {
        val writer = QueueJourneyWriter { compile(sql, it, work) }
        if (mode == Mode.Reused) { writer.upsertVisits(visits); writer.upsertMembers(members); return }
        bulk(sql, visits, "INSERT INTO queue_visits(token,queueId,ordinal,entryId,cycle)", work, ::bindVisit, writer::upsertVisits)
        bulk(sql, members, "INSERT INTO queue_cycle_members(queueId,cycle,entryId,ordinal,visited)", work, ::bindMember, writer::upsertMembers)
    }

    private fun writeEntries(sql: SupportSQLiteDatabase, mode: Mode, entries: List<EntryRecord>, work: Work) {
        val prefix = "INSERT OR ABORT INTO queue_entries(id,queueId,ordinal,track,sourcePlaylistId)"
        if (mode == Mode.MultiRow128) bulk(sql, entries, prefix, work, ::bindEntry)
        else compile(sql, "$prefix VALUES(?,?,?,?,?)", work).use { statement ->
            entries.forEach { bindEntry(statement, it, 0); statement.execute(); statement.clearBindings() }
        }
    }

    private fun <T> bulk(sql: SupportSQLiteDatabase, rows: List<T>, prefix: String, work: Work,
        bind: (SupportSQLiteStatement, T, Int) -> Unit, repair: ((List<T>) -> Unit)? = null) {
        fun control(command: String) { work.savepoints++; sql.execSQL(command) }
        Statements().use { statements ->
            for (batch in rows.chunked(128)) {
                check(batch.size * 5 <= 640)
                val statement = statements.byRows.getOrPut(batch.size) {
                    compile(sql, "$prefix VALUES" + List(batch.size) { "(?,?,?,?,?)" }.joinToString(","), work)
                }
                batch.forEachIndexed { index, row -> bind(statement, row, index * 5) }
                if (repair == null) statement.execute() else {
                    control("SAVEPOINT diagnostic_insert_batch")
                    try { statement.execute() } catch (error: Throwable) {
                        try {
                            control("ROLLBACK TO diagnostic_insert_batch")
                            control("RELEASE diagnostic_insert_batch")
                        } catch (cleanup: Throwable) { error.addSuppressed(cleanup); throw error }
                        if (error !is SQLException || !isJourneyUniquenessFailure(error.message)) throw error
                        repair(batch) // Replays original insert/immediate-repair order from the pre-batch SQL state.
                        statement.clearBindings()
                        continue
                    }
                    control("RELEASE diagnostic_insert_batch")
                }
                statement.clearBindings()
            }
        }
    }

    private class Statements : Closeable {
        val byRows = mutableMapOf<Int, SupportSQLiteStatement>()
        override fun close() {
            var failure: Throwable? = null
            byRows.values.forEach { statement ->
                try { statement.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun compile(sql: SupportSQLiteDatabase, query: String, work: Work): SupportSQLiteStatement {
        work.prepares++
        val real = sql.compileStatement(query)
        return object : SupportSQLiteStatement by real {
            override fun execute() { if (query.startsWith("INSERT")) work.inserts++; real.execute() }
        }
    }
    private fun chunks(rows: Int) = (rows + 127) / 128
    private fun bindVisit(s: SupportSQLiteStatement, r: VisitRecord, o: Int) {
        s.bindString(o+1,r.token); s.bindString(o+2,r.queueId); s.bindLong(o+3,r.ordinal.toLong()); s.bindString(o+4,r.entryId); s.bindLong(o+5,r.cycle)
    }
    private fun bindMember(s: SupportSQLiteStatement, r: CycleMemberRecord, o: Int) {
        s.bindString(o+1,r.queueId); s.bindLong(o+2,r.cycle); s.bindString(o+3,r.entryId); s.bindLong(o+4,r.ordinal.toLong()); s.bindLong(o+5,if(r.visited)1 else 0)
    }
    private fun bindEntry(s: SupportSQLiteStatement, r: EntryRecord, o: Int) {
        s.bindString(o+1,r.id); s.bindString(o+2,r.queueId); s.bindLong(o+3,r.ordinal.toLong()); s.bindString(o+4,r.track)
        r.sourcePlaylistId?.let { s.bindString(o+5,it) } ?: s.bindNull(o+5)
    }
    private fun clearJourney(sql: SupportSQLiteDatabase) {
        sql.execSQL("DELETE FROM queue_visits"); sql.execSQL("DELETE FROM queue_cycle_members")
    }
    private fun assertForeignKeys(sql: SupportSQLiteDatabase) {
        assertTrue(sql.inTransaction())
        sql.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
    }
    private fun library(count: Int, selected: Int): QueueLibrary {
        val tracks = List(count) { Track("content://fixture/$it", "Track $it", fileName="$it.mp3", relativePath="fixture/$it.mp3") }
        val seed = QueueEdits.create(tracks, selected, "Bounded INSERT diagnostic")
        val queue = seed.copy(entries = seed.entries.mapIndexed { index, entry ->
            entry.copy(sourcePlaylistId = if (index % 2 == 0) "fixture-playlist" else null)
        }, journey=QueueJourney.start(seed.entries.map { it.id }, seed.currentEntryId, JourneyModes()))
        return QueueLibrary(listOf(queue), queue.id)
    }
    private suspend fun snapshot(db: QueueDatabase) = Triple(db.queues().read(), db.queues().readActive(),
        db.queues().readQueues().map { s -> s.copy(entries=s.entries.sortedBy { it.id }, journeys=s.journeys.sortedBy { it.queueId },
            visits=s.visits.sortedBy { it.token }, cycleMembers=s.cycleMembers.sortedWith(compareBy<CycleMemberRecord> { it.cycle }.thenBy { it.entryId })) })
}

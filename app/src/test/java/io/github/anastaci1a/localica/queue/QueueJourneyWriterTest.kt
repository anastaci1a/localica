package io.github.anastaci1a.localica.queue

import androidx.sqlite.db.SupportSQLiteStatement
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyWriterTest {
    @Test fun largeBatchesReuseOneStatementAndBindEveryTypedColumn() {
        val statements = mutableListOf<Statement>()
        val writer = QueueJourneyWriter { sql -> Statement(sql).also { statements += it } }
        val visits = List(13_000) { VisitRecord("visit-$it", "queue", it, "entry-${it % 2}", it.toLong() - 2) }
        val members = List(13_000) { CycleMemberRecord("queue", it.toLong() - 2, "entry-${it % 2}", it, it % 2 == 0) }
        assertEquals(13_000, writer.updateVisits(visits))
        assertEquals(13_000, writer.updateMembers(members))
        assertEquals(2, statements.size)
        val visitStatement = statements[0]
        assertEquals("UPDATE OR ABORT `queue_visits` SET `token` = ?,`queueId` = ?,`ordinal` = ?,`entryId` = ?,`cycle` = ? WHERE `token` = ?", visitStatement.sql)
        assertEquals(visits.map { listOf(it.token, it.queueId, it.ordinal.toLong(), it.entryId, it.cycle, it.token) }, visitStatement.rows)
        val memberStatement = statements[1]
        assertEquals("UPDATE OR ABORT `queue_cycle_members` SET `queueId` = ?,`cycle` = ?,`entryId` = ?,`ordinal` = ?,`visited` = ? WHERE `queueId` = ? AND `cycle` = ? AND `entryId` = ?", memberStatement.sql)
        assertEquals(members.map { listOf(it.queueId, it.cycle, it.entryId, it.ordinal.toLong(), if (it.visited) 1L else 0L, it.queueId, it.cycle, it.entryId) }, memberStatement.rows)
        assertTrue(statements.all { it.closed })
    }

    @Test fun emptyBatchesDoNotPrepareStatementsAndMissingRowsReturnShortCount() {
        val statement = Statement("", counts = listOf(1, 0, 1))
        var prepared = 0
        val writer = QueueJourneyWriter { prepared++; statement }
        assertEquals(0, writer.updateVisits(emptyList()))
        assertEquals(0, writer.updateMembers(emptyList()))
        assertEquals(0, prepared)
        assertEquals(2, writer.updateVisits(visits()))
        assertEquals(1, prepared)
        assertTrue(statement.closed)
    }

    @Test fun impossibleCountsAndStatementFailuresCloseTheBatchBeforePropagating() {
        for (failure in listOf(-1, 2, null)) {
            val statement = Statement("", counts = failure?.let { listOf(1, it) }.orEmpty(), failAt = if (failure == null) 1 else null)
            val writer = QueueJourneyWriter { statement }
            try { writer.updateVisits(visits()); fail("The batch must fail") }
            catch (_: IllegalStateException) { }
            assertEquals(2, statement.rows.size)
            assertTrue(statement.closed)
        }
    }

    private fun visits() = List(3) { VisitRecord("v$it", "queue", it, "entry", 0) }
    private class Statement(val sql: String, val counts: List<Int> = emptyList(), val failAt: Int? = null) : SupportSQLiteStatement {
        val rows = mutableListOf<List<Any?>>()
        private val bindings = sortedMapOf<Int, Any?>()
        var closed = false
        override fun bindLong(index: Int, value: Long) { bindings[index] = value }
        override fun bindString(index: Int, value: String) { bindings[index] = value }
        override fun bindDouble(index: Int, value: Double) { bindings[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bindings[index] = value }
        override fun bindNull(index: Int) { bindings[index] = null }
        override fun clearBindings() { bindings.clear() }
        override fun executeUpdateDelete(): Int {
            val index = rows.size
            rows += bindings.values.toList()
            if (index == failAt) error("Injected statement failure")
            return counts.getOrElse(index) { 1 }
        }
        override fun execute(): Unit = error("Must use scalar row count")
        override fun executeInsert(): Long = error("Must not insert retained rows")
        override fun simpleQueryForLong(): Long = error("Must not query changes")
        override fun simpleQueryForString(): String = error("Must not query changes")
        override fun close() { closed = true }
    }
}

package io.github.anastaci1a.localica.queue

import android.database.SQLException
import androidx.sqlite.db.SupportSQLiteStatement
import java.io.Closeable

/** Uses the owning Room transaction and reuses typed statements without replacing existing rows. */
internal class QueueJourneyWriter(private val compile: (String) -> SupportSQLiteStatement) {
    fun updateVisits(rows: List<VisitRecord>): Int = update(visits, rows)
    fun updateMembers(rows: List<CycleMemberRecord>): Int = update(members, rows)
    fun upsertVisits(rows: List<VisitRecord>) = upsert(visits, rows)
    fun upsertMembers(rows: List<CycleMemberRecord>) = upsert(members, rows)

    private fun <T> update(sql: Rows<T>, rows: List<T>): Int {
        if (rows.isEmpty()) return 0
        return compile(sql.update).use { statement -> rows.sumOf { row -> statement.update(sql, row) } }
    }

    private fun <T> upsert(sql: Rows<T>, rows: List<T>) {
        if (rows.isEmpty()) return
        compile(sql.insert).use { statement ->
            DeferredStatement { compile(sql.update) }.use { repair ->
                for (row in rows) {
                    sql.bind(statement, row)
                    try { statement.execute() } catch (error: SQLException) {
                        // Preserve pinned Room 2.7.2's uniqueness-only upsert repair, in the same row order.
                        if (!isJourneyUniquenessFailure(error.message)) throw error
                        // Room ignores the repair count; a zero-row update is not a new failure condition.
                        repair.get().update(sql, row)
                    }
                    statement.clearBindings()
                }
            }
        }
    }

    private fun <T> SupportSQLiteStatement.update(sql: Rows<T>, row: T): Int {
        sql.bind(this, row); sql.key(this, row)
        return executeUpdateDelete().also { check(it in 0..1) { "Unexpected saved journey row count" } }
    }

    private class DeferredStatement(private val compile: () -> SupportSQLiteStatement) : Closeable {
        private var statement: SupportSQLiteStatement? = null
        fun get(): SupportSQLiteStatement = statement ?: compile().also { statement = it }
        override fun close() { statement?.close() }
    }

    private class Rows<T>(val insert: String, val update: String,
        val bind: (SupportSQLiteStatement, T) -> Unit, val key: (SupportSQLiteStatement, T) -> Unit)

    private companion object {
        // Match pinned Room SQL: plain INSERT uses these tables' default ABORT conflict behavior.
        val visits = Rows<VisitRecord>(
            "INSERT INTO `queue_visits` (`token`,`queueId`,`ordinal`,`entryId`,`cycle`) VALUES (?,?,?,?,?)",
            "UPDATE OR ABORT `queue_visits` SET `token` = ?,`queueId` = ?,`ordinal` = ?,`entryId` = ?,`cycle` = ? WHERE `token` = ?",
            { statement, row -> with(statement) {
                bindString(1, row.token); bindString(2, row.queueId); bindLong(3, row.ordinal.toLong())
                bindString(4, row.entryId); bindLong(5, row.cycle)
            } },
            { statement, row -> statement.bindString(6, row.token) },
        )
        val members = Rows<CycleMemberRecord>(
            "INSERT INTO `queue_cycle_members` (`queueId`,`cycle`,`entryId`,`ordinal`,`visited`) VALUES (?,?,?,?,?)",
            "UPDATE OR ABORT `queue_cycle_members` SET `queueId` = ?,`cycle` = ?,`entryId` = ?,`ordinal` = ?,`visited` = ? WHERE `queueId` = ? AND `cycle` = ? AND `entryId` = ?",
            { statement, row -> with(statement) {
                bindString(1, row.queueId); bindLong(2, row.cycle); bindString(3, row.entryId)
                bindLong(4, row.ordinal.toLong()); bindLong(5, if (row.visited) 1 else 0)
            } },
            { statement, row -> with(statement) {
                bindString(6, row.queueId); bindLong(7, row.cycle); bindString(8, row.entryId)
            } },
        )
    }
}

/** Matches Room's pinned message predicate; other constraints must abort the owning transaction. */
internal fun isJourneyUniquenessFailure(message: String?): Boolean = message != null &&
    (message.contains("unique", ignoreCase = true) || message.contains("2067") || message.contains("1555"))

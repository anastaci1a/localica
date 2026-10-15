package io.github.anastaci1a.localica.queue

import androidx.sqlite.db.SupportSQLiteStatement

/** Uses the owning Room transaction; scalar counts avoid an Android Cursor for every retained row. */
internal class QueueJourneyWriter(private val compile: (String) -> SupportSQLiteStatement) {
    fun updateVisits(rows: List<VisitRecord>): Int = update(
        "UPDATE OR ABORT `queue_visits` SET `token` = ?,`queueId` = ?,`ordinal` = ?,`entryId` = ?,`cycle` = ? WHERE `token` = ?",
        rows,
    ) { row ->
        bindString(1, row.token); bindString(2, row.queueId); bindLong(3, row.ordinal.toLong())
        bindString(4, row.entryId); bindLong(5, row.cycle); bindString(6, row.token)
    }

    fun updateMembers(rows: List<CycleMemberRecord>): Int = update(
        "UPDATE OR ABORT `queue_cycle_members` SET `queueId` = ?,`cycle` = ?,`entryId` = ?,`ordinal` = ?,`visited` = ? WHERE `queueId` = ? AND `cycle` = ? AND `entryId` = ?",
        rows,
    ) { row ->
        bindString(1, row.queueId); bindLong(2, row.cycle); bindString(3, row.entryId)
        bindLong(4, row.ordinal.toLong()); bindLong(5, if (row.visited) 1 else 0)
        bindString(6, row.queueId); bindLong(7, row.cycle); bindString(8, row.entryId)
    }

    private fun <T> update(sql: String, rows: List<T>, bind: SupportSQLiteStatement.(T) -> Unit): Int {
        if (rows.isEmpty()) return 0
        return compile(sql).use { statement ->
            rows.sumOf { row ->
                statement.bind(row)
                statement.executeUpdateDelete().also { check(it in 0..1) { "Unexpected saved journey row count" } }
            }
        }
    }
}

package io.github.anastaci1a.localica.queue

import androidx.room.*

@Entity(tableName = "queue_journeys", foreignKeys = [ForeignKey(entity = QueueRecord::class,
    parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)])
data class JourneyRecord(@PrimaryKey val queueId: String, val historySize: Int, val cursor: Int,
    val cycle: Long, val shuffle: Boolean, val loop: Boolean, val reshuffle: Boolean,
    @ColumnInfo(defaultValue = "NULL") val orderedTraversalId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val orderedAnchorToken: String? = null)

@Entity(tableName = "queue_visits", foreignKeys = [ForeignKey(entity = QueueRecord::class,
    parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)], indices = [Index("queueId")])
data class VisitRecord(@PrimaryKey val token: String, val queueId: String, val ordinal: Int, val entryId: String, val cycle: Long)

@Entity(tableName = "queue_cycle_members", primaryKeys = ["queueId", "cycle", "entryId"], foreignKeys = [ForeignKey(entity = QueueRecord::class,
    parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)])
data class CycleMemberRecord(val queueId: String, val cycle: Long, val entryId: String, val ordinal: Int, val visited: Boolean)

internal fun QueueJourney.record(queueId: String) = JourneyRecord(queueId, history.size, cursor, cycle, modes.shuffle, modes.loop, modes.reshuffle, orderedTraversalId, orderedAnchorToken)
internal fun QueueJourney.visitRecords(queueId: String) = (history + future).mapIndexed { index, visit ->
    VisitRecord(visit.token, queueId, index, visit.entryId, visit.cycle)
}
internal fun QueueJourney.cycleRecords(queueId: String) = plans.flatMap { (number, order) ->
    order.mapIndexed { index, entry -> CycleMemberRecord(queueId, number, entry, index, number == cycle && entry in visited) }
}
internal fun JourneyRecord.restore(visits: List<VisitRecord>, members: List<CycleMemberRecord>): QueueJourney {
    val ordered = visits.sortedBy { it.ordinal }.map { QueueVisit(it.entryId, it.cycle, it.token) }
    require(historySize in 0..ordered.size)
    require(if (historySize == 0) cursor == -1 else cursor in 0 until historySize)
    return QueueJourney(ordered.take(historySize), cursor, ordered.drop(historySize), cycle,
        members.filter { it.cycle == cycle && it.visited }.map { it.entryId }.toSet(),
        members.groupBy { it.cycle }.mapValues { (_, items) -> items.sortedBy { it.ordinal }.map { it.entryId } },
        JourneyModes(shuffle, loop, reshuffle), orderedTraversalId, orderedAnchorToken)
}

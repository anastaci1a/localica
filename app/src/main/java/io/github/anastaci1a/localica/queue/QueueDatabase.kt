package io.github.anastaci1a.localica.queue

import androidx.room.*
import io.github.anastaci1a.localica.library.TrackSnapshot

@Entity(tableName = "queues")
data class QueueRecord(@PrimaryKey val id: String, val name: String, val createdAt: Long,
    val currentEntryId: String?, val positionMs: Long, val shuffle: Boolean, val repeat: String, @ColumnInfo(defaultValue = "0") val ordinal: Int = 0)
@Entity(tableName = "queue_entries", foreignKeys = [ForeignKey(entity = QueueRecord::class,
    parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)], indices = [Index("queueId")])
data class EntryRecord(@PrimaryKey val id: String, val queueId: String, val ordinal: Int, val track: String)
@Entity(tableName = "queue_state")
data class ActiveQueueRecord(@PrimaryKey val id: Int = 1, val queueId: String?)
data class StoredQueue(@Embedded val queue: QueueRecord,
    @Relation(parentColumn = "id", entityColumn = "queueId") val entries: List<EntryRecord>)

@Dao
abstract class QueueDao {
    @Transaction @Query("SELECT * FROM queues ORDER BY ordinal, createdAt, id") abstract suspend fun readQueues(): List<StoredQueue>
    @Query("SELECT * FROM queue_state WHERE id = 1") abstract suspend fun readActive(): ActiveQueueRecord?
    @Upsert protected abstract suspend fun upsertQueue(queue: QueueRecord)
    @Upsert protected abstract suspend fun upsertActive(active: ActiveQueueRecord)
    @Insert protected abstract suspend fun insertEntries(entries: List<EntryRecord>)
    @Query("DELETE FROM queue_entries WHERE queueId = :id") protected abstract suspend fun clearEntries(id: String)
    @Query("DELETE FROM queues WHERE id = :id") protected abstract suspend fun deleteQueue(id: String)

    @Transaction open suspend fun read(): QueueLibrary {
        val queues = readQueues().map { stored -> stored.queue.let { q ->
            ListeningQueue(q.id, q.name, q.createdAt, stored.entries.sortedBy { it.ordinal }.map {
                QueueEntry(it.id, TrackSnapshot.decode(it.track)) }, q.currentEntryId, q.positionMs, q.shuffle,
                QueueRepeat.entries.firstOrNull { it.name == q.repeat } ?: QueueRepeat.Off)
        } }
        return QueueLibrary(queues, readActive()?.queueId?.takeIf { id -> queues.any { it.id == id } })
    }

    /** Cursor updates write only queue metadata; edits replace only the changed queue's entries. */
    @Transaction open suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        before.queues.filter { old -> after.queues.none { it.id == old.id } }.forEach { deleteQueue(it.id) }
        for ((ordinal, queue) in after.queues.withIndex()) {
            val previous = before.queues.firstOrNull { it.id == queue.id }
            if (previous == queue && before.queues.indexOf(previous) == ordinal) continue
            upsertQueue(QueueRecord(queue.id, queue.name, queue.createdAt, queue.currentEntryId,
                queue.positionMs, queue.shuffle, queue.repeat.name, ordinal))
            if (previous?.entries != queue.entries) {
                clearEntries(queue.id)
                insertEntries(queue.entries.mapIndexed { index, entry ->
                    EntryRecord(entry.id, queue.id, index, TrackSnapshot.encode(entry.track)) })
            }
        }
        upsertActive(ActiveQueueRecord(queueId = after.activeId))
    }
}

@Database(entities = [QueueRecord::class, EntryRecord::class, ActiveQueueRecord::class], version = 2, exportSchema = true)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun queues(): QueueDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queues ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0")
                db.query("SELECT id FROM queues ORDER BY createdAt, id").use { cursor ->
                    var ordinal = 0
                    while (cursor.moveToNext()) db.execSQL("UPDATE queues SET ordinal = ? WHERE id = ?", arrayOf(ordinal++, cursor.getString(0)))
                }
            }
        }
    }
}

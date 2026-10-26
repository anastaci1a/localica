package io.github.anastaci1a.localica.queue

import androidx.room.*
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.SavedOrderCodec

@Entity(tableName = "queues")
data class QueueRecord(@PrimaryKey val id: String, val name: String, val createdAt: Long,
    val currentEntryId: String?, val positionMs: Long, val shuffle: Boolean, val repeat: String, @ColumnInfo(defaultValue = "0") val ordinal: Int = 0, @ColumnInfo(defaultValue = "0") val lastActivatedAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val orderState: String = "")
/** Partial update keeps the unchanged, potentially large order payload out of cursor checkpoints. */
data class QueueMetadataRecord(val id: String, val name: String, val createdAt: Long,
    val currentEntryId: String?, val positionMs: Long, val shuffle: Boolean, val repeat: String,
    val ordinal: Int, val lastActivatedAt: Long)
@Entity(tableName = "queue_entries", foreignKeys = [ForeignKey(entity = QueueRecord::class,
    parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)], indices = [Index("queueId")])
data class EntryRecord(@PrimaryKey val id: String, val queueId: String, val ordinal: Int, val track: String, val sourcePlaylistId: String? = null)
@Entity(tableName = "queue_state")
data class ActiveQueueRecord(@PrimaryKey val id: Int = 1, val queueId: String?)
data class StoredQueue(@Embedded val queue: QueueRecord,
    @Relation(parentColumn = "id", entityColumn = "queueId") val entries: List<EntryRecord>,
    @Relation(parentColumn = "id", entityColumn = "queueId") val journeys: List<JourneyRecord>,
    @Relation(parentColumn = "id", entityColumn = "queueId") val visits: List<VisitRecord>,
    @Relation(parentColumn = "id", entityColumn = "queueId") val cycleMembers: List<CycleMemberRecord>)

@Dao
abstract class QueueDao {
    private val database: RoomDatabase?
    constructor(database: RoomDatabase) { this.database = database }
    // Pure test DAOs override the SQL boundary; Room selects the non-null owning database constructor.
    protected constructor() { database = null }
    private fun journeyWriter(): QueueJourneyWriter {
        val owner = checkNotNull(database) { "A database is required for concrete journey writes" }
        check(owner.inTransaction()) { "Journey writes require the owning save transaction" }
        return QueueJourneyWriter(owner::compileStatement)
    }

    @Transaction @Query("SELECT * FROM queues ORDER BY ordinal, createdAt, id") abstract suspend fun readQueues(): List<StoredQueue>
    @Query("SELECT * FROM queue_state WHERE id = 1") abstract suspend fun readActive(): ActiveQueueRecord?
    @Upsert protected abstract suspend fun upsertQueue(queue: QueueRecord)
    @Update(entity = QueueRecord::class) protected abstract suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int
    @Upsert protected abstract suspend fun upsertActive(active: ActiveQueueRecord)
    @Upsert protected abstract suspend fun upsertJourney(journey: JourneyRecord)
    @Query("DELETE FROM queue_journeys WHERE queueId = :id") protected abstract suspend fun clearJourney(id: String)
    protected open suspend fun upsertVisits(visits: List<VisitRecord>) = journeyWriter().upsertVisits(visits)
    protected open suspend fun updateVisits(visits: List<VisitRecord>): Int = journeyWriter().updateVisits(visits)
    protected open suspend fun upsertCycleMembers(members: List<CycleMemberRecord>) = journeyWriter().upsertMembers(members)
    protected open suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = journeyWriter().updateMembers(members)
    @Query("DELETE FROM queue_visits WHERE queueId = :queueId AND token IN (:tokens)")
    protected abstract suspend fun deleteVisits(queueId: String, tokens: List<String>)
    @Query("DELETE FROM queue_cycle_members WHERE queueId = :queueId AND cycle = :cycle AND entryId IN (:entries)")
    protected abstract suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>)
    @Insert protected abstract suspend fun insertEntries(entries: List<EntryRecord>)
    /** Authoritative-before saves may change only order; qualify every row by its queue owner. */
    protected open suspend fun updateEntryOrdinals(queueId: String, entryIds: List<String>): Int {
        val owner = checkNotNull(database) { "A database is required for concrete ordinal writes" }
        check(owner.inTransaction()) { "Ordinal writes require the owning save transaction" }
        return owner.compileStatement("UPDATE OR ABORT queue_entries SET ordinal = ? WHERE id = ? AND queueId = ?").use { statement ->
            var matched = 0
            for ((ordinal, id) in entryIds.withIndex()) {
                statement.bindLong(1, ordinal.toLong())
                statement.bindString(2, id)
                statement.bindString(3, queueId)
                val changed = statement.executeUpdateDelete()
                check(changed in 0..1) { "Unexpected saved occurrence row count" }
                matched += changed
            }
            matched
        }
    }
    @Query("DELETE FROM queue_entries WHERE queueId = :id") protected abstract suspend fun clearEntries(id: String)
    @Query("DELETE FROM queues WHERE id = :id") protected abstract suspend fun deleteQueue(id: String)

    @Transaction open suspend fun read(): QueueLibrary {
        val queues = readQueues().map { stored -> stored.queue.let { q ->
            ListeningQueue(q.id, q.name, q.createdAt, stored.entries.sortedBy { it.ordinal }.map {
                QueueEntry(it.id, TrackSnapshot.decode(it.track), it.sourcePlaylistId) }, q.currentEntryId, q.positionMs, q.shuffle,
                QueueRepeat.entries.firstOrNull { it.name == q.repeat } ?: QueueRepeat.Off, q.lastActivatedAt,
                stored.journeys.singleOrNull()?.restore(stored.visits, stored.cycleMembers),
                SavedOrderCodec.decode(q.orderState, stored.entries.sortedBy { it.ordinal }.map { it.id }))
        } }
        return QueueLibrary(queues, readActive()?.queueId?.takeIf { id -> queues.any { it.id == id } })
    }

    /** Cursor updates keep metadata small; exact reorders preserve occurrence payloads in place. */
    @Transaction open suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        before.queues.filter { old -> after.queues.none { it.id == old.id } }.forEach { deleteQueue(it.id) }
        for ((ordinal, queue) in after.queues.withIndex()) {
            val previous = before.queues.firstOrNull { it.id == queue.id }
            if (previous == queue && before.queues.indexOf(previous) == ordinal) continue
            if (previous != null && previous.order == queue.order) {
                check(updateQueueMetadata(QueueMetadataRecord(queue.id, queue.name, queue.createdAt, queue.currentEntryId,
                    queue.positionMs, queue.shuffle, queue.repeat.name, ordinal, queue.lastActivatedAt)) == 1) {
                    "The saved queue is missing"
                }
            } else {
                upsertQueue(QueueRecord(queue.id, queue.name, queue.createdAt, queue.currentEntryId,
                    queue.positionMs, queue.shuffle, queue.repeat.name, ordinal, queue.lastActivatedAt, SavedOrderCodec.encode(queue.order)))
            }
            if (previous?.journey != queue.journey) saveJourney(queue.id, previous?.journey, queue.journey)
            if (previous?.entries != queue.entries) {
                val ordinalOnly = previous != null && queueEntryOrderEligible(previous.entries, queue.entries)
                val matched = if (ordinalOnly) updateEntryOrdinals(queue.id, queue.entries.map { it.id }) else null
                check(matched == null || matched in 0..queue.entries.size) { "Unexpected saved occurrence count" }
                // Includes stationary rows: a missing middle occurrence must trigger complete repair too.
                if (matched != queue.entries.size) {
                    clearEntries(queue.id)
                    insertEntries(queueEntryRecords(queue.id, queue.entries))
                }
            }
        }
        upsertActive(ActiveQueueRecord(queueId = after.activeId))
    }

    /** Keep ordinary cursor checkpoints small; only changed visits/membership touch their tables. */
    private suspend fun saveJourney(id: String, before: QueueJourney?, after: QueueJourney?) {
        if (after == null) clearJourney(id) else upsertJourney(after.record(id))
        if (before?.history != after?.history || before?.future != after?.future) {
            val oldVisits = before?.visitRecords(id).orEmpty().associateBy { it.token }
            val newVisits = after?.visitRecords(id).orEmpty().associateBy { it.token }
            (oldVisits.keys - newVisits.keys).chunked(500).forEach { deleteVisits(id, it) }
            val (retained, added) = newVisits.values.filter { oldVisits[it.token] != it }.partition { it.token in oldVisits }
            updateOrRepair(retained, ::updateVisits, ::upsertVisits)
            if (added.isNotEmpty()) upsertVisits(added)
        }
        if (before?.plans != after?.plans || before?.visited != after?.visited || before?.cycle != after?.cycle) {
            val oldMembers = before?.cycleRecords(id).orEmpty().associateBy { it.cycle to it.entryId }
            val newMembers = after?.cycleRecords(id).orEmpty().associateBy { it.cycle to it.entryId }
            (oldMembers.keys - newMembers.keys).groupBy({ it.first }, { it.second }).forEach { (cycle, entries) ->
                entries.chunked(500).forEach { deleteCycleMembers(id, cycle, it) }
            }
            val (retained, added) = newMembers.values.filter { oldMembers[it.cycle to it.entryId] != it }
                .partition { it.cycle to it.entryId in oldMembers }
            updateOrRepair(retained, ::updateCycleMembers, ::upsertCycleMembers)
            if (added.isNotEmpty()) upsertCycleMembers(added)
        }
    }

    /** Avoid an INSERT/constraint exception for every retained row; preserve upsert repair if a row is missing. */
    private suspend fun <T> updateOrRepair(rows: List<T>, update: suspend (List<T>) -> Int, upsert: suspend (List<T>) -> Unit) {
        if (rows.isEmpty()) return
        val changed = update(rows)
        check(changed in 0..rows.size) { "Unexpected saved journey row count" }
        if (changed != rows.size) upsert(rows)
    }
}

@Database(entities = [QueueRecord::class, EntryRecord::class, ActiveQueueRecord::class, JourneyRecord::class,
    VisitRecord::class, CycleMemberRecord::class], version = 7, exportSchema = true)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun queues(): QueueDao
    companion object {
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_journeys ADD COLUMN orderedTraversalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE queue_journeys ADD COLUMN orderedAnchorToken TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queues ADD COLUMN orderState TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS queue_journeys (queueId TEXT NOT NULL PRIMARY KEY, historySize INTEGER NOT NULL, cursor INTEGER NOT NULL, cycle INTEGER NOT NULL, shuffle INTEGER NOT NULL, loop INTEGER NOT NULL, reshuffle INTEGER NOT NULL, FOREIGN KEY(queueId) REFERENCES queues(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS queue_visits (token TEXT NOT NULL PRIMARY KEY, queueId TEXT NOT NULL, ordinal INTEGER NOT NULL, entryId TEXT NOT NULL, cycle INTEGER NOT NULL, FOREIGN KEY(queueId) REFERENCES queues(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_visits_queueId ON queue_visits(queueId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS queue_cycle_members (queueId TEXT NOT NULL, cycle INTEGER NOT NULL, entryId TEXT NOT NULL, ordinal INTEGER NOT NULL, visited INTEGER NOT NULL, PRIMARY KEY(queueId, cycle, entryId), FOREIGN KEY(queueId) REFERENCES queues(id) ON DELETE CASCADE)")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_entries ADD COLUMN sourcePlaylistId TEXT")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queues ADD COLUMN lastActivatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE queues SET lastActivatedAt = createdAt")
                db.execSQL("UPDATE queues SET lastActivatedAt = (SELECT COALESCE(MAX(createdAt), 0) + 1 FROM queues) WHERE id IN (SELECT queueId FROM queue_state)")
            }
        }
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

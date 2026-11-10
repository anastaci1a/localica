package io.github.anastaci1a.localica.queue

import androidx.room.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.SavedOrderCodec
import io.github.anastaci1a.localica.library.enumNameOrNull

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

/** Opt-in transaction-body measurements; the caller times DAO return for commit/admission. */
internal data class QueueSaveStage(val name: String, val rows: Int, val nanos: Long)

@Dao
abstract class QueueDao {
    @Volatile internal var saveStageObserver: ((QueueSaveStage) -> Unit)? = null
    private inline fun <T> measureSave(name: String, rows: Int = 0, block: () -> T): T {
        val observer = saveStageObserver ?: return block()
        val started = System.nanoTime()
        try { return block() } finally {
            // A diagnostic sink cannot fail an otherwise successful transaction.
            try { observer(QueueSaveStage(name, rows, System.nanoTime() - started)) } catch (_: Exception) { }
        }
    }
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
    @Query("DELETE FROM queue_entries WHERE queueId = :id") protected abstract suspend fun clearEntries(id: String)
    @Query("DELETE FROM queues WHERE id = :id") protected abstract suspend fun deleteQueue(id: String)

    /** Rows are read a page of one queue at a time, and queues of the same songs share their decoded songs:
     * restoring many saved queues of a whole library costs one song per distinct snapshot, not one per entry (#774). */
    @Transaction open suspend fun read(): QueueLibrary {
        val restore = QueueRestore()
        val rows = (database as? QueueDatabase)?.restore()
        val queues = if (rows != null) rows.queues().map { queue ->
            val entries = ArrayList<QueueEntry>()
            while (true) {
                val page = rows.entries(queue.id, QueueRestore.PageRows, entries.size)
                page.mapTo(entries) { restore.entry(it.id, it.track, it.sourcePlaylistId) }
                if (page.size < QueueRestore.PageRows) break
            }
            restore.queue(queue, entries, rows.journeys(queue.id), rows.visits(queue.id), rows.cycleMembers(queue.id))
        } else readQueues().map { stored ->
            restore.queue(stored.queue, stored.entries.sortedBy { it.ordinal }.map { restore.entry(it.id, it.track, it.sourcePlaylistId) },
                stored.journeys, stored.visits, stored.cycleMembers)
        }
        return QueueLibrary(queues, readActive()?.queueId?.takeIf { id -> queues.any { it.id == id } })
    }

    /** Cursor updates write only queue metadata; edits replace only the changed queue's entries. */
    @Transaction open suspend fun save(before: QueueLibrary, after: QueueLibrary): Unit = measureSave("transaction-body") {
        before.queues.filter { old -> after.queues.none { it.id == old.id } }.forEach { deleteQueue(it.id) }
        for ((ordinal, queue) in after.queues.withIndex()) {
            val previous = before.queues.firstOrNull { it.id == queue.id }
            if (previous == queue && before.queues.indexOf(previous) == ordinal) continue
            if (previous != null && previous.order == queue.order) {
                check(measureSave("queue-metadata", 1) {
                    updateQueueMetadata(QueueMetadataRecord(queue.id, queue.name, queue.createdAt, queue.currentEntryId,
                        queue.positionMs, queue.shuffle, queue.repeat.name, ordinal, queue.lastActivatedAt))
                } == 1) {
                    "The saved queue is missing"
                }
            } else {
                val order = measureSave("order-encode", queue.order.manual.size) { SavedOrderCodec.encode(queue.order) }
                measureSave("queue-upsert", 1) {
                    upsertQueue(QueueRecord(queue.id, queue.name, queue.createdAt, queue.currentEntryId,
                        queue.positionMs, queue.shuffle, queue.repeat.name, ordinal, queue.lastActivatedAt, order))
                }
            }
            if (previous?.journey != queue.journey) saveJourney(queue.id, previous?.journey, queue.journey)
            if (previous?.entries != queue.entries) {
                measureSave("entries-clear") { clearEntries(queue.id) }
                val entries = measureSave("tracks-encode", queue.entries.size) {
                    queue.entries.mapIndexed { index, entry ->
                        EntryRecord(entry.id, queue.id, index, TrackSnapshot.encode(entry.track), entry.sourcePlaylistId) }
                }
                measureSave("entries-insert", entries.size) { insertEntries(entries) }
            }
        }
        measureSave("active-upsert", 1) { upsertActive(ActiveQueueRecord(queueId = after.activeId)) }
    }

    /** Keep ordinary cursor checkpoints small; only changed visits/membership touch their tables. */
    private suspend fun saveJourney(id: String, before: QueueJourney?, after: QueueJourney?) {
        measureSave("journey-record", if (after == null) 0 else 1) {
            if (after == null) clearJourney(id) else upsertJourney(after.record(id))
        }
        if (before?.history != after?.history || before?.future != after?.future) {
            val (oldVisits, newVisits) = measureSave("visits-project") {
                before?.visitRecords(id).orEmpty().associateBy { it.token } to
                    after?.visitRecords(id).orEmpty().associateBy { it.token }
            }
            (oldVisits.keys - newVisits.keys).chunked(500).forEach { deleteVisits(id, it) }
            val (retained, added) = newVisits.values.filter { oldVisits[it.token] != it }.partition { it.token in oldVisits }
            measureSave("visits-write", retained.size + added.size) {
                updateOrRepair(retained, ::updateVisits, ::upsertVisits)
                if (added.isNotEmpty()) upsertVisits(added)
            }
        }
        if (before?.plans != after?.plans || before?.visited != after?.visited || before?.cycle != after?.cycle) {
            val (oldMembers, newMembers) = measureSave("cycle-project") {
                before?.cycleRecords(id).orEmpty().associateBy { it.cycle to it.entryId } to
                    after?.cycleRecords(id).orEmpty().associateBy { it.cycle to it.entryId }
            }
            (oldMembers.keys - newMembers.keys).groupBy({ it.first }, { it.second }).forEach { (cycle, entries) ->
                entries.chunked(500).forEach { deleteCycleMembers(id, cycle, it) }
            }
            val (retained, added) = newMembers.values.filter { oldMembers[it.cycle to it.entryId] != it }
                .partition { it.cycle to it.entryId in oldMembers }
            measureSave("cycle-write", retained.size + added.size) {
                updateOrRepair(retained, ::updateCycleMembers, ::upsertCycleMembers)
                if (added.isNotEmpty()) upsertCycleMembers(added)
            }
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

/** One saved occurrence without its queue's id, which every row of a page would repeat. */
data class RestoredEntryRow(val id: String, val track: String, val sourcePlaylistId: String?)

/** Restore reads: a page of one queue's rows per statement, so a library of saved queues is never resident as rows. */
@Dao
interface QueueRestoreDao {
    @Query("SELECT * FROM queues ORDER BY ordinal, createdAt, id") suspend fun queues(): List<QueueRecord>
    @Query("SELECT id, track, sourcePlaylistId FROM queue_entries WHERE queueId = :id ORDER BY ordinal, rowid LIMIT :limit OFFSET :offset")
    suspend fun entries(id: String, limit: Int, offset: Int): List<RestoredEntryRow>
    @Query("SELECT * FROM queue_journeys WHERE queueId = :id") suspend fun journeys(id: String): List<JourneyRecord>
    @Query("SELECT * FROM queue_visits WHERE queueId = :id") suspend fun visits(id: String): List<VisitRecord>
    @Query("SELECT * FROM queue_cycle_members WHERE queueId = :id") suspend fun cycleMembers(id: String): List<CycleMemberRecord>
}

/** One restore's shared values. Equal snapshots decode once, equal texts inside them are one string, and every
 * reference to an occurrence uses its entry's id. Values stay equal to an unshared restore. */
private class QueueRestore {
    private val tracks = HashMap<String, Track>()
    private val texts = HashMap<String, String>()
    private fun text(value: String) = texts.getOrPut(value) { value }
    private fun optional(value: String?) = value?.let(::text)

    fun entry(id: String, snapshot: String, sourcePlaylistId: String?) =
        QueueEntry(id, tracks.getOrPut(snapshot) { compact(TrackSnapshot.decode(snapshot)) }, optional(sourcePlaylistId))

    /** Folder roots, people, albums and genres repeat across a library; a location usually repeats its song's own address. */
    private fun compact(track: Track) = track.copy(artist = optional(track.artist), album = optional(track.album),
        albumArtist = optional(track.albumArtist), genre = optional(track.genre), composer = optional(track.composer),
        lyricist = optional(track.lyricist), releaseType = optional(track.releaseType),
        locations = track.locations.map { place -> TrackLocation(text(place.folderUri),
            if (place.uri == track.uri) track.uri else place.uri,
            if (place.relativePath == track.relativePath) track.relativePath else place.relativePath) })

    fun queue(queue: QueueRecord, entries: List<QueueEntry>, journeys: List<JourneyRecord>, visits: List<VisitRecord>,
        members: List<CycleMemberRecord>): ListeningQueue {
        val ids = HashMap<String, String>(entries.size * 2)
        entries.forEach { ids[it.id] = it.id }
        fun shared(id: String) = ids[id] ?: id
        val order = SavedOrderCodec.decode(queue.orderState, entries.map { it.id })
        return ListeningQueue(queue.id, queue.name, queue.createdAt, entries, queue.currentEntryId, queue.positionMs, queue.shuffle,
            enumNameOrNull<QueueRepeat>(queue.repeat) ?: QueueRepeat.Off, queue.lastActivatedAt,
            journeys.singleOrNull()?.restore(visits.map { it.copy(entryId = shared(it.entryId)) },
                members.map { it.copy(entryId = shared(it.entryId)) }),
            order.copy(manual = order.manual.map(::shared)))
    }

    companion object { const val PageRows = 2000 }
}

@Database(entities = [QueueRecord::class, EntryRecord::class, ActiveQueueRecord::class, JourneyRecord::class,
    VisitRecord::class, CycleMemberRecord::class], version = 7, exportSchema = true)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun queues(): QueueDao
    abstract fun restore(): QueueRestoreDao
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

package io.github.anastaci1a.localica.queue

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.library.enumNameOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The actor and viewport receive immutable windows; SQLite connections never escape this worker boundary. */
internal class QueueWindowStore(private val room: RoomDatabase) {
    private val schemaLock = Mutex()
    @Volatile private var schemaReady = false

    suspend fun restore(settings: QueueSettings): QueueLibrary = transaction { sequence ->
        val db = sequence.db
        QueueSelection(sequence).reset()
        val activeId = db.query("SELECT queueId FROM queue_state WHERE id = 1").use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getString(0)
        }
        // Never SELECT orderState here: that legacy column can itself contain a million IDs.
        val records = db.query("SELECT id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,lastActivatedAt FROM queues ORDER BY ordinal,createdAt,id").use { rows ->
            buildList {
                while (rows.moveToNext()) add(QueueRecord(rows.getString(0), rows.getString(1), rows.getLong(2),
                    if (rows.isNull(3)) null else rows.getString(3), rows.getLong(4), rows.getInt(5) != 0,
                    rows.getString(6), lastActivatedAt = rows.getLong(7)))
            }
        }
        val migration = QueueSequenceMigration(sequence)
        val queues = records.map { record ->
            migration.import(record.id)
            project(sequence, record, record.id == activeId, settings)
        }
        QueueLibrary(queues, activeId?.takeIf { id -> queues.any { it.id == id } })
    }

    suspend fun page(queueId: String, start: Int, size: Int): QueueSequencePage = transaction {
        it.page(queueId, start, size)
    }

    /** A late player callback may update only the exact active occurrence and durable current visit. */
    suspend fun checkpoint(queueId: String, entryId: String, visitToken: String, positionMs: Long): Boolean = transaction(sweepSelections = false) { sequence ->
        require(positionMs >= 0)
        sequence.db.compileStatement("""
            UPDATE queues SET positionMs = ? WHERE id = ? AND currentEntryId = ?
                AND EXISTS (SELECT 1 FROM queue_state WHERE id = 1 AND queueId = queues.id)
                AND EXISTS (SELECT 1 FROM queue_sequence_traversal t JOIN queue_sequence_history h
                    ON h.queueId = t.queueId AND h.position = t.cursor
                    WHERE t.queueId = queues.id AND h.entryId = ? AND h.token = ?)
        """.trimIndent()).use {
            it.bindLong(1, positionMs)
            it.bindString(2, queueId)
            it.bindString(3, entryId)
            it.bindString(4, entryId)
            it.bindString(5, visitToken)
            it.executeUpdateDelete() == 1
        }
    }

    suspend fun select(queueId: String, all: Boolean = false): QueueSelectionHandle = transaction {
        QueueSelection(it).create(queueId, all)
    }

    suspend fun setSelection(handle: QueueSelectionHandle, ids: Set<String>, selected: Boolean): QueueSelectionHandle = transaction {
        QueueSelection(it).set(handle, ids, selected)
    }

    suspend fun selectionCount(handle: QueueSelectionHandle): Int = transaction { QueueSelection(it).count(handle) }

    suspend fun selectionPage(handle: QueueSelectionHandle, start: Int, size: Int): QueueSequencePage = transaction {
        QueueSelection(it).page(handle, start, size)
    }

    suspend fun closeSelection(selectionId: String) = transaction { QueueSelection(it).close(selectionId) }

    private fun project(sequence: QueueSequence, record: QueueRecord, active: Boolean,
        settings: QueueSettings): ListeningQueue {
        val summary = sequence.revision(record.id)
        val window = if (active) QueueTraversal(sequence).restore(record.id, record.currentEntryId,
            JourneyModes.from(settings)) else null
        val entries = if (window == null) emptyList() else sequence.entries(record.id, window.positions.keys)
        val currentId = window?.current?.entryId ?: record.currentEntryId
        val position = currentId?.let { sequence.position(record.id, it) } ?: -1
        val order = sequence.db.query("SELECT sort,ascending FROM queue_sequence_state WHERE queueId = ?",
            arrayOf(record.id)).use {
            check(it.moveToFirst())
            SavedOrder(emptyList(), enumNameOrNull<TrackSort>(it.getString(0)) ?: TrackSort.Manual, it.getInt(1) != 0)
        }
        return ListeningQueue(record.id, record.name, record.createdAt, entries, currentId, record.positionMs,
            record.shuffle, enumNameOrNull<QueueRepeat>(record.repeat) ?: QueueRepeat.Off, record.lastActivatedAt,
            order = order, storage = QueueStorageState(summary.count, summary.durationMs, summary.knownDurations,
                position, summary.revision), playbackWindow = window)
    }

    /** This store shares the actor and owning Room database lifetime. Set readiness only after schema commit. */
    private suspend fun ensureSchema() {
        if (schemaReady) return
        schemaLock.withLock {
            if (!schemaReady) {
                room.withTransaction {
                    val db = room.openHelper.writableDatabase
                    QueueSequence.createSchema(db)
                    QueueTraversal.createSchema(db)
                }
                schemaReady = true
            }
        }
    }

    private suspend fun <T> transaction(sweepSelections: Boolean = true, block: (QueueSequence) -> T): T = withContext(Dispatchers.IO) {
        ensureSchema()
        room.withTransaction {
            val sequence = QueueSequence(room.openHelper.writableDatabase)
            if (sweepSelections) QueueSelection(sequence).sweepStale()
            block(sequence).also { if (sweepSelections) QueueSelection(sequence).sweepStale() }
        }
    }
}

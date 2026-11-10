package io.github.anastaci1a.localica.queue

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** A selection is bound to one sequence and selection revision, never an in-memory set of all IDs. */
internal data class QueueSelectionHandle(val id: String, val queueId: String, val sequenceRevision: Long,
    val revision: Long, val all: Boolean)

/** Worker-owned selection predicates and stable moves, including occurrences outside loaded pages. */
internal class QueueSelection(private val sequence: QueueSequence) {
    private val db get() = sequence.db

    fun create(queueId: String, all: Boolean = false): QueueSelectionHandle {
        transaction()
        val handle = QueueSelectionHandle(UUID.randomUUID().toString(), queueId, sequence.revision(queueId).revision, 0, all)
        db.execSQL("INSERT INTO queue_sequence_selections (id,queueId,sequenceRevision,revision,allEntries) VALUES (?,?,?,?,?)",
            arrayOf(handle.id, queueId, handle.sequenceRevision, handle.revision, all))
        return handle
    }

    /** Set one bounded batch. All IDs must belong to this exact queue before any selection is changed. */
    fun set(handle: QueueSelectionHandle, ids: Set<String>, selected: Boolean): QueueSelectionHandle {
        validate(handle)
        require(ids.size <= QueueSequence.PageSize)
        ids.forEach { require(sequence.position(handle.queueId, it) != null) { "The selected occurrence is missing" } }
        if (ids.isEmpty()) return handle
        db.compileStatement(if (selected == handle.all)
            "DELETE FROM queue_sequence_selection_exceptions WHERE selectionId = ? AND entryId = ?"
        else "INSERT OR IGNORE INTO queue_sequence_selection_exceptions (selectionId,entryId,position) VALUES (?,?,?)").use { change ->
            ids.forEach { id ->
                change.clearBindings()
                change.bindString(1, handle.id)
                change.bindString(2, id)
                val position = requireNotNull(sequence.position(handle.queueId, id))
                if (selected != handle.all) change.bindLong(3, position.toLong())
                if (change.executeUpdateDelete() != 0) updateRanges(handle.id, position, selected != handle.all)
            }
        }
        db.execSQL("UPDATE queue_sequence_selections SET revision = revision + 1 WHERE id = ?", arrayOf(handle.id))
        return handle.copy(revision = handle.revision + 1)
    }

    fun count(handle: QueueSelectionHandle): Int {
        validate(handle)
        val exceptions = db.query("SELECT COUNT(*) FROM queue_sequence_selection_exceptions WHERE selectionId = ?",
            arrayOf(handle.id)).use { check(it.moveToFirst()); it.getInt(0) }
        return if (handle.all) sequence.revision(handle.queueId).count - exceptions else exceptions
    }

    /** Seek from a physical position. The last returned position plus one requests the next selected page. */
    fun page(handle: QueueSelectionHandle, start: Int, size: Int): QueueSequencePage {
        validate(handle)
        require(start >= 0 && size in 1..QueueSequence.PageSize)
        val entries = ArrayList<PositionedQueueEntry>()
        rows(handle, start, null, true, size) { position, id ->
            entries.add(requireNotNull(sequence.occurrence(handle.queueId, id)).also { check(it.position == position) })
        }
        return QueueSequencePage(sequence.revision(handle.queueId), start, entries)
    }

    /** Source order is retained for both selected and remaining occurrences, exactly as OccurrenceMoves.before. */
    fun moveBefore(handle: QueueSelectionHandle, beforeId: String?) {
        validate(handle)
        require(count(handle) > 0) { "The moved selection is empty" }
        val before = beforeId?.let { id ->
            requireNotNull(sequence.position(handle.queueId, id)) { "The insertion target is missing" }.also {
                require(!contains(handle, id)) { "The insertion target is part of the moved selection" }
            }
        } ?: sequence.revision(handle.queueId).count
        db.execSQL("DELETE FROM queue_sequence_edit_positions WHERE queueId = ?", arrayOf(handle.queueId))
        var target = 0L
        db.compileStatement("INSERT INTO queue_sequence_edit_positions (queueId,entryId,position) VALUES (?,?,?)").use { insert ->
            fun stage(start: Int, end: Int?, selected: Boolean) {
                var next = start
                while (true) {
                    var loaded = 0
                    rows(handle, next, end, selected, QueueSequence.PageSize) { position, id ->
                        insert.clearBindings()
                        insert.bindString(1, handle.queueId)
                        insert.bindString(2, id)
                        insert.bindLong(3, target++)
                        insert.executeInsert()
                        next = position + 1
                        loaded++
                    }
                    if (loaded < QueueSequence.PageSize) break
                }
            }
            stage(0, before, false)
            stage(0, null, true)
            stage(before, null, false)
        }
        check(target == sequence.revision(handle.queueId).count.toLong())
        val changed = db.query("SELECT 1 FROM queue_sequence_entries e JOIN queue_sequence_edit_positions p ON p.entryId = e.id " +
            "WHERE p.queueId = ? AND e.position != p.position LIMIT 1", arrayOf(handle.queueId)).use { it.moveToFirst() }
        if (changed) {
            // Every old position is parked below zero before any new nonnegative position is assigned.
            db.execSQL("UPDATE queue_sequence_entries SET position = -position - 1 WHERE queueId = ?", arrayOf(handle.queueId))
            db.execSQL("UPDATE queue_sequence_entries SET position = (SELECT p.position FROM queue_sequence_edit_positions p " +
                "WHERE p.queueId = ? AND p.entryId = queue_sequence_entries.id) WHERE queueId = ?",
                arrayOf(handle.queueId, handle.queueId))
            db.execSQL("UPDATE queue_sequence_entries SET manualPosition = position WHERE queueId = ?", arrayOf(handle.queueId))
            db.execSQL("UPDATE queue_sequence_state SET sort = 'Manual', ascending = 1 WHERE queueId = ?", arrayOf(handle.queueId))
            sequence.changed(handle.queueId)
        }
        db.execSQL("DELETE FROM queue_sequence_edit_positions WHERE queueId = ?", arrayOf(handle.queueId))
        close(handle.id)
    }

    fun close(selectionId: String) {
        transaction()
        db.execSQL("DELETE FROM queue_sequence_selections WHERE id = ?", arrayOf(selectionId))
    }

    private fun contains(handle: QueueSelectionHandle, id: String): Boolean {
        val exception = db.query("SELECT 1 FROM queue_sequence_selection_exceptions WHERE selectionId = ? AND entryId = ?",
            arrayOf(handle.id, id)).use { it.moveToFirst() }
        return handle.all != exception
    }

    private fun rows(handle: QueueSelectionHandle, start: Int, end: Int?, selected: Boolean, limit: Int,
        consume: (Int, String) -> Unit) {
        val finish = end ?: sequence.revision(handle.queueId).count
        if (handle.all != selected) {
            db.query("SELECT position,entryId FROM queue_sequence_selection_exceptions " +
                "WHERE selectionId = ? AND position >= ? AND position < ? ORDER BY position LIMIT ?",
                arrayOf(handle.id, start, finish, limit)).use { rows ->
                while (rows.moveToNext()) consume(rows.getInt(0), rows.getString(1))
            }
            return
        }
        // Complement pages seek over whole excluded runs, including an almost entirely deselected queue.
        var next = start
        var remaining = limit
        while (next < finish && remaining > 0) {
            val excludedThrough = db.query("SELECT rangeEnd FROM queue_sequence_selection_ranges " +
                "WHERE selectionId = ? AND rangeStart <= ? ORDER BY rangeStart DESC LIMIT 1",
                arrayOf(handle.id, next)).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            if (excludedThrough >= next) {
                next = excludedThrough + 1
                continue
            }
            val until = db.query("SELECT rangeStart FROM queue_sequence_selection_ranges " +
                "WHERE selectionId = ? AND rangeStart > ? ORDER BY rangeStart LIMIT 1",
                arrayOf(handle.id, next)).use { if (it.moveToFirst()) minOf(finish, it.getInt(0)) else finish }
            db.query("SELECT position,id FROM queue_sequence_entries WHERE queueId = ? " +
                "AND position >= ? AND position < ? ORDER BY position LIMIT ?",
                arrayOf(handle.queueId, next, until, remaining)).use { rows ->
                while (rows.moveToNext()) {
                    consume(rows.getInt(0), rows.getString(1))
                    remaining--
                }
            }
            next = until
        }
    }

    private fun updateRanges(selectionId: String, position: Int, add: Boolean) {
        if (add) {
            val left = db.query("SELECT rangeStart FROM queue_sequence_selection_ranges WHERE selectionId = ? AND rangeEnd = ?",
                arrayOf(selectionId, position - 1)).use { if (it.moveToFirst()) it.getInt(0) else position }
            val right = db.query("SELECT rangeEnd FROM queue_sequence_selection_ranges WHERE selectionId = ? AND rangeStart = ?",
                arrayOf(selectionId, position + 1)).use { if (it.moveToFirst()) it.getInt(0) else position }
            db.execSQL("DELETE FROM queue_sequence_selection_ranges WHERE selectionId = ? AND (rangeStart = ? OR rangeStart = ?)",
                arrayOf(selectionId, left, position + 1))
            range(selectionId, left, right)
        } else {
            val bounds = db.query("SELECT rangeStart,rangeEnd FROM queue_sequence_selection_ranges " +
                "WHERE selectionId = ? AND rangeStart <= ? ORDER BY rangeStart DESC LIMIT 1",
                arrayOf(selectionId, position)).use {
                check(it.moveToFirst() && it.getInt(1) >= position)
                it.getInt(0) to it.getInt(1)
            }
            db.execSQL("DELETE FROM queue_sequence_selection_ranges WHERE selectionId = ? AND rangeStart = ?",
                arrayOf(selectionId, bounds.first))
            if (bounds.first < position) range(selectionId, bounds.first, position - 1)
            if (position < bounds.second) range(selectionId, position + 1, bounds.second)
        }
    }

    private fun range(selectionId: String, start: Int, end: Int) {
        db.execSQL("INSERT INTO queue_sequence_selection_ranges (selectionId,rangeStart,rangeEnd) VALUES (?,?,?)",
            arrayOf(selectionId, start, end))
    }

    fun sweepStale() {
        transaction()
        db.execSQL("DELETE FROM queue_sequence_selections WHERE sequenceRevision != " +
            "(SELECT revision FROM queue_sequence_state WHERE queueId = queue_sequence_selections.queueId)")
    }

    /** Handles belong to one actor lifetime; a new restore releases abandoned handles from the previous one. */
    fun reset() {
        transaction()
        db.execSQL("DELETE FROM queue_sequence_selections")
    }

    private fun validate(handle: QueueSelectionHandle) {
        transaction()
        check(sequence.revision(handle.queueId).revision == handle.sequenceRevision) { "The queue selection is stale" }
        db.query("SELECT queueId,sequenceRevision,revision,allEntries FROM queue_sequence_selections WHERE id = ?",
            arrayOf(handle.id)).use {
            check(it.moveToFirst() && it.getString(0) == handle.queueId && it.getLong(1) == handle.sequenceRevision &&
                it.getLong(2) == handle.revision && (it.getInt(3) != 0) == handle.all) { "The queue selection is stale" }
        }
    }

    private fun transaction() { sequence.checkWorker(); check(db.inTransaction()) { "Queue selections require a transaction" } }

    companion object {
        fun createSchema(db: SupportSQLiteDatabase) {
            QueueSequence(db).checkWorker()
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_selections (
                id TEXT NOT NULL PRIMARY KEY, queueId TEXT NOT NULL, sequenceRevision INTEGER NOT NULL,
                revision INTEGER NOT NULL, allEntries INTEGER NOT NULL,
                FOREIGN KEY(queueId) REFERENCES queue_sequence_state(queueId) ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_selections_queue ON queue_sequence_selections(queueId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_selection_exceptions (
                selectionId TEXT NOT NULL, entryId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(selectionId,entryId),
                FOREIGN KEY(selectionId) REFERENCES queue_sequence_selections(id) ON DELETE CASCADE,
                FOREIGN KEY(entryId) REFERENCES queue_sequence_entries(id) ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_sequence_selection_position ON queue_sequence_selection_exceptions(selectionId,position)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_selection_ranges (
                selectionId TEXT NOT NULL, rangeStart INTEGER NOT NULL, rangeEnd INTEGER NOT NULL,
                PRIMARY KEY(selectionId,rangeStart),
                FOREIGN KEY(selectionId) REFERENCES queue_sequence_selections(id) ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_sequence_selection_range_end ON queue_sequence_selection_ranges(selectionId,rangeEnd)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_selection_entry ON queue_sequence_selection_exceptions(entryId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_edit_positions (
                queueId TEXT NOT NULL, entryId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(queueId,entryId),
                FOREIGN KEY(queueId) REFERENCES queue_sequence_state(queueId) ON DELETE CASCADE,
                FOREIGN KEY(entryId) REFERENCES queue_sequence_entries(id) ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_edit_entry ON queue_sequence_edit_positions(entryId)")
        }
    }
}

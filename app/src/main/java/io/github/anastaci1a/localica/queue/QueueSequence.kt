package io.github.anastaci1a.localica.queue

import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import java.util.UUID

/** A durable sequence revision, never a collection of all its occurrences. */
internal data class QueueSequenceRevision(val queueId: String, val revision: Long, val count: Int,
    val durationMs: Long, val knownDurations: Int)

internal data class PositionedQueueEntry(val position: Int, val entry: QueueEntry)

/** Pages belong to one committed sequence revision. Consumers discard pages after an edit. */
internal data class QueueSequencePage(val revision: QueueSequenceRevision, val start: Int,
    val entries: List<PositionedQueueEntry>)

/**
 * Worker-only SQL boundary for a queue's physical and saved manual order. Callers own the transaction.
 * A page is a range seek, not LIMIT/OFFSET. Edits may touch many database rows but never load them.
 * Song snapshots are shared by value; occurrence and originating-playlist identities stay independent.
 */
@WorkerThread
internal class QueueSequence(internal val db: SupportSQLiteDatabase) {
    init { worker() }

    internal fun checkWorker() = worker()

    fun revision(queueId: String): QueueSequenceRevision {
        worker()
        return db.query("SELECT revision, entryCount, durationMs, knownDurations FROM queue_sequence_state WHERE queueId = ?",
            arrayOf(queueId)).use { rows ->
            check(rows.moveToFirst()) { "The saved queue is missing" }
            QueueSequenceRevision(queueId, rows.getLong(0), rows.getInt(1), rows.getLong(2), rows.getInt(3))
        }
    }

    fun page(queueId: String, start: Int, size: Int): QueueSequencePage {
        worker()
        check(db.inTransaction()) { "Queue pages require a snapshot transaction" }
        require(start >= 0 && size in 1..PageSize)
        val source = revision(queueId)
        val entries = db.query("""
            SELECT e.position, e.id, s.snapshot, e.sourcePlaylistId
            FROM queue_sequence_entries e JOIN queue_sequence_songs s ON s.id = e.songId
            WHERE e.queueId = ? AND e.position >= ? AND e.position < ? ORDER BY e.position
        """.trimIndent(), arrayOf(queueId, start, start.toLong() + size)).use { rows ->
            val songs = HashMap<String, Track>()
            buildList {
                while (rows.moveToNext()) {
                    val snapshot = rows.getString(2)
                    add(PositionedQueueEntry(rows.getInt(0), QueueEntry(rows.getString(1),
                        songs.getOrPut(snapshot) { TrackSnapshot.decode(snapshot) },
                        if (rows.isNull(3)) null else rows.getString(3))))
                }
            }
        }
        return QueueSequencePage(source, start, entries)
    }

    fun entries(queueId: String, ids: Set<String>): List<QueueEntry> {
        worker()
        check(db.inTransaction())
        require(ids.size <= QueuePlaybackWindow.MaximumVisits)
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val args = arrayOf<Any>(queueId, *ids.toTypedArray())
        return db.query("SELECT e.id,s.snapshot,e.sourcePlaylistId FROM queue_sequence_entries e " +
            "JOIN queue_sequence_songs s ON s.id = e.songId WHERE e.queueId = ? AND e.id IN ($placeholders) ORDER BY e.position", args).use { rows ->
            val songs = HashMap<String, Track>()
            buildList {
                while (rows.moveToNext()) {
                    val snapshot = rows.getString(1)
                    add(QueueEntry(rows.getString(0), songs.getOrPut(snapshot) { TrackSnapshot.decode(snapshot) },
                        if (rows.isNull(2)) null else rows.getString(2)))
                }
            }
        }
    }

    fun position(queueId: String, occurrenceId: String): Int? {
        worker()
        return db.query("SELECT position FROM queue_sequence_entries WHERE queueId = ? AND id = ?",
            arrayOf(queueId, occurrenceId)).use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    fun occurrence(queueId: String, occurrenceId: String): PositionedQueueEntry? =
        position(queueId, occurrenceId)?.let { page(queueId, it, 1).entries.singleOrNull() }

    /** The input batch is bounded even when its producer streams a million occurrences. */
    fun append(queueId: String, batch: List<QueueEntry>) {
        mutation()
        require(batch.size <= PageSize)
        if (batch.isEmpty()) return
        val count = revision(queueId).count
        require(count.toLong() + batch.size <= Int.MAX_VALUE) { "Queue position exceeds the supported integer range" }
        val manual = db.query("SELECT COALESCE(MAX(manualPosition), -1) FROM queue_sequence_entries WHERE queueId = ?",
            arrayOf(queueId)).use { it.moveToFirst(); it.getLong(0) + 1 }
        db.compileStatement("INSERT INTO queue_sequence_entries (id,queueId,position,manualPosition,songId,sourcePlaylistId) VALUES (?,?,?,?,?,?)").use { insert ->
            val songs = HashMap<Track, Long>()
            batch.forEachIndexed { index, entry ->
                insert.clearBindings()
                insert.bindString(1, entry.id)
                insert.bindString(2, queueId)
                insert.bindLong(3, count.toLong() + index)
                insert.bindLong(4, manual + index)
                insert.bindLong(5, songs.getOrPut(entry.track) { song(entry.track) })
                entry.sourcePlaylistId?.let { insert.bindString(6, it) } ?: insert.bindNull(6)
                insert.executeInsert()
            }
        }
    }

    /** Insert immediately before the requested physical position without copying the tail into memory. */
    fun insert(queueId: String, position: Int, batch: List<QueueEntry>) {
        mutation()
        val count = revision(queueId).count
        require(position in 0..count && batch.size <= PageSize)
        if (batch.isEmpty()) return
        val manualBefore = if (position == 0) 0L else db.query(
            "SELECT manualPosition + 1 FROM queue_sequence_entries WHERE queueId = ? AND position = ?",
            arrayOf(queueId, position - 1)).use { check(it.moveToFirst()); it.getLong(0) }
        val manualEnd = db.query("SELECT COALESCE(MAX(manualPosition), -1) + 1 FROM queue_sequence_entries WHERE queueId = ?",
            arrayOf(queueId)).use { it.moveToFirst(); it.getLong(0) }
        append(queueId, batch)
        moveRange(queueId, count, batch.size, position)
        // Reconciliation inserts after the physical predecessor in the retained manual baseline.
        db.execSQL("UPDATE queue_sequence_entries SET manualPosition = -manualPosition - 1 WHERE queueId = ? AND manualPosition >= ?",
            arrayOf(queueId, manualEnd))
        db.execSQL("UPDATE queue_sequence_entries SET manualPosition = manualPosition + ? WHERE queueId = ? AND manualPosition >= ?",
            arrayOf(batch.size, queueId, manualBefore))
        db.execSQL("UPDATE queue_sequence_entries SET manualPosition = -manualPosition - 1 - ? + ? WHERE queueId = ? AND manualPosition < 0",
            arrayOf(manualEnd, manualBefore, queueId))
    }

    /** Move one contiguous block. Target is an insertion boundary in the original sequence. */
    fun moveRange(queueId: String, from: Int, size: Int, before: Int) {
        mutation()
        val count = revision(queueId).count
        require(size > 0 && from >= 0 && from.toLong() + size <= count && before in 0..count)
        if (before in from..from + size) return
        // Negative positions reserve the moving block. Index uniqueness remains true after every row write.
        db.execSQL("UPDATE queue_sequence_entries SET position = -position - 1 WHERE queueId = ? AND position >= ? AND position < ?",
            arrayOf(queueId, from, from.toLong() + size))
        if (before < from) {
            shift(queueId, before, from, size)
            db.execSQL("UPDATE queue_sequence_entries SET position = -position - 1 - ? + ? WHERE queueId = ? AND position < 0",
                arrayOf(from, before, queueId))
        } else {
            shift(queueId, from + size, before, -size)
            db.execSQL("UPDATE queue_sequence_entries SET position = -position - 1 - ? + ? WHERE queueId = ? AND position < 0",
                arrayOf(from, before - size, queueId))
        }
        changed(queueId)
    }

    fun move(queueId: String, occurrenceId: String, beforeId: String?) {
        mutation()
        require(beforeId != occurrenceId) { "The insertion target is part of the moved selection" }
        val from = requireNotNull(position(queueId, occurrenceId)) { "The moved occurrence is missing" }
        val before = beforeId?.let { requireNotNull(position(queueId, it)) { "The insertion target is missing" } }
            ?: revision(queueId).count
        if (before == from || before == from + 1) return
        moveRange(queueId, from, 1, before)
        adoptPhysicalOrder(queueId)
    }

    /** Removal leaves the caller responsible for choosing a replacement current occurrence. */
    fun remove(queueId: String, occurrenceId: String): Int? {
        mutation()
        val from = position(queueId, occurrenceId) ?: return null
        val count = revision(queueId).count
        db.execSQL("DELETE FROM queue_sequence_entries WHERE queueId = ? AND id = ?", arrayOf(queueId, occurrenceId))
        shift(queueId, from + 1, count, -1)
        return from
    }

    fun reverse(queueId: String) {
        mutation()
        val count = revision(queueId).count
        if (count < 2) return
        db.execSQL("UPDATE queue_sequence_entries SET position = -position - 1 WHERE queueId = ?", arrayOf(queueId))
        db.execSQL("UPDATE queue_sequence_entries SET position = ? + position WHERE queueId = ?", arrayOf(count, queueId))
        adoptPhysicalOrder(queueId)
        changed(queueId)
    }

    /** Fisher–Yates swaps indexed positions; SQLite never sorts a queue-sized temporary result. */
    fun randomize(queueId: String, currentFirst: String? = null, random: kotlin.random.Random = kotlin.random.Random.Default) {
        mutation()
        val count = revision(queueId).count
        if (count < 2) { adoptPhysicalOrder(queueId); changed(queueId); return }
        val anchor = currentFirst?.let { position(queueId, it) }
        if (anchor != null) moveRange(queueId, anchor, 1, 0)
        val first = if (anchor == null) 0 else 1
        db.compileStatement("UPDATE queue_sequence_entries SET position = ? WHERE queueId = ? AND position = ?").use { swap ->
            fun move(from: Int, to: Int) {
                swap.clearBindings()
                swap.bindLong(1, to.toLong())
                swap.bindString(2, queueId)
                swap.bindLong(3, from.toLong())
                check(swap.executeUpdateDelete() == 1) { "The queue permutation changed unexpectedly" }
            }
            for (index in count - 1 downTo first + 1) {
                val other = random.nextInt(first, index + 1)
                if (index == other) continue
                move(index, -1)
                move(other, index)
                move(-1, other)
            }
        }
        adoptPhysicalOrder(queueId)
        changed(queueId)
    }

    /** Duplicate from a frozen SQL source, including self-duplication, without materializing its IDs. */
    fun duplicate(sourceId: String, targetId: String) {
        mutation()
        val source = revision(sourceId)
        val target = revision(targetId)
        require(source.count.toLong() + target.count <= Int.MAX_VALUE)
        val manual = db.query("SELECT COALESCE(MAX(manualPosition), -1) FROM queue_sequence_entries WHERE queueId = ?",
            arrayOf(targetId)).use { it.moveToFirst(); it.getLong(0) + 1 }
        val namespace = UUID.randomUUID().toString()
        db.execSQL("""
            INSERT INTO queue_sequence_entries (id,queueId,position,manualPosition,songId,sourcePlaylistId)
            SELECT ? || ':' || position, ?, position + ?, position + ?, songId, sourcePlaylistId
            FROM queue_sequence_entries WHERE queueId = ? AND position < ? ORDER BY position
        """.trimIndent(), arrayOf(namespace, targetId, target.count, manual, sourceId, source.count))
    }

    fun create(queueId: String) {
        mutation()
        db.execSQL("INSERT INTO queue_sequence_state (queueId,revision,entryCount,durationMs,knownDurations) VALUES (?,0,0,0,0)", arrayOf(queueId))
    }

    private fun song(track: Track): Long {
        val snapshot = TrackSnapshot.encode(track)
        db.compileStatement("INSERT OR IGNORE INTO queue_sequence_songs (libraryId,snapshot,durationMs) VALUES (?,?,?)").use {
            it.bindString(1, track.id)
            it.bindString(2, snapshot)
            track.durationMs?.let { value -> it.bindLong(3, value) } ?: it.bindNull(3)
            it.executeInsert()
        }
        return db.query("SELECT id FROM queue_sequence_songs WHERE snapshot = ?", arrayOf(snapshot)).use {
            check(it.moveToFirst()); it.getLong(0)
        }
    }

    private fun adoptPhysicalOrder(queueId: String) {
        db.execSQL("UPDATE queue_sequence_entries SET manualPosition = position WHERE queueId = ?", arrayOf(queueId))
        db.execSQL("UPDATE queue_sequence_state SET sort = 'Manual', ascending = 1 WHERE queueId = ?", arrayOf(queueId))
    }

    private fun shift(queueId: String, start: Int, end: Int, offset: Int) {
        if (start >= end || offset == 0) return
        // Reserve below every parked block and every legal position before returning to the positive range.
        val reserve = Int.MAX_VALUE.toLong() + 1
        db.execSQL("UPDATE queue_sequence_entries SET position = position + ? WHERE queueId = ? AND position >= ? AND position < ?",
            arrayOf(reserve, queueId, start, end))
        db.execSQL("UPDATE queue_sequence_entries SET position = position - ? + ? WHERE queueId = ? AND position >= ?",
            arrayOf(reserve, offset, queueId, reserve))
    }

    private fun changed(queueId: String) {
        db.execSQL("UPDATE queue_sequence_state SET revision = revision + 1 WHERE queueId = ?", arrayOf(queueId))
    }

    private fun mutation() { worker(); check(db.inTransaction()) { "Queue edits require a transaction" } }

    companion object {
        const val PageSize = 256
        private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Queue storage cannot run on Main" } }

        /** Installed by the owning database migration/create boundary, before any occurrence writer. */
        fun createSchema(db: SupportSQLiteDatabase) {
            worker()
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_state (
                queueId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, entryCount INTEGER NOT NULL,
                durationMs INTEGER NOT NULL, knownDurations INTEGER NOT NULL, sort TEXT NOT NULL DEFAULT 'Manual', ascending INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(queueId) REFERENCES queues(id) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, libraryId TEXT NOT NULL,
                snapshot TEXT NOT NULL UNIQUE, durationMs INTEGER, metadataReady INTEGER NOT NULL DEFAULT 1)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_entries (
                id TEXT NOT NULL PRIMARY KEY, queueId TEXT NOT NULL, position INTEGER NOT NULL,
                manualPosition INTEGER NOT NULL, songId INTEGER NOT NULL, sourcePlaylistId TEXT,
                FOREIGN KEY(queueId) REFERENCES queue_sequence_state(queueId) ON DELETE CASCADE,
                FOREIGN KEY(songId) REFERENCES queue_sequence_songs(id))""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_entries_queueId_ordinal ON queue_entries(queueId,ordinal)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_song_metadata ON queue_sequence_songs(metadataReady,id)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_manual_links (
                queueId TEXT NOT NULL, id TEXT NOT NULL, nextId TEXT, PRIMARY KEY(queueId,id),
                FOREIGN KEY(queueId) REFERENCES queue_sequence_state(queueId) ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_sequence_position ON queue_sequence_entries(queueId,position)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_manual ON queue_sequence_entries(queueId,manualPosition)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_song ON queue_sequence_entries(songId)")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS queue_sequence_insert AFTER INSERT ON queue_sequence_entries BEGIN
                UPDATE queue_sequence_state SET entryCount = entryCount + 1, revision = revision + 1,
                    durationMs = durationMs + COALESCE((SELECT durationMs FROM queue_sequence_songs WHERE id = NEW.songId), 0),
                    knownDurations = knownDurations + ((SELECT durationMs FROM queue_sequence_songs WHERE id = NEW.songId) IS NOT NULL)
                WHERE queueId = NEW.queueId;
                END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS queue_sequence_delete AFTER DELETE ON queue_sequence_entries BEGIN
                UPDATE queue_sequence_state SET entryCount = entryCount - 1, revision = revision + 1,
                    durationMs = durationMs - COALESCE((SELECT durationMs FROM queue_sequence_songs WHERE id = OLD.songId), 0),
                    knownDurations = knownDurations - ((SELECT durationMs FROM queue_sequence_songs WHERE id = OLD.songId) IS NOT NULL)
                WHERE queueId = OLD.queueId;
                END""")
            QueueSelection.createSchema(db)
        }
    }
}

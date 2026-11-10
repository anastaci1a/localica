package io.github.anastaci1a.localica.queue

import android.util.JsonReader
import android.util.JsonToken
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.library.enumNameOrNull
import java.io.InputStreamReader
import java.io.PushbackReader

/** One-time, transactional conversion of schema-7 rows. No occurrence-sized Kotlin collection is created. */
internal class QueueSequenceMigration(private val sequence: QueueSequence) {
    private val db get() = sequence.db

    fun import(queueId: String) {
        sequence.checkWorker()
        check(db.inTransaction()) { "Queue migration requires a transaction" }
        db.query("SELECT 1 FROM queue_sequence_state WHERE queueId = ?", arrayOf(queueId)).use {
            if (it.moveToFirst()) return
        }
        // The unique, disk-backed snapshot index does the sharing; DISTINCT must not build a temp result.
        db.execSQL("""
            INSERT OR IGNORE INTO queue_sequence_songs (libraryId,snapshot,durationMs,metadataReady)
            SELECT '',track,NULL,0 FROM queue_entries WHERE queueId = ?
        """.trimIndent(), arrayOf(queueId))
        completeSongMetadata()
        sequence.create(queueId)
        var ordinal = Int.MIN_VALUE.toLong() - 1
        var rowId = 0L
        var position = 0L
        db.compileStatement("INSERT INTO queue_sequence_entries (id,queueId,position,manualPosition,songId,sourcePlaylistId) VALUES (?,?,?,?,?,?)").use { insert ->
            while (true) {
                // Rowid is the existing restore's stable tie-breaker, including across page boundaries.
                val imported = db.query("""
                    SELECT e.rowid, e.ordinal, e.id, s.id, e.sourcePlaylistId
                    FROM queue_entries e JOIN queue_sequence_songs s ON s.snapshot = e.track
                    WHERE e.queueId = ? AND (e.ordinal,e.rowid) > (?,?)
                    ORDER BY e.ordinal,e.rowid LIMIT ?
                """.trimIndent(), arrayOf(queueId, ordinal, rowId, QueueSequence.PageSize)).use { rows ->
                    var count = 0
                    while (rows.moveToNext()) {
                        rowId = rows.getLong(0)
                        ordinal = rows.getLong(1)
                        insert.clearBindings()
                        insert.bindString(1, rows.getString(2))
                        insert.bindString(2, queueId)
                        insert.bindLong(3, position)
                        insert.bindLong(4, position)
                        insert.bindLong(5, rows.getLong(3))
                        if (rows.isNull(4)) insert.bindNull(6) else insert.bindString(6, rows.getString(4))
                        insert.executeInsert()
                        position++
                        count++
                    }
                    count
                }
                if (imported < QueueSequence.PageSize) break
            }
        }
        importManualOrder(queueId)
    }

    private fun completeSongMetadata() {
        db.compileStatement("UPDATE queue_sequence_songs SET libraryId = ?, durationMs = ?, metadataReady = 1 WHERE id = ?").use { update ->
            while (true) {
                val count = db.query("SELECT id,snapshot FROM queue_sequence_songs WHERE metadataReady = 0 ORDER BY id LIMIT ?",
                    arrayOf(QueueSequence.PageSize)).use { rows ->
                    var count = 0
                    while (rows.moveToNext()) {
                        val track = TrackSnapshot.decode(rows.getString(1))
                        update.clearBindings()
                        update.bindString(1, track.id)
                        track.durationMs?.let { update.bindLong(2, it) } ?: update.bindNull(2)
                        update.bindLong(3, rows.getLong(0))
                        check(update.executeUpdateDelete() == 1)
                        count++
                    }
                    count
                }
                if (count < QueueSequence.PageSize) break
            }
        }
    }

    private fun importManualOrder(queueId: String) {
        var head: String? = null
        var previous: String? = null
        var sort = TrackSort.Manual
        var ascending = true
        val reader = PushbackReader(InputStreamReader(LegacyQueueOrderStream(db, queueId), Charsets.UTF_8), 1)
        reader.use {
            var first = reader.read()
            while (first >= 0 && first.toChar().isWhitespace()) first = reader.read()
            if (first < 0) return
            reader.unread(first)
            val json = JsonReader(reader)
            var manualSeen = false
            json.beginObject()
            while (json.hasNext()) when (json.nextName()) {
                "manual" -> {
                    manualSeen = true
                    json.beginArray()
                    while (json.hasNext()) {
                        val id = json.nextString()
                        db.compileStatement("""
                            INSERT OR IGNORE INTO queue_sequence_manual_links (queueId,id,nextId)
                            SELECT queueId,id,NULL FROM queue_sequence_entries WHERE queueId = ? AND id = ?
                        """.trimIndent()).use { insert ->
                            insert.bindString(1, queueId)
                            insert.bindString(2, id)
                            if (insert.executeInsert() != -1L) {
                                if (previous == null) head = id else link(queueId, previous!!, id)
                                previous = id
                            }
                        }
                    }
                    json.endArray()
                }
                "sort" -> sort = if (json.peek() == JsonToken.STRING) {
                    enumNameOrNull<TrackSort>(json.nextString()) ?: TrackSort.Manual
                } else { json.skipValue(); TrackSort.Manual }
                "ascending" -> ascending = when (json.peek()) {
                    JsonToken.BOOLEAN -> json.nextBoolean()
                    JsonToken.STRING -> !json.nextString().equals("false", ignoreCase = true)
                    else -> { json.skipValue(); true }
                }
                else -> json.skipValue()
            }
            json.endObject()
            require(manualSeen) { "The saved manual order is missing" }
        }
        // Reproduce SavedOrder.reconcile: insert a missing occurrence after its physical predecessor,
        // retaining the independent manual order of all surviving saved occurrences.
        var last = -1L
        previous = null
        while (true) {
            val count = db.query("SELECT position,id FROM queue_sequence_entries WHERE queueId = ? AND position > ? ORDER BY position LIMIT ?",
                arrayOf(queueId, last, QueueSequence.PageSize)).use { rows ->
                var count = 0
                while (rows.moveToNext()) {
                    last = rows.getLong(0)
                    val id = rows.getString(1)
                    val exists = db.query("SELECT 1 FROM queue_sequence_manual_links WHERE queueId = ? AND id = ?",
                        arrayOf(queueId, id)).use { it.moveToFirst() }
                    if (!exists) {
                        val following = if (previous == null) head else next(queueId, previous!!)
                        db.execSQL("INSERT INTO queue_sequence_manual_links (queueId,id,nextId) VALUES (?,?,?)",
                            arrayOf(queueId, id, following))
                        if (previous == null) head = id else link(queueId, previous!!, id)
                    }
                    previous = id
                    count++
                }
                count
            }
            if (count < QueueSequence.PageSize) break
        }
        var id = head
        var position = 0L
        val expected = sequence.revision(queueId).count
        db.compileStatement("UPDATE queue_sequence_entries SET manualPosition = ? WHERE queueId = ? AND id = ?").use { update ->
            while (id != null) {
                check(position < expected) { "Invalid saved manual order" }
                update.clearBindings()
                update.bindLong(1, position++)
                update.bindString(2, queueId)
                update.bindString(3, id!!)
                check(update.executeUpdateDelete() == 1)
                id = next(queueId, id!!)
            }
        }
        check(position == expected.toLong()) { "Incomplete saved manual order" }
        db.execSQL("DELETE FROM queue_sequence_manual_links WHERE queueId = ?", arrayOf(queueId))
        db.execSQL("UPDATE queue_sequence_state SET sort = ?, ascending = ? WHERE queueId = ?",
            arrayOf(sort.name, ascending, queueId))
    }

    private fun next(queueId: String, id: String): String? = db.query(
        "SELECT nextId FROM queue_sequence_manual_links WHERE queueId = ? AND id = ?", arrayOf(queueId, id)).use {
        check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
    }

    private fun link(queueId: String, from: String, to: String) {
        db.execSQL("UPDATE queue_sequence_manual_links SET nextId = ? WHERE queueId = ? AND id = ?", arrayOf(to, queueId, from))
    }

}

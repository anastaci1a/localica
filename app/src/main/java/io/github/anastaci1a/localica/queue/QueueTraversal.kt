package io.github.anastaci1a.localica.queue

import java.util.UUID

/** Durable traversal pages. Actual history remains in SQL; projected ordered neighbors are not history. */
internal class QueueTraversal(private val sequence: QueueSequence) {
    private val db get() = sequence.db
    internal data class State(val queueId: String, val namespace: String, val cursor: Int,
        val historySize: Int, val cycle: Long, val modes: JourneyModes, val anchor: String?)

    fun restore(queueId: String, currentEntryId: String?, modes: JourneyModes): QueuePlaybackWindow {
        sequence.checkWorker()
        check(db.inTransaction())
        var state = state(queueId)
        if (state == null) {
            val old = db.query("SELECT historySize,cursor,cycle,shuffle,loop,reshuffle,orderedTraversalId,orderedAnchorToken FROM queue_journeys WHERE queueId = ?",
                arrayOf(queueId)).use { rows ->
                if (!rows.moveToFirst()) null else State(queueId,
                    if (rows.isNull(6)) UUID.randomUUID().toString() else rows.getString(6), rows.getInt(1), rows.getInt(0),
                    rows.getLong(2), JourneyModes(rows.getInt(3) != 0, rows.getInt(4) != 0, rows.getInt(5) != 0),
                    if (rows.isNull(7)) null else rows.getString(7))
            }
            state = old ?: State(queueId, UUID.randomUUID().toString(), -1, 0, 0, modes, null)
            db.execSQL("INSERT INTO queue_sequence_traversal (queueId,namespace,cursor,historySize,cycle,shuffle,loop,reshuffle,anchorToken) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf(queueId, state.namespace, state.cursor, state.historySize, state.cycle,
                    state.modes.shuffle, state.modes.loop, state.modes.reshuffle, state.anchor))
            if (old != null) {
                db.execSQL("""
                    INSERT INTO queue_sequence_history (token,queueId,position,entryId,cycle)
                    SELECT v.token,v.queueId,v.ordinal,v.entryId,v.cycle FROM queue_visits v
                    JOIN queue_sequence_entries e ON e.id = v.entryId AND e.queueId = v.queueId
                    WHERE v.queueId = ? AND v.ordinal < ?
                """.trimIndent(), arrayOf(queueId, old.historySize))
                db.execSQL("""
                    INSERT INTO queue_sequence_plan (queueId,cycle,position,entryId,visited,scheduled,token)
                    SELECT m.queueId,m.cycle,m.ordinal,m.entryId,m.visited,
                        CASE WHEN m.cycle = ? AND m.visited = 0 AND EXISTS
                            (SELECT 1 FROM queue_sequence_history h WHERE h.queueId = m.queueId AND h.entryId = m.entryId
                                AND h.cycle = m.cycle AND h.position > ?) THEN 1 ELSE 0 END,
                        (SELECT v.token FROM queue_visits v WHERE v.queueId = m.queueId AND v.cycle = m.cycle
                            AND v.entryId = m.entryId AND v.ordinal >= ? ORDER BY v.ordinal LIMIT 1)
                    FROM queue_cycle_members m JOIN queue_sequence_entries e ON e.id = m.entryId AND e.queueId = m.queueId
                    WHERE m.queueId = ?
                """.trimIndent(), arrayOf(old.cycle, old.cursor, old.historySize, queueId))
            }
        }
        val selected = currentEntryId?.takeIf { sequence.position(queueId, it) != null }
            ?: sequence.page(queueId, 0, 1).entries.firstOrNull()?.entry?.id
        if (selected == null) return QueuePlaybackWindow(emptyList(), null, emptyMap(), modes)
        val current = historyAt(queueId, state.cursor)
        if (current == null || current.entryId != selected) {
            val visit = QueueVisit(selected, state.cycle)
            val insertion = state.cursor + 1
            shiftHistory(queueId, insertion, 1)
            db.execSQL("INSERT INTO queue_sequence_history (token,queueId,position,entryId,cycle) VALUES (?,?,?,?,?)",
                arrayOf(visit.token, queueId, insertion, visit.entryId, visit.cycle))
            state = state.copy(cursor = insertion, historySize = state.historySize + 1,
                namespace = if (modes.shuffle) state.namespace else UUID.randomUUID().toString(),
                anchor = if (modes.shuffle) null else visit.token)
            write(state)
        }
        return window(state)
    }

    fun window(queueId: String): QueuePlaybackWindow {
        sequence.checkWorker()
        check(db.inTransaction())
        return window(requireNotNull(state(queueId)))
    }

    private fun window(state: State): QueuePlaybackWindow {
        val selected = historyAt(state.queueId, state.cursor)
            ?: return QueuePlaybackWindow(emptyList(), null, emptyMap(), state.modes)
        return if (state.modes.shuffle) shuffledWindow(state, selected) else orderedWindow(state, selected)
    }

    private fun orderedWindow(state: State, selected: QueueVisit): QueuePlaybackWindow {
        val count = sequence.revision(state.queueId).count
        if (count == 0) return QueuePlaybackWindow(emptyList(), null, emptyMap(), state.modes)
        val index = sequence.position(state.queueId, selected.entryId)
            ?: error("The current occurrence is missing")
        val anchor = state.anchor?.let { token -> historyToken(state.queueId, token) }
        val positions = LinkedHashMap<String, Int>()
        val visits = buildList {
            for (offset in -QueuePlaybackWindow.Radius..QueuePlaybackWindow.Radius) {
                val position = index.toLong() + offset
                val shift = Math.floorDiv(position, count.toLong())
                if (!state.modes.loop && shift != 0L) continue
                if (shift > 0 && selected.cycle > Long.MAX_VALUE - shift ||
                    shift < 0 && selected.cycle < Long.MIN_VALUE - shift) continue
                val physical = Math.floorMod(position, count.toLong()).toInt()
                val id = db.query("SELECT id FROM queue_sequence_entries WHERE queueId = ? AND position = ?",
                    arrayOf(state.queueId, physical)).use { check(it.moveToFirst()); it.getString(0) }
                positions[id] = physical
                val cycle = selected.cycle + shift
                add(when {
                    offset == 0 -> selected
                    anchor?.entryId == id && anchor.cycle == cycle -> anchor
                    else -> QueueVisit(id, cycle, token(state.namespace, id, cycle))
                })
            }
        }
        return QueuePlaybackWindow(visits, selected.token, positions, state.modes)
    }

    private fun shuffledWindow(state: State, selected: QueueVisit): QueuePlaybackWindow {
        val start = (state.cursor - QueuePlaybackWindow.Radius).coerceAtLeast(0)
        val history = db.query("""
            SELECT entryId,cycle,token FROM queue_sequence_history
            WHERE queueId = ? AND position >= ? AND position <= ? ORDER BY position
        """.trimIndent(), arrayOf(state.queueId, start, state.cursor.toLong() + QueuePlaybackWindow.Radius)).use { rows ->
            buildList { while (rows.moveToNext()) add(QueueVisit(rows.getString(0), rows.getLong(1), rows.getString(2))) }
        }
        val visits = history.toMutableList()
        val desired = state.cursor - start + QueuePlaybackWindow.Radius + 1
        val lastCycle = if (!state.modes.loop) state.cycle else minOf(Long.MAX_VALUE - 2, state.cycle) + 2
        for (cycle in state.cycle..lastCycle) {
            if (visits.size >= desired) break
            db.query("""
                SELECT entryId,token FROM queue_sequence_plan
                WHERE queueId = ? AND cycle = ? AND visited = 0 AND scheduled = 0 ORDER BY position LIMIT ?
            """.trimIndent(), arrayOf(state.queueId, cycle, desired - visits.size)).use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0)
                    val visitToken = if (rows.isNull(1)) token(state.namespace, id, cycle) else rows.getString(1)
                    visits.add(QueueVisit(id, cycle, visitToken))
                }
            }
        }
        val positions = visits.map { it.entryId }.distinct().associateWith {
            requireNotNull(sequence.position(state.queueId, it))
        }
        return QueuePlaybackWindow(visits, selected.token, positions, state.modes)
    }

    private fun state(queueId: String): State? = db.query("SELECT namespace,cursor,historySize,cycle,shuffle,loop,reshuffle,anchorToken FROM queue_sequence_traversal WHERE queueId = ?",
        arrayOf(queueId)).use { rows ->
        if (!rows.moveToFirst()) null else State(queueId, rows.getString(0), rows.getInt(1), rows.getInt(2), rows.getLong(3),
            JourneyModes(rows.getInt(4) != 0, rows.getInt(5) != 0, rows.getInt(6) != 0), if (rows.isNull(7)) null else rows.getString(7))
    }

    private fun historyAt(queueId: String, position: Int): QueueVisit? = db.query(
        "SELECT entryId,cycle,token FROM queue_sequence_history WHERE queueId = ? AND position = ?", arrayOf(queueId, position)).use {
        if (!it.moveToFirst()) null else QueueVisit(it.getString(0), it.getLong(1), it.getString(2))
    }

    private fun historyToken(queueId: String, token: String): QueueVisit? = db.query(
        "SELECT entryId,cycle FROM queue_sequence_history WHERE queueId = ? AND token = ?", arrayOf(queueId, token)).use {
        if (!it.moveToFirst()) null else QueueVisit(it.getString(0), it.getLong(1), token)
    }

    private fun write(state: State) {
        db.execSQL("UPDATE queue_sequence_traversal SET namespace = ?, cursor = ?, historySize = ?, cycle = ?, shuffle = ?, loop = ?, reshuffle = ?, anchorToken = ? WHERE queueId = ?",
            arrayOf(state.namespace, state.cursor, state.historySize, state.cycle, state.modes.shuffle, state.modes.loop,
                state.modes.reshuffle, state.anchor, state.queueId))
    }

    private fun shiftHistory(queueId: String, start: Int, offset: Int) {
        val reserve = Int.MAX_VALUE.toLong() + 1
        db.execSQL("UPDATE queue_sequence_history SET position = position + ? WHERE queueId = ? AND position >= ?",
            arrayOf(reserve, queueId, start))
        db.execSQL("UPDATE queue_sequence_history SET position = position - ? + ? WHERE queueId = ? AND position >= ?",
            arrayOf(reserve, offset, queueId, reserve))
    }

    companion object {
        private fun token(namespace: String, id: String, cycle: Long): String = UUID.nameUUIDFromBytes(
            "${namespace.length}:$namespace:$cycle:${id.length}:$id".toByteArray(Charsets.UTF_8)).toString()

        fun createSchema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_traversal (
                queueId TEXT NOT NULL PRIMARY KEY, namespace TEXT NOT NULL, cursor INTEGER NOT NULL,
                historySize INTEGER NOT NULL, cycle INTEGER NOT NULL, shuffle INTEGER NOT NULL,
                loop INTEGER NOT NULL, reshuffle INTEGER NOT NULL, anchorToken TEXT,
                FOREIGN KEY(queueId) REFERENCES queue_sequence_state(queueId) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_history (
                token TEXT NOT NULL PRIMARY KEY, queueId TEXT NOT NULL, position INTEGER NOT NULL,
                entryId TEXT NOT NULL, cycle INTEGER NOT NULL,
                FOREIGN KEY(queueId) REFERENCES queue_sequence_traversal(queueId) ON DELETE CASCADE,
                FOREIGN KEY(entryId) REFERENCES queue_sequence_entries(id) ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_sequence_history_position ON queue_sequence_history(queueId,position)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_history_entry ON queue_sequence_history(queueId,entryId,cycle,position)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_history_entryId ON queue_sequence_history(entryId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_plan (
                queueId TEXT NOT NULL, cycle INTEGER NOT NULL, position INTEGER NOT NULL,
                entryId TEXT NOT NULL, visited INTEGER NOT NULL, scheduled INTEGER NOT NULL, token TEXT,
                PRIMARY KEY(queueId,cycle,entryId),
                FOREIGN KEY(queueId) REFERENCES queue_sequence_traversal(queueId) ON DELETE CASCADE,
                FOREIGN KEY(entryId) REFERENCES queue_sequence_entries(id) ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_sequence_plan_position ON queue_sequence_plan(queueId,cycle,position)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_plan_pending ON queue_sequence_plan(queueId,cycle,visited,scheduled,position)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_sequence_plan_entryId ON queue_sequence_plan(entryId)")
        }
    }
}

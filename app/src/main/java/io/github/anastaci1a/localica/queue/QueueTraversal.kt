package io.github.anastaci1a.localica.queue

import java.util.UUID

/** Durable traversal pages. Actual history remains in SQL; projected ordered neighbors are not history. */
internal class QueueTraversal(private val sequence: QueueSequence) {
    private val db get() = sequence.db
    /** Opt-in diagnostic row writes, including staging rows; observers never affect the transaction. */
    internal var arrivalWriteObserver: ((Long) -> Unit)? = null
    internal data class State(val queueId: String, val namespace: String, val cursor: Int,
        val historySize: Int, val cycle: Long, val modes: JourneyModes, val anchor: String?)

    fun restore(queueId: String, currentEntryId: String?, modes: JourneyModes,
        random: kotlin.random.Random = kotlin.random.Random.Default): QueuePlaybackWindow {
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
                    WHERE m.queueId = ? AND m.cycle != ?
                """.trimIndent(), arrayOf(old.cycle, old.cursor, old.historySize, queueId, QueueJourney.BEHIND_START))
                // This reserved plan contains [neighbor id, queue index], not two occurrence IDs.
                val behind = db.query("SELECT entryId,ordinal FROM queue_cycle_members WHERE queueId = ? AND cycle = ? ORDER BY ordinal",
                    arrayOf(queueId, QueueJourney.BEHIND_START)).use { rows ->
                    var id: String? = null
                    var position: Int? = null
                    while (rows.moveToNext()) when (rows.getInt(1)) {
                        0 -> id = rows.getString(0)
                        1 -> position = rows.getString(0).toIntOrNull()
                    }
                    id?.let { it to position }
                }
                if (behind != null) {
                    db.execSQL("INSERT INTO queue_sequence_behind (queueId,entryId,queuePosition) VALUES (?,?,?)",
                        arrayOf(queueId, behind.first, behind.second))
                }
            }
        }
        val selected = currentEntryId?.takeIf { sequence.position(queueId, it) != null }
            ?: sequence.page(queueId, 0, 1).entries.firstOrNull()?.entry?.id
        if (selected == null) return QueuePlaybackWindow(emptyList(), null, emptyMap(), modes)
        val current = historyAt(queueId, state.cursor)
        var insertedCurrent = false
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
            insertedCurrent = true
        }
        if (state.modes.shuffle) {
            initializePlans(state, random)
            if (insertedCurrent) db.execSQL("UPDATE queue_sequence_plan SET visited = 1 WHERE queueId = ? AND cycle = ? AND entryId = ?",
                arrayOf(queueId, state.cycle, selected))
        }
        return window(state)
    }

    /** Persist an exact Fisher–Yates plan without allocating IDs or visits for the whole queue. */
    private fun initializePlans(state: State, random: kotlin.random.Random) {
        val count = sequence.revision(state.queueId).count
        if (count == 0) return
        val lastCycle = if (!state.modes.loop) state.cycle else minOf(Long.MAX_VALUE - 2, state.cycle) + 2
        for (cycle in state.cycle..lastCycle) {
            val exists = db.query("SELECT 1 FROM queue_sequence_plan WHERE queueId = ? AND cycle = ? LIMIT 1",
                arrayOf(state.queueId, cycle)).use { it.moveToFirst() }
            if (exists) continue
            if (cycle > state.cycle && !state.modes.reshuffle) {
                db.execSQL("INSERT INTO queue_sequence_plan (queueId,cycle,position,entryId,visited,scheduled,token) " +
                    "SELECT queueId,?,position,entryId,0,0,NULL FROM queue_sequence_plan WHERE queueId = ? AND cycle = ?",
                    arrayOf(cycle, state.queueId, cycle - 1))
                continue
            }
            db.execSQL("INSERT INTO queue_sequence_plan (queueId,cycle,position,entryId,visited,scheduled,token) " +
                "SELECT queueId,?,position,id,0,0,NULL FROM queue_sequence_entries WHERE queueId = ?",
                arrayOf(cycle, state.queueId))
            val anchor = if (cycle != state.cycle) null else db.query(
                "SELECT entryId FROM queue_sequence_history WHERE queueId = ? AND cycle = ? ORDER BY position LIMIT 1",
                arrayOf(state.queueId, cycle)).use { if (it.moveToFirst()) it.getString(0) else null }
            val anchorPosition = anchor?.let { id -> db.query(
                "SELECT position FROM queue_sequence_plan WHERE queueId = ? AND cycle = ? AND entryId = ?",
                arrayOf(state.queueId, cycle, id)).use { if (it.moveToFirst()) it.getInt(0) else null } }
            if (anchorPosition != null && anchorPosition > 0) {
                db.execSQL("UPDATE queue_sequence_plan SET position = -1 WHERE queueId = ? AND cycle = ? AND position = ?",
                    arrayOf(state.queueId, cycle, anchorPosition))
                val reserve = Int.MAX_VALUE.toLong() + 1
                db.execSQL("UPDATE queue_sequence_plan SET position = position + ? WHERE queueId = ? AND cycle = ? AND position >= 0 AND position < ?",
                    arrayOf(reserve, state.queueId, cycle, anchorPosition))
                db.execSQL("UPDATE queue_sequence_plan SET position = position - ? + 1 WHERE queueId = ? AND cycle = ? AND position >= ?",
                    arrayOf(reserve, state.queueId, cycle, reserve))
                db.execSQL("UPDATE queue_sequence_plan SET position = 0 WHERE queueId = ? AND cycle = ? AND position = -1",
                    arrayOf(state.queueId, cycle))
            }
            val first = if (anchorPosition == null) 0 else 1
            db.compileStatement("UPDATE queue_sequence_plan SET position = ? WHERE queueId = ? AND cycle = ? AND position = ?").use { swap ->
                fun move(from: Int, to: Int) {
                    swap.clearBindings()
                    swap.bindLong(1, to.toLong()); swap.bindString(2, state.queueId)
                    swap.bindLong(3, cycle); swap.bindLong(4, from.toLong())
                    check(swap.executeUpdateDelete() == 1)
                }
                for (index in count - 1 downTo first + 1) {
                    val other = random.nextInt(first, index + 1)
                    if (index == other) continue
                    move(index, -1); move(other, index); move(-1, other)
                }
            }
            db.execSQL("UPDATE queue_sequence_plan SET visited = 1 WHERE queueId = ? AND cycle = ? AND entryId IN " +
                "(SELECT entryId FROM queue_sequence_history WHERE queueId = ? AND cycle = ? AND position <= ?)",
                arrayOf(state.queueId, cycle, state.queueId, cycle, state.cursor))
        }
    }

    fun window(queueId: String): QueuePlaybackWindow {
        sequence.checkWorker()
        check(db.inTransaction())
        return window(requireNotNull(state(queueId)))
    }

    /**
     * The unplayed behind neighbor becomes real history only when its exact token arrives.
     * The caller must commit queue cursor metadata in this same transaction before publishing it.
     */
    fun arriveBehind(queueId: String, visitToken: String): QueuePlaybackWindow? {
        sequence.checkWorker()
        check(db.inTransaction())
        return measureArrival { arriveBehindVisit(queueId, visitToken) }
    }

    private fun arriveBehindVisit(queueId: String, visitToken: String): QueuePlaybackWindow? {
        val state = requireNotNull(state(queueId))
        if (!state.modes.shuffle) return null
        val visit = behind(state)?.takeIf { it.token == visitToken } ?: return null
        val historySize = Math.addExact(state.historySize, 1)
        shiftHistory(queueId, 0, 1)
        db.execSQL("INSERT INTO queue_sequence_history (token,queueId,position,entryId,cycle) VALUES (?,?,?,?,?)",
            arrayOf(visit.token, queueId, 0, visit.entryId, visit.cycle))
        if (visit.cycle == state.cycle) {
            db.execSQL("UPDATE queue_sequence_plan SET visited = 1, scheduled = 0 WHERE queueId = ? AND cycle = ? AND entryId = ?",
                arrayOf(queueId, state.cycle, visit.entryId))
        }
        val updated = state.copy(cursor = 0, historySize = historySize)
        write(updated)
        return window(updated)
    }

    /** Recorded shuffle arrivals retain the active cycle, even when retracing an older cycle. */
    fun arriveRecordedShuffle(queueId: String, visitToken: String): QueuePlaybackWindow? {
        sequence.checkWorker()
        check(db.inTransaction())
        return measureArrival {
            val state = requireNotNull(state(queueId))
            if (!state.modes.shuffle) return@measureArrival null
            val target = db.query("SELECT position,entryId,cycle FROM queue_sequence_history WHERE queueId = ? AND token = ?",
                arrayOf(queueId, visitToken)).use {
                if (!it.moveToFirst()) null else it.getInt(0) to QueueVisit(it.getString(1), it.getLong(2), visitToken)
            } ?: return@measureArrival null
            if (target.first == state.cursor) return@measureArrival window(state)
            val skipped = if (target.first <= state.cursor) 0 else db.query("""
                SELECT COUNT(*) FROM queue_sequence_history h LEFT JOIN queue_sequence_plan p
                    ON p.queueId = h.queueId AND p.cycle = h.cycle AND p.entryId = h.entryId
                WHERE h.queueId = ? AND h.position > ? AND h.position < ?
                    AND h.cycle = ? AND COALESCE(p.visited,0) = 0
            """.trimIndent(), arrayOf(queueId, state.cursor, target.first, state.cycle)).use { it.moveToFirst(); it.getInt(0) }
            if (skipped != 0) moveSkippedVisits(state, target.first, skipped)
            if (target.second.cycle == state.cycle) {
                db.execSQL("UPDATE queue_sequence_plan SET visited = 1, scheduled = 0 WHERE queueId = ? AND cycle = ? AND entryId = ?",
                    arrayOf(queueId, state.cycle, target.second.entryId))
            }
            val updated = state.copy(cursor = target.first - skipped)
            write(updated)
            window(updated)
        }
    }

    /** Move skipped unplayed visits after the target, with only one constant-size SQL page live at a time. */
    private fun moveSkippedVisits(state: State, target: Int, skipped: Int) {
        check(!db.query("SELECT 1 FROM queue_sequence_history_order WHERE queueId = ? LIMIT 1",
            arrayOf(state.queueId)).use { it.moveToFirst() })
        var after = state.cursor
        var seen = 0
        db.compileStatement("INSERT INTO queue_sequence_history_order (queueId,token,position) VALUES (?,?,?)").use { insert ->
            while (after < target) {
                var read = 0
                db.query("""
                    SELECT h.position,h.token,h.cycle,COALESCE(p.visited,0) FROM queue_sequence_history h
                    LEFT JOIN queue_sequence_plan p ON p.queueId = h.queueId AND p.cycle = h.cycle AND p.entryId = h.entryId
                    WHERE h.queueId = ? AND h.position > ? AND h.position <= ? ORDER BY h.position LIMIT ?
                """.trimIndent(), arrayOf(state.queueId, after, target, QueueSequence.PageSize)).use { rows ->
                    while (rows.moveToNext()) {
                        val position = rows.getInt(0)
                        val isSkipped = position < target && rows.getLong(2) == state.cycle && rows.getInt(3) == 0
                        if (isSkipped) seen++
                        val next = if (isSkipped) target - skipped + seen else position - seen
                        insert.clearBindings()
                        insert.bindString(1, state.queueId); insert.bindString(2, rows.getString(1)); insert.bindLong(3, next.toLong())
                        insert.executeInsert()
                        after = position
                        read++
                    }
                }
                check(read > 0)
            }
        }
        check(seen == skipped)
        db.execSQL("UPDATE queue_sequence_history SET position = -position - 1 WHERE queueId = ? AND position > ? AND position <= ?",
            arrayOf(state.queueId, state.cursor, target))
        db.execSQL("""
            UPDATE queue_sequence_history SET position = (SELECT s.position FROM queue_sequence_history_order s
                WHERE s.queueId = queue_sequence_history.queueId AND s.token = queue_sequence_history.token)
            WHERE queueId = ? AND position < 0
        """.trimIndent(), arrayOf(state.queueId))
        db.execSQL("DELETE FROM queue_sequence_history_order WHERE queueId = ?", arrayOf(state.queueId))
    }

    private inline fun <T> measureArrival(block: () -> T): T {
        val observer = arrivalWriteObserver ?: return block()
        val before = totalChanges()
        try { return block() } finally {
            val writes = totalChanges() - before
            try { observer(writes) } catch (_: Exception) { }
        }
    }

    private fun totalChanges(): Long = db.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }

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

    private fun behind(state: State): QueueVisit? = db.query("""
            SELECT h.entryId,h.cycle,h.token FROM queue_sequence_behind b
            JOIN queue_sequence_history h ON h.queueId = b.queueId AND h.position = 0
            WHERE b.queueId = ?
        """.trimIndent(), arrayOf(state.queueId)).use { rows ->
            if (!rows.moveToFirst()) null else {
                // The row enables behind projection. Its saved ID/index are import metadata, not live order.
                val oldestPosition = sequence.position(state.queueId, rows.getString(0))
                val count = sequence.revision(state.queueId).count
                val position = when {
                    oldestPosition != null && oldestPosition > 0 -> oldestPosition - 1
                    oldestPosition == 0 && state.modes.loop && count > 1 -> count - 1
                    else -> null
                }
                position?.let {
                    val id = db.query("SELECT id FROM queue_sequence_entries WHERE queueId = ? AND position = ?",
                        arrayOf(state.queueId, it)).use { entry -> check(entry.moveToFirst()); entry.getString(0) }
                    val oldest = rows.getString(2)
                    QueueVisit(id, rows.getLong(1), UUID.nameUUIDFromBytes(
                        "behind:${oldest.length}:$oldest:$id".toByteArray(Charsets.UTF_8)).toString())
                }
            }
        }

    private fun shuffledWindow(state: State, selected: QueueVisit): QueuePlaybackWindow {
        val start = (state.cursor - QueuePlaybackWindow.Radius).coerceAtLeast(0)
        val behind = if (start == 0) behind(state) else null
        val end = (state.cursor.toLong() + QueuePlaybackWindow.Radius + 1).let {
            if (behind == null) it else it.coerceAtMost(QueuePlaybackWindow.MaximumVisits.toLong() - 1)
        }
        val history = db.query("""
            SELECT entryId,cycle,token FROM queue_sequence_history
            WHERE queueId = ? AND position >= ? AND position < ? ORDER BY position
        """.trimIndent(), arrayOf(state.queueId, start, end)).use { rows ->
            buildList { while (rows.moveToNext()) add(QueueVisit(rows.getString(0), rows.getLong(1), rows.getString(2))) }
        }
        val visits = history.toMutableList()
        val desired = (end - start).toInt()
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
        if (behind != null) visits.add(0, behind)
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
            QueueSequence(db).checkWorker()
            db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_visits_queue_cycle_entry_ordinal ON queue_visits(queueId,cycle,entryId,ordinal)")
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
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_history_order (
                queueId TEXT NOT NULL, token TEXT NOT NULL, position INTEGER NOT NULL,
                PRIMARY KEY(queueId,token),
                FOREIGN KEY(queueId) REFERENCES queue_sequence_traversal(queueId) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS queue_sequence_behind (
                queueId TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, queuePosition INTEGER,
                FOREIGN KEY(queueId) REFERENCES queue_sequence_traversal(queueId) ON DELETE CASCADE)""")
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

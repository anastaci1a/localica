package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.QueueDatabase
import io.github.anastaci1a.localica.queue.QueueEdits
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.queue.QueueSequence
import io.github.anastaci1a.localica.queue.QueueSequenceMigration
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.SavedOrderCodec
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.TrackSort
import org.junit.Assert.*
import org.junit.Test

/** SQL boundary checks compare occurrence identities and origins with the existing pure model. */
class QueueSequenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun song(index: Int) = Track("song:$index", "Song $index", fileName = "$index.mp3",
        relativePath = "$index.mp3", durationMs = if (index % 3 == 0) null else index.toLong() + 1000)

    private fun database(test: (QueueSequence, androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val sql = room.openHelper.writableDatabase
            QueueSequence.createSchema(sql)
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT INTO queues (id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,ordinal,lastActivatedAt,orderState) VALUES ('q','Queue',0,NULL,0,0,'Off',0,0,'')")
                val sequence = QueueSequence(sql)
                sequence.create("q")
                test(sequence, sql)
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        } finally { room.close() }
    }

    private fun ids(sequence: QueueSequence) = sequence.page("q", 0, QueueSequence.PageSize).entries.map { it.entry.id }

    @Test fun rangeEditsMatchTheExistingOccurrenceModelAndMaintainTotals() = database { sequence, _ ->
        var expected = QueueEdits.create(List(19) { song(it % 7) }, 8, "Queue")
        sequence.append("q", expected.entries)
        assertEquals(expected.entries.map { it.id }, ids(sequence))
        for ((from, before) in listOf(12 to 2, 0 to 19, 18 to 4, 5 to 6, 4 to 5)) {
            val id = expected.entries[from].id
            val target = expected.entries.getOrNull(before)?.id
            expected = QueueEdits.moveBefore(expected, setOf(id), target)
            sequence.move("q", id, target)
            assertEquals(expected.entries.map { it.id }, ids(sequence))
        }
        val selected = expected.entries[4].id
        assertTrue(runCatching { QueueEdits.moveBefore(expected, setOf(selected), selected) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { sequence.move("q", selected, selected) }.exceptionOrNull() is IllegalArgumentException)
        expected.entries.filterIndexed { index, _ -> index % 4 == 0 }.map { it.id }.forEach { id ->
            expected = QueueEdits.remove(expected, id)
            sequence.remove("q", id)
            assertEquals(expected.entries.map { it.id }, ids(sequence))
        }
        sequence.reverse("q")
        expected = QueueEdits.reverse(expected)
        assertEquals(expected.entries.map { it.id }, ids(sequence))
        val summary = sequence.revision("q")
        assertEquals(expected.entries.size, summary.count)
        assertEquals(expected.entries.mapNotNull { it.track.durationMs }.sum(), summary.durationMs)
        assertEquals(expected.entries.count { it.track.durationMs != null }, summary.knownDurations)
    }

    @Test fun blockMovesPreserveEveryOccurrenceAndBothInsertionDirections() = database { sequence, _ ->
        val entries = List(15) { QueueEntry(id = "entry:$it", track = song(it)) }
        sequence.append("q", entries)
        var expected = entries.toMutableList()
        for ((at, length, target) in listOf(Triple(2, 4, 11), Triple(8, 3, 1), Triple(0, 2, 15), Triple(5, 4, 7))) {
            if (target !in at..at + length) {
                val block = expected.subList(at, at + length).toList()
                repeat(length) { expected.removeAt(at) }
                expected.addAll(if (target > at) target - length else target, block)
            }
            sequence.moveRange("q", at, length, target)
            assertEquals(expected.map { it.id }, ids(sequence))
        }
    }

    @Test fun snapshotsShareByValueWhileChangedMetadataAndOriginsRemainDistinct() = database { sequence, sql ->
        val original = song(7)
        val changed = original.copy(title = "Changed")
        val entries = List(30) { QueueEntry(track = if (it % 7 == 0) changed else original,
            sourcePlaylistId = if (it % 2 == 0) "origin" else null) }
        sequence.append("q", entries)
        assertEquals(entries, sequence.page("q", 0, 30).entries.map { it.entry })
        sql.query("SELECT COUNT(*) FROM queue_sequence_songs").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        sequence.duplicate("q", "q")
        val page = sequence.page("q", 0, 60)
        assertEquals(60, page.revision.count)
        assertEquals(entries.map { it.track }, page.entries.drop(30).map { it.entry.track })
        assertEquals(entries.map { it.sourcePlaylistId }, page.entries.drop(30).map { it.entry.sourcePlaylistId })
        assertEquals(60, page.entries.map { it.entry.id }.toSet().size)
    }

    @Test fun insertKeepsTheSavedManualBaselineInsteadOfReplacingItWithSortedPhysicalOrder() = database { sequence, sql ->
        val entries = List(7) { QueueEntry(id = "entry:$it", track = song(it)) }
        sequence.append("q", entries)
        // Physical reversal here models automatic sorting, which retains the original manual baseline.
        sql.execSQL("UPDATE queue_sequence_entries SET position = -position - 1 WHERE queueId = 'q'")
        sql.execSQL("UPDATE queue_sequence_entries SET position = 7 + position WHERE queueId = 'q'")
        val batch = listOf(QueueEntry(id = "new:1", track = song(90)), QueueEntry(id = "new:2", track = song(91)))
        sequence.insert("q", 3, batch)
        assertEquals(listOf("entry:6", "entry:5", "entry:4", "new:1", "new:2", "entry:3", "entry:2", "entry:1", "entry:0"), ids(sequence))
        val manual = sql.query("SELECT id FROM queue_sequence_entries WHERE queueId = 'q' ORDER BY manualPosition").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        assertEquals(listOf("entry:0", "entry:1", "entry:2", "entry:3", "entry:4", "new:1", "new:2", "entry:5", "entry:6"), manual)
    }

    @Test fun randomizeRetainsOccurrenceIdentityOriginsAndTheActiveOccurrenceFirst() = database { sequence, _ ->
        val entries = List(200) { QueueEntry(track = song(it % 7), sourcePlaylistId = "playlist:${it % 3}") }
        sequence.append("q", entries)
        val expected = QueueEdits.randomize(io.github.anastaci1a.localica.queue.ListeningQueue(name = "Queue",
            entries = entries, currentEntryId = entries[97].id), true, kotlin.random.Random(73))
        sequence.randomize("q", entries[97].id, kotlin.random.Random(73))
        val actual = sequence.page("q", 0, 200).entries.map { it.entry }
        assertEquals(expected.entries, actual)
        assertEquals(entries[97], actual.first())
        assertEquals(entries.associateBy { it.id }, actual.associateBy { it.id })
        assertEquals(200, actual.size)
    }

    @Test fun legacyMigrationPreservesTiesOriginsChangedSnapshotsAndStreamedManualOrder() = database { sequence, sql ->
        val entries = List(2103) { index -> QueueEntry(id = "項目🎵:" + index.toString().padStart(8, '0') + ":" + "長".repeat(17),
            track = song(index % 11).let { if (index % 97 == 0) it.copy(title = "Changed at $index") else it },
            sourcePlaylistId = if (index % 4 == 0) "playlist" else null) }
        val retained = entries.filterIndexed { index, _ -> index % 7 != 0 }.reversed().map { it.id } + "deleted"
        val saved = SavedOrder(retained, TrackSort.Title, ascending = false)
        sql.execSQL("INSERT INTO queues (id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,orderState) VALUES ('legacy','Legacy',0,?,1234,0,'Off',?)",
            arrayOf(entries[1050].id, SavedOrderCodec.encode(saved)))
        sql.compileStatement("INSERT INTO queue_entries (id,queueId,ordinal,track,sourcePlaylistId) VALUES (?,'legacy',?,?,?)").use { insert ->
            entries.forEachIndexed { index, entry ->
                insert.clearBindings()
                insert.bindString(1, entry.id)
                insert.bindLong(2, ((index + 1) / 2).toLong())
                insert.bindString(3, TrackSnapshot.encode(entry.track))
                entry.sourcePlaylistId?.let { insert.bindString(4, it) } ?: insert.bindNull(4)
                insert.executeInsert()
            }
        }
        QueueSequenceMigration(sequence).import("legacy")
        val actual = (entries.indices step QueueSequence.PageSize).flatMap { start ->
            sequence.page("legacy", start, QueueSequence.PageSize).entries.map { it.entry }
        }
        assertEquals(entries, actual)
        val manual = sql.query("SELECT id FROM queue_sequence_entries WHERE queueId = 'legacy' ORDER BY manualPosition").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        assertEquals(saved.reconcile(entries.map { it.id }).manual, manual)
        val summary = sequence.revision("legacy")
        assertEquals(entries.size, summary.count)
        assertEquals(entries.mapNotNull { it.track.durationMs }.sum(), summary.durationMs)
        QueueSequenceMigration(sequence).import("legacy")
        assertEquals("A second import is a metadata lookup", summary, sequence.revision("legacy"))
        sql.query("SELECT sort,ascending FROM queue_sequence_state WHERE queueId = 'legacy'").use {
            assertTrue(it.moveToFirst()); assertEquals("Title", it.getString(0)); assertEquals(0, it.getInt(1))
        }
    }

    @Test fun pageQuerySeeksThePositionIndexWithoutSortingOrSkippingThePrefix() = database { sequence, sql ->
        sequence.append("q", List(100) { QueueEntry(track = song(it)) })
        val detail = sql.query("EXPLAIN QUERY PLAN SELECT id FROM queue_sequence_entries WHERE queueId = 'q' AND position >= 70 AND position < 90 ORDER BY position").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(3)) }.joinToString("\n")
        }
        assertTrue(detail, detail.contains("index_queue_sequence_position"))
        assertFalse(detail, detail.contains("TEMP B-TREE"))
        assertEquals((70 until 90).toList(), sequence.page("q", 70, 20).entries.map { it.position })
    }

    @Test fun failedTransactionRollsBackRowsRevisionAndAggregates() {
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val sql = room.openHelper.writableDatabase
            QueueSequence.createSchema(sql)
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT INTO queues (id,name,createdAt,currentEntryId,positionMs,shuffle,repeat) VALUES ('q','Queue',0,NULL,0,0,'Off')")
                val sequence = QueueSequence(sql)
                sequence.create("q")
                sequence.append("q", listOf(QueueEntry(id = "same", track = song(1))))
                try {
                    sequence.append("q", listOf(QueueEntry(id = "same", track = song(2))))
                    fail("Duplicate occurrence identity must be rejected")
                } catch (_: android.database.SQLException) { }
                // Deliberately leave the transaction unsuccessful, including its prior successful append.
            } finally { sql.endTransaction() }
            sql.query("SELECT COUNT(*) FROM queue_sequence_state").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM queue_sequence_entries").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { room.close() }
    }
}

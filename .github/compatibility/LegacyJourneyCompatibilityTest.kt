package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.statistics.QualifiedPlay
import io.github.anastaci1a.localica.statistics.StatisticsDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real legacy DAO proofs; replacing installed APKs is a separate, still-required compatibility gate. */
class LegacyJourneyCompatibilityTest {
    @Test fun readAndMetadataOnlySavePreserveProjectionWhileHistoryOnlyChangeInvalidatesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-projection-compatibility.db"
        val statsName = "legacy-projection-statistics.db"
        context.deleteDatabase(name); context.deleteDatabase(statsName)
        fun open() = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        var db = open()
        val statistics = Room.databaseBuilder(context, StatisticsDatabase::class.java, statsName).build()
        try {
            statistics.statistics().record(QualifiedPlay("play-1", "same-track", "playlist", 123L))
            statistics.statistics().record(QualifiedPlay("play-2", "same-track", null, 456L))
            val plays = statisticsRows(statistics)
            val original = fixture()
            db.queues().save(QueueLibrary(), original)
            setProjection(db)
            installHeaderCounter(db)
            val raw = snapshot(db)
            assertEquals(original, db.queues().read())
            assertEquals(raw, snapshot(db))
            assertEquals(0L, headerWrites(db))
            val renamed = original.update(original.active!!.copy(name = "Renamed", positionMs = 3456))
            db.queues().save(original, renamed)
            assertEquals(0L, headerWrites(db))
            assertEquals(raw.first.single().journeys, snapshot(db).first.single().journeys)
            assertEquals(raw.first.single().visits, snapshot(db).first.single().visits)
            assertEquals(raw.first.single().cycleMembers, snapshot(db).first.single().cycleMembers)
            db.close(); db = open()
            assertEquals(renamed, db.queues().read())
            assertProjection(db, "candidate-generation", "removed-anchor")

            val journey = renamed.active!!.journey!!
            val changed = journey.copy(history = listOf(journey.history[2], journey.history[1], journey.history[0]))
            // Same scalar header/cursor/current token: list inequality must still invalidate the header.
            assertEquals(journey.record(renamed.activeId!!), changed.record(renamed.activeId!!))
            assertEquals(journey.current, changed.current)
            val after = renamed.update(renamed.active!!.copy(journey = changed))
            db.queues().save(renamed, after)
            assertEquals(1L, headerWrites(db))
            assertProjection(db, null, null)
            assertEquals(after, db.queues().read())
            assertEquals(original.active!!.order, after.active!!.order)
            assertEquals(original.active!!.entries, after.active!!.entries)
            assertEquals(plays, statisticsRows(statistics))
            db.close(); db = open()
            assertEquals(after, db.queues().read())
            assertProjection(db, null, null)
        } finally {
            db.close(); statistics.close()
            context.deleteDatabase(name); context.deleteDatabase(statsName)
        }
    }

    @Test fun failedLegacyVisitSaveRollsBackIdentityInvalidationAndRetryClearsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val before = fixture()
            db.queues().save(QueueLibrary(), before)
            setProjection(db); installHeaderCounter(db)
            val raw = snapshot(db)
            val queue = before.active!!
            val selected = queue.journey!!.select(queue.entries.first().id, queue.entries.map { it.id })
            val after = before.update(queue.copy(currentEntryId = selected.current!!.entryId, positionMs = 0, journey = selected))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_legacy_visit BEFORE INSERT ON queue_visits BEGIN SELECT RAISE(ABORT, 'Injected legacy visit failure'); END")
            try { db.queues().save(before, after); fail("Header invalidation must roll back with visit failure") }
            catch (_: android.database.SQLException) { }
            assertEquals(raw, snapshot(db))
            assertEquals(before, db.queues().read())
            assertEquals(0L, headerWrites(db))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_legacy_visit")
            db.queues().save(before, after)
            assertEquals(after, db.queues().read())
            assertProjection(db, null, null)
            assertEquals(1L, headerWrites(db))

            setProjection(db)
            val shuffled = after.active!!.journey!!.reconcile(queue.entries.map { it.id }, after.active!!.currentEntryId,
                JourneyModes(shuffle = true, loop = true), kotlin.random.Random(17))
            val modeChanged = after.update(after.active!!.copy(journey = shuffled))
            db.queues().save(after, modeChanged)
            assertProjection(db, null, null)
            assertEquals(modeChanged, db.queues().read())
            assertEquals(after.active!!.journey!!.current, shuffled.current)
            assertEquals(after.active!!.repeat, modeChanged.active!!.repeat)
        } finally { db.close() }
    }

    private fun fixture(): QueueLibrary {
        val track = Track("same-track", "Duplicate", fileName = "same.mp3", relativePath = "same.mp3")
        val entries = listOf("a", "b", "c").map { QueueEntry(it, track, "playlist") }
        val ids = entries.map { it.id }
        val history = listOf(QueueVisit("a", 0, "real-a"), QueueVisit("c", -1, "reverse-wrap-c"), QueueVisit("b", -1, "reverse-wrap-b"))
        val journey = QueueJourney(history, 1, cycle = -1, visited = setOf("b", "c"),
            plans = (-1L..1L).associateWith { ids }, modes = JourneyModes(loop = true))
            .reconcile(ids, "c", JourneyModes(loop = true), kotlin.random.Random(2))
        check(journey.current!!.token == "reverse-wrap-c" && journey.cursor == 1)
        val queue = ListeningQueue(id = "compat-queue", name = "Candidate state", createdAt = 123,
            entries = entries, currentEntryId = "c", positionMs = 2345, repeat = QueueRepeat.All,
            lastActivatedAt = 456, journey = journey, order = SavedOrder(ids.reversed()))
        return QueueLibrary(listOf(queue), queue.id)
    }

    private fun setProjection(db: QueueDatabase) = db.openHelper.writableDatabase.execSQL(
        "UPDATE queue_journeys SET orderedTraversalId = ?, orderedAnchorToken = ?", arrayOf("candidate-generation", "removed-anchor"))
    private suspend fun assertProjection(db: QueueDatabase, namespace: String?, anchor: String?) {
        val header = db.queues().readQueues().single().journeys.single()
        assertEquals(namespace, header.orderedTraversalId); assertEquals(anchor, header.orderedAnchorToken)
    }
    private fun installHeaderCounter(db: QueueDatabase) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE compatibility_header_writes (count INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO compatibility_header_writes VALUES (0)")
        sql.execSQL("CREATE TRIGGER count_legacy_header AFTER UPDATE ON queue_journeys BEGIN UPDATE compatibility_header_writes SET count = count + 1; END")
    }
    private fun headerWrites(db: QueueDatabase) = db.openHelper.writableDatabase.query(
        "SELECT count FROM compatibility_header_writes").use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private suspend fun snapshot(db: QueueDatabase) = db.queues().readQueues().map { it.copy(
        entries = it.entries.sortedBy { row -> row.id }, visits = it.visits.sortedBy { row -> row.token },
        cycleMembers = it.cycleMembers.sortedWith(compareBy({ row -> row.cycle }, { row -> row.entryId }))) } to db.queues().readActive()
    private fun statisticsRows(db: StatisticsDatabase) = db.openHelper.writableDatabase.query(
        "SELECT id, trackId, playlistId, playedAt FROM qualified_plays ORDER BY id").use { cursor ->
        buildList { while (cursor.moveToNext()) add(QualifiedPlay(cursor.getString(0), cursor.getString(1),
            if (cursor.isNull(2)) null else cursor.getString(2), cursor.getLong(3))) }
    }
}

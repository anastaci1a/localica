package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** #930 at operator scale: every write path keeps the complete id -> (addedAt, firstSeenAt, firstSeenModifiedAt) map; hidden tracks never surface. */
@RunWith(AndroidJUnit4::class)
class DateModel930Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val music = FolderSelection("tree:music", "Music")
    private val other = FolderSelection("tree:other", "Other")
    private val count = 18_000

    private fun song(i: Int, folder: FolderSelection = music, title: String = "Song $i") =
        Track("${folder.uri}/document/$i", title, fileName = "$i.mp3", relativePath = "$i.mp3", id = "doc:$i", modifiedAt = if (i % 10 == 0) null else 100L + i)
    private fun songs(folder: FolderSelection = music, title: (Int) -> String = { "Song $it" }) = (0 until count).map { song(it, folder, title(it)) }

    private fun LibraryDatabase.dates(): Map<String, Triple<Long?, Long?, Long?>> =
        openHelper.readableDatabase.query("SELECT id, addedAt, firstSeenAt, firstSeenModifiedAt FROM tracks").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), Triple(if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3))) }
        }

    /** 18,000 tracks with distinct imported dates, unknown (NULL) dates and first-seen values that differ from date added. */
    private fun seeded(block: suspend (LibraryDatabase, RoomLibraryStore, Map<String, Triple<Long?, Long?, Long?>>) -> Unit) = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            store.replaceRoot(ScanResult(music, songs().map { it.copy(firstSeenModifiedAt = 1) }, 0))
            store.applyAddedDates((0 until count).filter { it % 10 != 0 }.associate { "doc:$it" to 1_000_000L + it * 1_000L })
            database.openHelper.writableDatabase.execSQL("UPDATE tracks SET addedAt = NULL WHERE CAST(substr(id, 5) AS INTEGER) % 10 = 0")
            database.openHelper.writableDatabase.execSQL("UPDATE tracks SET firstSeenAt = NULL WHERE CAST(substr(id, 5) AS INTEGER) % 20 = 0")
            val expected = database.dates()
            assertEquals(count, expected.size)
            assertEquals(songs().associate { it.id to it.modifiedAt }, expected.mapValues { it.value.third })
            assertEquals(count - count / 10, expected.values.mapNotNull { it.first }.toSet().size)
            assertTrue(expected.values.any { it.first == null && it.second != null } && expected.values.any { it.second == null })
            val runtime = Runtime.getRuntime()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val gcBefore = android.os.Debug.getRuntimeStats()
            block(database, store, expected)
            android.util.Log.i("DateModel943", "rows=$count heapBefore=$heapBefore heapAfter=${runtime.totalMemory() - runtime.freeMemory()} gcBefore=$gcBefore gcAfter=${android.os.Debug.getRuntimeStats()}")
        } finally { database.close() }
    }

    @Test fun scansMetadataRefreshAndImportsKeepBothDatesAt18k() = seeded { database, store, expected ->
        val start = System.currentTimeMillis()
        store.replaceRoot(ScanResult(music, songs { "Retitled $it" }.map { it.copy(modifiedAt = 900_000, firstSeenModifiedAt = 1) }, 0))
        assertEquals(expected, database.dates())
        assertEquals("Retitled 7", store.current().tracks.single { it.id == "doc:7" }.title)
        assertTrue(store.current().tracks.all { it.modifiedAt == 900_000L })
        store.replaceRoot(ScanResult(music, songs { "Retitled $it" }, 0))
        assertEquals(expected, database.dates())

        val fresh = (count until count + 200).map { song(it).copy(firstSeenModifiedAt = 1) }
        store.mergeRoot(ScanResult(music, store.current().tracks + fresh, 0))
        val merged = database.dates()
        assertEquals(expected, merged - fresh.map { it.id }.toSet())
        fresh.forEach { val (added, seen, modified) = merged.getValue(it.id); assertEquals(it.modifiedAt, modified); assertEquals(added, seen); assertTrue(requireNotNull(seen) >= start) }
        assertEquals(1, fresh.map { merged.getValue(it.id).second }.toSet().size)
        val withFresh = merged

        store.updateMetadataBatch(store.current().tracks.take(5_000).map { it.copy(title = "Tagged", addedAt = 1, firstSeenAt = 1, modifiedAt = 800_000, firstSeenModifiedAt = 1) })
        store.updateMetadata(store.current().tracks.last().copy(title = "Tagged once", addedAt = 2, firstSeenAt = 2, modifiedAt = 700_000, firstSeenModifiedAt = 2))
        assertEquals(withFresh, database.dates())

        val imported = (0 until count step 3).associate { "doc:$it" to 5L + it }
        assertEquals(imported.size, store.applyAddedDates(imported))
        assertEquals(withFresh.mapValues { (id, dates) -> Triple(imported[id] ?: dates.first, dates.second, dates.third) }, database.dates())
        val exported = ByteArrayOutputStream().also { writeLibraryData(store.current(), it, 0, "test") }.toString("UTF-8")
        val forged = JSONObject(exported).apply {
            val tracks = getJSONArray("tracks")
            for (i in 0 until tracks.length()) tracks.getJSONObject(i).apply {
                put("addedAt", 20L + i); put("modifiedAt", 1); put("firstSeenAt", 1); put("firstSeenModifiedAt", 1)
            }
        }
        val importPlan = LibraryDataImport.plan(LibraryDataCodec.decode(forged.toString()), store.current())
        store.applyAddedDates(importPlan.addedAtChanges)
        val afterImport = database.dates()
        assertEquals(withFresh.mapValues { (id, dates) -> Triple(importPlan.addedAtChanges[id] ?: dates.first, dates.second, dates.third) }, afterImport)
        store.replaceRoot(ScanResult(music, songs() + fresh, 0))
        store.mergeRoot(ScanResult(music, store.current().tracks, 0))
        assertEquals(afterImport, database.dates())
    }

    @Test fun membershipLossRemovalAndRootHandoffKeepBothDatesAt18k() = seeded { database, store, expected ->
        RoomArtworkColorStore(database.libraryDao()).save(songs().take(100).map { ArtworkSource(it.id, it.modifiedAt, it.uri) to 0xFF112233.toInt() })
        assertEquals(100, database.libraryDao().observeArtworkColors().first().size)
        // Successful empty scan, then quick rediscovery with tags edited outside the app meanwhile.
        store.replaceRoot(ScanResult(music, emptyList(), 0))
        assertEquals(expected, database.dates())
        assertHidden(database, store)
        store.mergeRoot(ScanResult(music, songs { "Edited $it" }.map { it.copy(modifiedAt = 600_000) }, 0))
        assertEquals(expected, database.dates())
        assertEquals(count, store.current().tracks.size)
        assertEquals("Edited 11", store.current().tracks.single { it.id == "doc:11" }.title)

        // Restrictive scan hides half; a full scan restores it.
        store.replaceRoot(ScanResult(music, songs().take(count / 2), 0))
        assertEquals(count / 2, store.current().tracks.size)
        assertEquals(expected, database.dates())
        store.replaceRoot(ScanResult(music, songs(), 0))
        assertEquals(expected, database.dates())

        // Remove the folder, add it back (empty root as addFolder does), scan.
        store.removeRoot(music.uri)
        assertEquals(expected, database.dates())
        assertHidden(database, store)
        store.replaceRoot(ScanResult(music, emptyList(), 0))
        store.replaceRoot(ScanResult(music, songs(), 0))
        assertEquals(expected, database.dates())

        // Handoff: the old root empties before the new root indexes the same identities.
        store.replaceRoot(ScanResult(music, emptyList(), 0))
        store.replaceRoot(ScanResult(other, songs(other), 0))
        assertEquals(expected, database.dates())
        assertEquals(setOf(other.uri), store.current().tracks.flatMap { t -> t.locations.map { it.folderUri } }.toSet())
        // Reverse order: the new root indexes first (overlap), then the old root empties.
        store.replaceRoot(ScanResult(music, songs(), 0))
        assertEquals(expected, database.dates())
        assertEquals(2, store.current().tracks.first().locations.size)
        store.replaceRoot(ScanResult(other, emptyList(), 0))
        assertEquals(expected, database.dates())
        store.replaceRoot(ScanResult(other, songs(other), 0))
        store.removeRoot(music.uri)
        assertEquals(count, store.current().tracks.size)
        assertEquals(expected, database.dates())
    }

    /** Every read path is built from these: browse, search, playlists, Queue restore, stats, Last.fm read [LibraryStore.snapshots]/[sortSnapshots]/[playbackSnapshot]; artwork reads the DAO queries; export and import read [LibraryStore.current]. */
    private suspend fun assertHidden(database: LibraryDatabase, store: RoomLibraryStore) {
        val dao = database.libraryDao()
        val ids = (0 until count).map { "doc:$it" }
        assertTrue(store.current().tracks.isEmpty() && store.current().metadataFailures.isEmpty())
        assertTrue(store.snapshots.first().tracks.isEmpty())
        assertTrue(store.sortSnapshots.first().tracks.isEmpty())
        for (wanted in listOf(ids.take(900).toSet(), ids.toSet())) {
            val read = store.playbackSnapshot(wanted)
            assertTrue(read.tracks.isEmpty()); assertEquals(0, read.totalTracks)
        }
        assertTrue(dao.pendingArtwork(count).isEmpty())
        assertTrue(dao.artworkSources(songs().take(900).map { it.uri }).isEmpty())
        assertTrue(dao.observeArtworkColors().first().isEmpty())
        assertEquals(0, ByteArrayOutputStream().use { writeLibraryData(store.current(), it, 0, "test") })
        val plan = LibraryDataImport.plan(LibraryDataDocument("1.0", ids.take(100).map { LibraryDataRecord(id = it, addedAt = 9) }), store.current())
        assertEquals(0, plan.changes); assertEquals(100, plan.unmatched)
    }

    @Test fun failedAndCancelledScansLeaveBothDatesUnchangedAt18k() = seeded { database, store, expected ->
        val before = store.current()
        try {
            store.replaceRoot(ScanResult(other, songs(other) + songs(other).take(1), 0))
            fail("Duplicate memberships must fail")
        } catch (_: android.database.SQLException) { }
        assertEquals(expected, database.dates())
        assertEquals(before, store.current())
        val job = CoroutineScope(Dispatchers.IO).launch { store.replaceRoot(ScanResult(music, songs { "Cancelled $it" }, 0)) }
        delay(5)
        job.cancelAndJoin()
        assertEquals(expected, database.dates())
        val titles = store.current().tracks.map { it.title.substringBefore(' ') }.toSet()
        assertTrue(titles.toString(), titles == setOf("Song") || titles == setOf("Cancelled"))
    }

    @Test fun migrations9And10To11PreserveDatesAndCaptureStoredModificationAt18k() = runBlocking {
        for (version in listOf(9, 10)) {
            val name = "date-model-930-$version.db"
            context.deleteDatabase(name)
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("io.github.anastaci1a.localica.library.LibraryDatabase/$version.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                .getJSONObject("database")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) entities.getJSONObject(i).let { entity ->
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO folders VALUES ('root', 'Music')")
                old.beginTransaction()
                for (i in 0 until count) {
                    old.execSQL("INSERT INTO tracks (id, title, fileName, addedAt, modifiedAt) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>("doc:$i", "Song $i", "$i.mp3", if (i % 10 == 0) null else 1_000L + i, song(i).modifiedAt))
                    old.execSQL("INSERT INTO folder_tracks (folderUri, trackId, uri, relativePath) VALUES ('root', ?, ?, ?)", arrayOf("doc:$i", "root/$i", "$i.mp3"))
                }
                if (version == 10) old.execSQL("UPDATE tracks SET firstSeenAt = CASE WHEN CAST(substr(id, 5) AS INTEGER) % 20 = 0 THEN NULL ELSE 9000 + CAST(substr(id, 5) AS INTEGER) END")
                old.setTransactionSuccessful(); old.endTransaction()
                old.version = version
            }
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(*LibraryDatabase.MIGRATIONS).build()
            try {
                val dates = database.dates()
                assertEquals(count, dates.size)
                assertEquals((0 until count).associate { "doc:$it" to (if (it % 10 == 0) null else 1_000L + it).let { d -> Triple(d, if (version == 9) d else if (it % 20 == 0) null else 9000L + it, song(it).modifiedAt) } }, dates)
                val store = RoomLibraryStore(database.libraryDao())
                assertEquals(count, store.current().tracks.size)
                assertEquals(dates.mapValues { it.value.third }, store.current().tracks.associate { it.id to it.firstSeenModifiedAt })
                store.replaceRoot(ScanResult(music, songs().map { it.copy(modifiedAt = 42) }, 0))
                assertEquals(dates, database.dates())
            } finally { database.close(); context.deleteDatabase(name) }
        }
    }
}

package io.github.anastaci1a.localica

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** `track_artwork` (#525): the 6 -> 7 migration, staleness, cascade and index cost. */
@RunWith(AndroidJUnit4::class)
class ArtworkColorStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = "content://test.documents/tree/root"

    @Test fun migrationFromSixAddsTheColourTableAndPreservesTheLibrary() = runBlocking {
        val name = "migration-artwork-library.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            // Exactly the exported version 6 schema (app/schemas/.../6.json).
            old.execSQL("CREATE TABLE IF NOT EXISTS `folders` (`uri` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`uri`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS `tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `durationMs` INTEGER, `fileName` TEXT NOT NULL, `albumArtist` TEXT, `trackNumber` INTEGER, `discNumber` INTEGER, `year` INTEGER, `genre` TEXT, `comment` TEXT, `composer` TEXT, `lyricist` TEXT, `modifiedAt` INTEGER, `addedAt` INTEGER, PRIMARY KEY(`id`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS `folder_tracks` (`folderUri` TEXT NOT NULL, `trackId` TEXT NOT NULL, `uri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `metadataFailed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`folderUri`, `trackId`), FOREIGN KEY(`folderUri`) REFERENCES `folders`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_tracks_trackId` ON `folder_tracks` (`trackId`)")
            old.execSQL("INSERT INTO folders VALUES (?, 'Music')", arrayOf(root))
            for (i in 0 until 3) {
                old.execSQL("INSERT INTO tracks (id, title, fileName, modifiedAt, addedAt) VALUES (?, ?, ?, 100, 5)", arrayOf("id$i", "Song $i", "s$i.mp3"))
                old.execSQL("INSERT INTO folder_tracks (folderUri, trackId, uri, relativePath) VALUES (?, ?, ?, ?)", arrayOf(root, "id$i", "$root/document/s$i", "s$i.mp3"))
            }
            old.version = 6
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val dao = database.libraryDao()
            val store = RoomArtworkColorStore(dao)
            val snapshot = RoomLibraryStore(dao).current()
            assertEquals(listOf("Song 0", "Song 1", "Song 2"), snapshot.tracks.map { it.title })
            assertEquals(5L, snapshot.tracks.first().addedAt)
            // Every migrated track starts uncomputed: the neutral base until the backfill reaches it.
            assertEquals(setOf("id0", "id1", "id2"), store.pending(10).map { it.trackId }.toSet())
            assertTrue(dao.observeArtworkColors().first().isEmpty())
            val red = 0xFFCC2020.toInt()
            store.save(listOf(ArtworkSource("id0", 100, "$root/document/s0") to red,
                ArtworkSource("id1", 100, "$root/document/s1") to ArtworkColors.MISSING,
                ArtworkSource("gone", 1, "x") to red)) // removed meanwhile: skipped, not a constraint failure
            assertEquals(listOf("id2"), store.pending(10).map { it.trackId })
            assertEquals(mapOf("$root/document/s0" to red, "$root/document/s1" to null),
                dao.observeArtworkColors().first().associate { it.uri to it.color })
            // A tag edit or rescan that changes modifiedAt makes the colour stale.
            val track = snapshot.tracks.first()
            dao.updateMetadataBatch(listOf(TrackRecord(track.id, track.title, null, null, null, track.fileName, null, null, null, null, null, null, modifiedAt = 200)))
            assertEquals(setOf("id0", "id2"), store.pending(10).map { it.trackId }.toSet())
            // Removing the root hides its colours; the retained tracks keep them for rediscovery (#930).
            dao.removeRoot(root)
            assertTrue(dao.observeArtworkColors().first().isEmpty())
            assertTrue(store.pending(10).isEmpty())
            assertEquals(2, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_artwork").use { it.moveToFirst(); it.getInt(0) })
        } finally { database.close(); context.deleteDatabase(name) }
    }

    /** Cost evidence for the startup index: query plus map build for 10,000 tracks, off Main. */
    @Test fun tenThousandTrackIndexBuildsQuickly() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = database.libraryDao()
            val count = 10_000
            val tracks = (0 until count).map { Track("$root/document/$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3", id = "id$it") }
            RoomLibraryStore(dao).replaceRoot(ScanResult(FolderSelection(root, "Music"), tracks, 0))
            RoomArtworkColorStore(dao).save(tracks.map { ArtworkSource(it.id, null, it.uri) to (0xFF000000.toInt() or (it.hashCode() and 0xFFFFFF)) })
            var best = Long.MAX_VALUE
            repeat(3) {
                val started = SystemClock.elapsedRealtimeNanos()
                val rows = dao.observeArtworkColors().first()
                val index = HashMap<String, Int>(rows.size * 2)
                rows.forEach { index[it.uri] = it.color ?: ArtworkColors.MISSING }
                assertEquals(count, index.size)
                best = minOf(best, SystemClock.elapsedRealtimeNanos() - started)
            }
            Log.i("ArtworkColorStorageTest", "10k colour index query+build: ${best / 1_000_000} ms")
            assertTrue("10k index took ${best / 1_000_000} ms", best < 2_000_000_000L)
            assertTrue(RoomArtworkColorStore(dao).pending(10).isEmpty())
        } finally { database.close() }
    }
}

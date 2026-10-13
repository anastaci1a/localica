package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class StatisticsStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun addedDateSurvivesRescanOverlapAndMetadataEditWhileNewSongsReceiveNewDate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(db.libraryDao())
            val song = Track("uri", "Song", fileName = "song.mp3", relativePath = "song.mp3")
            val root = FolderSelection("root", "Music")
            store.replaceRoot(ScanResult(root, listOf(song), 0))
            // Seed a known historical indexing date to prove preservation without timing-dependent sleeps.
            db.openHelper.writableDatabase.execSQL("UPDATE tracks SET addedAt = 1234")
            store.replaceRoot(ScanResult(root, listOf(song.copy(title = "Rescanned")), 0))
            store.replaceRoot(ScanResult(FolderSelection("overlap", "Overlap"), listOf(song.copy(uri = "other-uri")), 0))
            store.updateMetadata(song.copy(title = "Edited", addedAt = 9999))
            assertEquals(1234L, store.current().tracks.single().addedAt)
            store.replaceRoot(ScanResult(root, listOf(song, song.copy(id = "new", uri = "new")), 0))
            assertEquals(1234L, store.current().tracks.first { it.id == song.id }.addedAt)
            assertTrue(requireNotNull(store.current().tracks.first { it.id == "new" }.addedAt) > 1234)
        } finally { db.close() }
    }
    @Test fun playlistMigrationKeepsEntriesAndUsesCreationDateUntilFirstEdit() = runBlocking {
        val name = "playlist-date-migration.db"
        context.deleteDatabase(name)
        val song = Track("uri", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE playlists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE playlist_entries (id TEXT NOT NULL PRIMARY KEY, playlistId TEXT NOT NULL, ordinal INTEGER NOT NULL, track TEXT NOT NULL, FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_playlist_entries_playlistId ON playlist_entries(playlistId)")
            old.execSQL("INSERT INTO playlists VALUES ('mix','Mix',1234)")
            old.execSQL("INSERT INTO playlist_entries VALUES ('entry','mix',0,?)", arrayOf(TrackSnapshot.encode(song)))
            old.version = 1
        }
        val db = Room.databaseBuilder(context, PlaylistDatabase::class.java, name)
            .addMigrations(PlaylistDatabase.MIGRATION_1_2, PlaylistDatabase.MIGRATION_2_3).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val controller = PlaylistController(db.playlists(), scope)
            withTimeout(5000) { controller.state.first { !it.loading } }
            assertEquals(1234L, controller.state.value.playlists.single().modifiedAt)
            assertEquals("entry", controller.state.value.playlists.single().entries.single().id)
            suspend fun edit(block: ((Boolean) -> Unit) -> Unit) {
                val done = CompletableDeferred<Boolean>(); block { done.complete(it) }
                assertTrue(withTimeout(5000) { done.await() })
            }
            edit { controller.refreshTrack(song.copy(title = "Tags changed"), it) }
            assertEquals(1234L, controller.state.value.playlists.single().modifiedAt)
            edit { controller.rename("mix", "Mix", it) }
            assertEquals(1234L, controller.state.value.playlists.single().modifiedAt)
            edit { controller.rename("mix", "New name", it) }
            val changed = controller.state.value.playlists.single()
            assertEquals(1234L, changed.createdAt)
            assertTrue(changed.modifiedAt > 1234)
            assertEquals(changed, db.playlists().read().single())
        } finally { scope.cancel(); db.close(); context.deleteDatabase(name) }
    }
    @Test fun originSurvivesSavedQueueEditsAndStatisticsSurviveReopenWithoutDuplicateEvents() = runBlocking {
        val name = "statistics-test.db"
        context.deleteDatabase(name)
        val queues = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        var db = Room.databaseBuilder(context, StatisticsDatabase::class.java, name).build()
        try {
            val song = Track("uri", "Song", fileName = "song.mp3", relativePath = "song.mp3", addedAt = 1234)
            val first = QueueEdits.create(listOf(song, song), 0, "Mix", listOf("playlist-a", "playlist-b"))
            val moved = QueueEdits.move(first, first.entries.last().id, -1)
            queues.queues().save(QueueLibrary(), QueueLibrary(listOf(moved), moved.id))
            val restored = queues.queues().read().active!!
            assertEquals(listOf("playlist-b", "playlist-a"), restored.entries.map { it.sourcePlaylistId })
            assertEquals(1234L, restored.entries.first().track.addedAt)
            val event = QualifiedPlay(trackId = song.id, playlistId = restored.entries.first().sourcePlaylistId)
            db.statistics().record(event); db.statistics().record(event)
            db.statistics().record(QualifiedPlay(trackId = song.id, playlistId = null))
            db.close()
            db = Room.databaseBuilder(context, StatisticsDatabase::class.java, name).build()
            assertEquals(listOf(PlayCount(song.id, 2)), db.statistics().tracks().first())
            assertEquals(listOf(PlayCount("playlist-b", 1)), db.statistics().playlists().first())
        } finally { queues.close(); db.close(); context.deleteDatabase(name) }
    }
}

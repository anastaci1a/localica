package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    @Test fun rootsSurviveReopenAndFailedTransactionRollsBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-library.db"
        context.deleteDatabase(name)
        val result = ScanResult(FolderSelection("content://test/root", "Music"), listOf(
            Track("content://test/song", "Song", fileName = "song.mp3", relativePath = "nested/song.mp3")), 0)
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            RoomLibraryStore(database.libraryDao()).replaceRoot(result)
            val snapshot = RoomLibraryStore(database.libraryDao()).current()
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val store = RoomLibraryStore(database.libraryDao())
            assertEquals(snapshot, store.current())
            try {
                store.replaceRoot(result.copy(folder = FolderSelection("other", "Other"), tracks = result.tracks + result.tracks))
                fail("Duplicate memberships must fail")
            } catch (_: android.database.SQLException) { }
            assertEquals(snapshot, store.current())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun overlappingRootsShareSongsAndRescansDoNotEraseOtherRoots() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val parent = FolderSelection("parent", "Parent")
            val child = FolderSelection("child", "Child")
            val song = Track("parent/song", "Old title", fileName = "song.mp3", relativePath = "child/song.mp3", id = "document:song")
            store.replaceRoot(ScanResult(parent, listOf(song), 0))
            store.replaceRoot(ScanResult(child, listOf(song.copy(uri = "child/song", relativePath = "song.mp3", title = "New title")), 0))
            assertEquals(2, store.current().folders.size)
            assertEquals(1, store.current().tracks.size)
            assertEquals(2, store.current().tracks.single().locations.size)
            assertEquals("New title", store.current().tracks.single().title)
            store.replaceRoot(ScanResult(parent, emptyList(), 0))
            assertEquals("document:song", store.current().tracks.single().id)
            assertEquals("child/song", store.current().tracks.single().uri)
            store.removeRoot("parent")
            assertEquals(listOf(child), store.current().folders)
            assertEquals(1, store.current().tracks.size)
            store.removeRoot("child")
            assertEquals(LibrarySnapshot(), store.current())
        } finally { database.close() }
    }

    @Test fun migrationPreservesReleasedLibraryAndCanonicalIdentityOnRescan() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-library.db"
        context.deleteDatabase(name)
        val uri = "content://test.documents/tree/root/document/song"
        val folder = "content://test.documents/tree/root"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE folders (id INTEGER NOT NULL PRIMARY KEY, uri TEXT NOT NULL, name TEXT NOT NULL)")
            old.execSQL("CREATE TABLE tracks (uri TEXT NOT NULL PRIMARY KEY, folderId INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT, album TEXT, durationMs INTEGER, fileName TEXT NOT NULL, relativePath TEXT NOT NULL, FOREIGN KEY(folderId) REFERENCES folders(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_tracks_folderId ON tracks(folderId)")
            old.execSQL("INSERT INTO folders VALUES (1, ?, 'Music')", arrayOf(folder))
            old.execSQL("INSERT INTO tracks VALUES (?,1,'Song','Artist','Album',1234,'song.mp3','nested/song.mp3')", arrayOf(uri))
            old.version = 1
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(LibraryDatabase.MIGRATION_1_2).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val snapshot = store.current()
            assertEquals(listOf(FolderSelection(folder, "Music")), snapshot.folders)
            val song = snapshot.tracks.single()
            assertEquals(uri, song.uri)
            assertEquals("content://test.documents/document/song", song.id)
            assertEquals(1234L, song.durationMs)
            assertEquals("nested/song.mp3", song.relativePath)
            assertNull(song.albumArtist)
            store.replaceRoot(ScanResult(snapshot.folders.single(), listOf(song.copy(title = "Updated", albumArtist = "Band")), 0))
            assertEquals(song.id, store.current().tracks.single().id)
            assertEquals("Band", store.current().tracks.single().albumArtist)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}

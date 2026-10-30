package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import io.github.anastaci1a.localica.statistics.withListeningStatistics
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
            Track("content://test/song", "Song", fileName = "song.mp3", relativePath = "nested/song.mp3")), 1, setOf("content://test/song"))
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            RoomLibraryStore(database.libraryDao()).replaceRoot(result)
            val snapshot = RoomLibraryStore(database.libraryDao()).current()
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val store = RoomLibraryStore(database.libraryDao())
            assertEquals(snapshot, store.current())
            assertEquals("nested/song.mp3", store.current().metadataFailures.single().path)
            try {
                store.replaceRoot(result.copy(folder = FolderSelection("other", "Other"), tracks = result.tracks + result.tracks))
                fail("Duplicate memberships must fail")
            } catch (_: android.database.SQLException) { }
            assertEquals(snapshot, store.current())
            store.replaceRoot(result.copy(metadataFailures = 0, failedMetadataIds = emptySet()))
            assertTrue(store.current().metadataFailures.isEmpty())
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
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val migrationStart = System.currentTimeMillis()
            val snapshot = store.current()
            assertTrue(requireNotNull(snapshot.tracks.single().addedAt) >= migrationStart)
            assertEquals(listOf(FolderSelection(folder, "Music")), snapshot.folders)
            val song = snapshot.tracks.single()
            assertEquals(uri, song.uri)
            assertEquals("content://test.documents/document/song", song.id)
            assertEquals(1234L, song.durationMs)
            assertEquals("nested/song.mp3", song.relativePath)
            assertNull(song.albumArtist)
            store.replaceRoot(ScanResult(snapshot.folders.single(), listOf(song.copy(title = "Updated", albumArtist = "Band", comment = "A comment")), 0))
            assertEquals(song.id, store.current().tracks.single().id)
            assertEquals("Band", store.current().tracks.single().albumArtist)
            assertEquals("A comment", store.current().tracks.single().comment)
            assertEquals(song.addedAt, store.current().tracks.single().addedAt)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun sortSourceRetainsFreshCanonicalRowsWithoutSearchIndexPreparation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val first = Track("parent/song", "Original", artist = "Artist", album = "Album", durationMs = 1234,
                fileName = "song.mp3", relativePath = "nested/song.mp3", id = "document:song", modifiedAt = 40)
            store.replaceRoot(ScanResult(FolderSelection("parent", "Music"), listOf(first), 0))
            store.replaceRoot(ScanResult(FolderSelection("child", "Nested"),
                listOf(first.copy(uri = "child/song", relativePath = "song.mp3")), 0))
            val sorts = store.sortSnapshots // Holding the cold flow must not freeze its first read.
            val before = sorts.first()
            assertNull(before.searchIndex)
            assertEquals(store.current(), before)
            assertEquals(2, before.tracks.single().locations.size)
            val indexed = store.snapshots.first()
            assertNotNull(indexed.searchIndex)
            assertEquals(before, indexed.copy(searchIndex = null))
            store.updateMetadata(first.copy(title = "Fresh", durationMs = 5678, modifiedAt = 90))
            val after = sorts.first()
            assertEquals("Fresh", after.tracks.single().title)
            assertEquals(5678L, after.tracks.single().durationMs)
            assertEquals(90L, after.tracks.single().modifiedAt)
            assertEquals(before.tracks.single().locations, after.tracks.single().locations)
            assertNull(after.searchIndex)
            assertEquals(store.current(), after)
            assertEquals(after, store.snapshots.first().copy(searchIndex = null))
        } finally { database.close() }
    }

    @Test fun repositorySortSourceOverlaysCurrentCountsWithoutReplayingPriorRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val repository = LibraryRepository(store, TreeScanner(AndroidDocuments(context)),
                PersistedFolderPermissions(context.contentResolver))
            val a = Track("a", "A", fileName = "a.mp3", relativePath = "a.mp3")
            val b = Track("b", "B", fileName = "b.mp3", relativePath = "b.mp3")
            store.replaceRoot(ScanResult(FolderSelection("root", "Music"), listOf(a, b), 0))
            val counts = MutableStateFlow(ListeningStatistics(mapOf("a" to 3L)))
            val source = repository.sortSnapshots.withListeningStatistics(counts)
            val query = LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)
            val before = source.first()
            assertEquals(listOf("a", "b"), LibraryQueries.tracks(before, query).map { it.id })
            counts.value = ListeningStatistics(mapOf("b" to 8L), mapOf("playlist" to 4L))
            store.updateMetadata(b.copy(title = "Updated B"))
            val after = source.first()
            assertNull(after.searchIndex)
            assertEquals(listOf("b", "a"), LibraryQueries.tracks(after, query).map { it.id })
            assertEquals("Updated B", after.tracks.single { it.id == "b" }.title)
            assertEquals(8L, after.tracks.single { it.id == "b" }.playCount)
            assertEquals(0L, after.tracks.single { it.id == "a" }.playCount)
            assertEquals(mapOf("playlist" to 4L), after.playlistPlays)
            assertEquals(mapOf("b" to 8L), after.trackPlays)
        } finally { database.close() }
    }

}

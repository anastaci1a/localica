package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupMergeTest {
    @Test fun additiveTransactionPreservesFreshMetadataDatesFailureStateAndOtherRoots() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val root = FolderSelection("root", "Root")
            val other = FolderSelection("other", "Other")
            val original = Track("song", "Original", fileName = "song.mp3", relativePath = "song.mp3")
            store.replaceRoot(ScanResult(root, listOf(original), 1, setOf(original.id)))
            val saved = store.current().tracks.single()
            store.updateMetadata(saved.copy(title = "Fresh edit", album = "Edited album"))
            store.mergeRoot(ScanResult(other, listOf(original.copy(uri = "alternate", relativePath = "nested/song.mp3")), 0))
            store.mergeRoot(ScanResult(root, listOf(original, original.copy(uri = "new", id = "new", title = "New")), 0))
            val snapshot = store.current()
            val current = snapshot.tracks.single { it.id == saved.id }
            assertEquals("Fresh edit", current.title)
            assertEquals("Edited album", current.album)
            assertEquals(saved.addedAt, current.addedAt)
            assertEquals(2, current.locations.size)
            assertEquals(listOf(MetadataFailure(saved.id, "root", "song.mp3")), snapshot.metadataFailures)
            assertNotNull(snapshot.tracks.single { it.id == "new" }.addedAt)
            val beforeRetry = store.current()
            store.mergeRoot(ScanResult(root, listOf(original), 0))
            assertEquals(beforeRetry, store.current())
            // Full refresh alone may replace tags and remove missing membership.
            store.replaceRoot(ScanResult(root, emptyList(), 0))
            assertEquals(listOf(other), store.current().folders.filter { it.uri == "other" })
            assertEquals("Fresh edit", store.current().tracks.single().title)
        } finally { database.close() }
    }

    @Test fun failedAdditiveTransactionRollsBackFolderAndTrackWrites() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LibraryDatabase::class.java).build()
        try {
            val dao = database.libraryDao()
            val store = RoomLibraryStore(dao)
            val before = store.current()
            try {
                dao.mergeRoot(FolderRecord("new-root", "New"), emptyList(),
                    listOf(FolderTrackRecord("new-root", "missing-track", "uri", "song.mp3")))
                fail("Missing track must violate the foreign key")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(before, store.current())
        } finally { database.close() }
    }
}

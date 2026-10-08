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
    @Test fun snapshotSurvivesReopenAndFailedTransactionRollsBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-library.db"
        context.deleteDatabase(name)
        val snapshot = LibrarySnapshot(FolderSelection("content://test/root", "Music"), listOf(
            Track("content://test/song", "Song", fileName = "song.mp3", relativePath = "nested/song.mp3"),
        ))
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            RoomLibraryStore(database.libraryDao()).replace(snapshot)
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val store = RoomLibraryStore(database.libraryDao())
            assertEquals(snapshot, store.current())
            try {
                store.replace(snapshot.copy(folder = FolderSelection("other", "Other"), tracks = snapshot.tracks + snapshot.tracks))
                fail("Duplicate primary keys must fail")
            } catch (_: android.database.SQLException) { }
            assertEquals(snapshot, store.current())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}

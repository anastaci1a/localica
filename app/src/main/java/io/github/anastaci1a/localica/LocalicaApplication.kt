package io.github.anastaci1a.localica

import android.app.Application
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import androidx.room.Room
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.LibraryDatabase
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.PersistedFolderPermissions
import io.github.anastaci1a.localica.library.RoomLibraryStore
import io.github.anastaci1a.localica.library.TreeScanner

class LocalicaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        java.io.File(cacheDir, "tag-views").listFiles()?.forEach { it.delete() }
    }
    val scanner by lazy { io.github.anastaci1a.localica.scanner.ScanController(this, library) }
    val lyrics by lazy { io.github.anastaci1a.localica.lyrics.LyricsRepository(this) }
    val tagEditing by lazy { io.github.anastaci1a.localica.editing.TagEditingController(this, library, queues, playlists) }
    val playlists by lazy {
        PlaylistController(Room.databaseBuilder(this, PlaylistDatabase::class.java, "playlists.db").build().playlists())
    }
    val queues by lazy {
        QueueController(Room.databaseBuilder(this, QueueDatabase::class.java, "queues.db").addMigrations(QueueDatabase.MIGRATION_1_2, QueueDatabase.MIGRATION_2_3).build().queues(),
            QueuePreferences(this), library)
    }
    val library by lazy {
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5).build()
        LibraryRepository(RoomLibraryStore(database.libraryDao()), TreeScanner(AndroidDocuments(this)),
            PersistedFolderPermissions(contentResolver))
    }
}

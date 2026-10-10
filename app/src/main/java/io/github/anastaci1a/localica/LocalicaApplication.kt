package io.github.anastaci1a.localica

import android.app.Application
import androidx.room.Room
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.LibraryDatabase
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.PersistedFolderPermissions
import io.github.anastaci1a.localica.library.RoomLibraryStore
import io.github.anastaci1a.localica.library.TreeScanner

class LocalicaApplication : Application() {
    val library by lazy {
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").addMigrations(LibraryDatabase.MIGRATION_1_2).build()
        LibraryRepository(RoomLibraryStore(database.libraryDao()), TreeScanner(AndroidDocuments(this)),
            PersistedFolderPermissions(contentResolver))
    }
}

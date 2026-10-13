package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistDatabaseTest {
    @Test fun versionTwoMigratesWithoutRenamingLegacyDuplicatesOrLosingOccurrences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "playlist-migration-test.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, 0, null)
        val track = Track("missing", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        old.execSQL("CREATE TABLE playlists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, modifiedAt INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("CREATE TABLE playlist_entries (id TEXT NOT NULL PRIMARY KEY, playlistId TEXT NOT NULL, ordinal INTEGER NOT NULL, track TEXT NOT NULL, FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_playlist_entries_playlistId ON playlist_entries(playlistId)")
        old.execSQL("INSERT INTO playlists VALUES ('a', 'Mix', 1, 2), ('b', 'mix', 3, 4)")
        old.execSQL("INSERT INTO playlist_entries VALUES ('one', 'a', 0, ?), ('two', 'a', 1, ?)", arrayOf(TrackSnapshot.encode(track), TrackSnapshot.encode(track)))
        old.version = 2; old.close()
        val database = Room.databaseBuilder(context, PlaylistDatabase::class.java, name).addMigrations(PlaylistDatabase.MIGRATION_2_3).build()
        try {
            val saved = database.playlists().read()
            assertEquals(listOf("Mix", "mix"), saved.map { it.name })
            assertEquals(listOf("one", "two"), saved.first().entries.map { it.id })
            assertTrue(saved.all { it.coverUri == null })
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun unavailableDuplicateReferencesSurviveReopenAndFailedEdits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "playlist-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, PlaylistDatabase::class.java, name).build()
        val track = Track("content://missing/song", "Title", fileName = "song.mp3", relativePath = "song.mp3", id = "song",
            comment = "Keep this", locations = listOf(TrackLocation("root", "content://missing/song", "song.mp3")))
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix", coverUri = "file:///private/playlist-covers/test.png"), listOf(track, track))
        val saved = listOf(playlist)
        try {
            database.playlists().save(emptyList(), saved)
            database.close()
            database = Room.databaseBuilder(context, PlaylistDatabase::class.java, name).build()
            assertEquals(saved, database.playlists().read())
            val invalid = listOf(playlist.copy(name = "Should roll back", entries = listOf(playlist.entries[0], playlist.entries[0])))
            try { database.playlists().save(saved, invalid); fail("Duplicate entry IDs must fail") }
            catch (_: android.database.SQLException) { }
            assertEquals(saved, database.playlists().read())
            val edited = listOf(PlaylistEdits.move(playlist.copy(name = "Renamed"), playlist.entries[1].id, -1))
            database.playlists().save(saved, edited)
            assertEquals(edited, database.playlists().read())
            database.playlists().save(edited, emptyList())
            assertTrue(database.playlists().read().isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }
}

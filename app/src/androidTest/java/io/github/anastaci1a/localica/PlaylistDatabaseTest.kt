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
    @Test fun unavailableDuplicateReferencesSurviveReopenAndFailedEdits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "playlist-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, PlaylistDatabase::class.java, name).build()
        val track = Track("content://missing/song", "Title", fileName = "song.mp3", relativePath = "song.mp3", id = "song",
            comment = "Keep this", locations = listOf(TrackLocation("root", "content://missing/song", "song.mp3")))
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(track, track))
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

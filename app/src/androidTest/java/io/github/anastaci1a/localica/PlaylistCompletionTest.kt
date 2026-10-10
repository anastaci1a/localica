package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PlaylistCompletionTest {
    @Test fun completionReflectsCommittedStateAndFailedSavesKeepSelectionEligible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(database.playlists(), scope)
        try {
            controller.state.first { !it.loading }
            val created = CompletableDeferred<Boolean>()
            controller.create("Mix", complete = { created.complete(it) })
            assertTrue(withTimeout(5000) { created.await() })
            val before = controller.state.value.playlists.single()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_entry BEFORE INSERT ON playlist_entries BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END")
            val added = CompletableDeferred<Boolean>()
            controller.append(before.id, listOf(Track("missing", "Song", fileName = "song.mp3", relativePath = "song.mp3"))) { added.complete(it) }
            assertFalse(withTimeout(5000) { added.await() })
            assertEquals(before, controller.state.value.playlists.single())
            assertNotNull(controller.state.value.problem)
        } finally { scope.cancel(); database.close() }
    }
}

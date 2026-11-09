package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.toast.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ToastPlaylistProducerTest {
    @Test fun failedPlaylistWriteEmitsNoOutcomeAndKeepsTheCommittedRows() = runBlocking {
        fixture(ToastQueue()) { db, controller, toasts ->
            val created = CompletableDeferred<Boolean>()
            controller.create("Mix", complete = { created.complete(it) })
            assertTrue(withTimeout(10_000) { created.await() })
            val before = controller.state.value.playlists.single()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_entry BEFORE INSERT ON playlist_entries BEGIN SELECT RAISE(ABORT, 'Persistence probe'); END")
            val appended = CompletableDeferred<Boolean>()
            controller.append(before.id, listOf(track())) { appended.complete(it) }
            assertFalse(withTimeout(10_000) { appended.await() })
            assertEquals(listOf(before), db.playlists().read())
            assertNull(toasts.state.value.current)
            assertEquals(0L, toasts.committedOutcomes.state.value.total)
        }
    }

    @Test fun committedDuplicateRowsSurviveThrowingCompletionAndFullAdmission() = runBlocking {
        for (full in listOf(false, true)) {
            val queue = ToastQueue(capacity = if (full) 1 else 32,
                onUndeliveredCommittedToast = { throw IllegalStateException("Diagnostic probe") })
            if (full) queue.showToast(ToastMessage("Existing action", tap = ToastCommand.OpenList("reserved", "entry")))
            fixture(queue) { db, controller, toasts ->
                val completions = CopyOnWriteArrayList<Boolean>()
                val created = CompletableDeferred<Boolean>()
                controller.create("Mix", listOf(track(), track())) {
                    completions += it; created.complete(it); throw IllegalStateException("Completion probe")
                }
                assertTrue(withTimeout(10_000) { created.await() })
                val saved = db.playlists().read().single()
                val barrier = CompletableDeferred<Boolean>()
                controller.rename(saved.id, "Renamed") { barrier.complete(it) }
                assertTrue(withTimeout(10_000) { barrier.await() })
                assertEquals(listOf(true), completions)
                assertEquals(2, saved.entries.size)
                assertEquals(2, saved.entries.map { it.id }.distinct().size)
                assertEquals(1, saved.entries.map { it.track.id }.distinct().size)
                assertNull(controller.state.value.problem)
                val report = toasts.committedOutcomes.state.value
                assertEquals(1L, report.total)
                val command = report.last!!.content.tap as ToastCommand.OpenList
                assertEquals(saved.id, command.listId)
                assertEquals(saved.entries.first().id, command.firstAddedEntryId)
                assertEquals(if (full) ToastAdmission.Full else ToastAdmission.Admitted, report.last!!.admission)
                assertEquals(if (full) 1L else 0L, report.reportingFailures)
            }
        }
    }

    private fun track() = Track("content://toast-playlist/duplicate", "Song", fileName = "song.mp3", relativePath = "song.mp3")
    private suspend fun fixture(toasts: ToastQueue,
        action: suspend (PlaylistDatabase, PlaylistController, ToastQueue) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val controller = PlaylistController(database.playlists(), scope, toasts = toasts)
            withTimeout(10_000) { controller.state.first { !it.loading } }
            action(database, controller, toasts)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); database.close() }
    }
}

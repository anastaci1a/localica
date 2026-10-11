package io.github.anastaci1a.localica

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GlobalPlaybackModesTest {
    @Test fun globalModesKeepVisibleOrderAndResetTraversalOnSelectionWhileRandomizePersists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val previousSettings = preferences.read()
        preferences.write(QueueSettings(switching = QueueSwitch.Paused))
        val file = File(context.cacheDir, "mode-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = (0..7).map { Track(file.toURI().toString(), "Track $it", fileName = file.name, relativePath = file.name, id = "track:$it") }
        val first = QueueEdits.create(tracks, 2, "First")
        val second = QueueEdits.create(tracks.reversed(), 1, "Second")
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(first, second), first.id)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun verifyTraversal(startId: String) {
            instrumentation.runOnMainSync {
                val timeline = player.currentTimeline
                var index = timeline.getFirstWindowIndex(true)
                val visited = mutableListOf<String>()
                while (index != C.INDEX_UNSET && visited.size <= tracks.size) {
                    visited += player.getMediaItemAt(index).mediaId
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                }
                assertEquals(startId, visited.first())
                assertEquals(tracks.size, visited.size)
                assertEquals(tracks.size, visited.toSet().size)
                assertTrue(player.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            }
        }
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            await { !queues.state.value.loading }
            queues.shuffle(); queues.repeat()
            await { queues.state.value.settings.shuffle && queues.state.value.settings.repeat == QueueRepeat.All }
            await { var ready = false; instrumentation.runOnMainSync { ready = player.mediaItemCount == tracks.size && player.shuffleModeEnabled }; ready }
            verifyTraversal(first.currentEntryId!!)
            assertEquals(first.entries, queues.state.value.library.active!!.entries)
            queues.selectEntry(first.entries[5].id)
            await { queues.state.value.library.active?.currentEntryId == first.entries[5].id }
            await { var changed = false; instrumentation.runOnMainSync { changed = player.currentMediaItem?.mediaId == first.entries[5].id }; changed }
            verifyTraversal(first.entries[5].id)
            queues.switchTo(second.id)
            await { var changed = false; instrumentation.runOnMainSync { changed = player.currentMediaItem?.mediaId == second.currentEntryId }; changed }
            verifyTraversal(second.currentEntryId!!)
            assertTrue(QueuePreferences(context).read().shuffle)
            assertEquals(QueueRepeat.All, QueuePreferences(context).read().repeat)
            queues.seek(1234)
            await { queues.state.value.library.active?.positionMs == 1234L }
            val done = CompletableDeferred<Boolean>()
            queues.randomize(second.id) { done.complete(it) }
            assertTrue(runBlocking { withTimeout(10000) { done.await() } })
            val randomized = queues.state.value.library.active!!
            assertEquals(second.entries.map { it.id }.toSet(), randomized.entries.map { it.id }.toSet())
            assertEquals(second.currentEntryId, randomized.currentEntryId)
            assertEquals(1234L, randomized.positionMs)
            assertEquals(randomized.entries, runBlocking { db.queues().read().active!!.entries })
            verifyTraversal(second.currentEntryId!!)
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); file.delete(); preferences.write(previousSettings)
        }
    }
}

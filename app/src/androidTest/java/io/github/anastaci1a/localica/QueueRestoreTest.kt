package io.github.anastaci1a.localica

import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QueueRestoreTest {
    @Test fun newControllerRestoresPersistedOccurrencePositionAndPausedState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        preferences.write(QueueSettings(repeat = QueueRepeat.All))
        val file = File(context.cacheDir, "restore-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val song = Track(Uri.fromFile(file).toString(), "Restore", fileName = file.name, relativePath = file.name)
        val saved = QueueEdits.create(listOf(song, song), song.id, "Saved").let {
            it.copy(currentEntryId = it.entries.last().id, positionMs = 2345, repeat = QueueRepeat.All)
        }
        runBlocking { database.queues().save(QueueLibrary(), QueueLibrary(listOf(saved), saved.id)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        var released = false
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                queues = QueueController(database.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            await { !queues.state.value.loading }
            await {
                var restored = false
                instrumentation.runOnMainSync {
                    restored = player.currentMediaItem?.mediaId == saved.currentEntryId && player.currentPosition == 2345L && player.playbackState == androidx.media3.common.Player.STATE_READY
                }
                restored
            }
            instrumentation.runOnMainSync {
                assertFalse(player.playWhenReady)
                assertEquals(queues.state.value.library.active!!.journey!!.window().size, player.mediaItemCount)
                assertEquals(1, queues.state.value.library.active!!.journey!!.history.size)
                assertEquals(saved.entries.first().id, queues.state.value.library.active!!.journey!!.previous!!.entryId)
                assertEquals(saved.entries.last().id, player.currentMediaItem?.mediaId)
                queues.seek(3456)
            }
            await { queues.state.value.library.active?.positionMs == 3456L }
            instrumentation.runOnMainSync { queues.detach(player); player.release(); released = true }
            await { runBlocking { database.queues().read().active?.positionMs == 3456L } }
        } finally {
            instrumentation.runOnMainSync {
                if (!released) { queues.detach(player); player.release() }
                scope.cancel()
            }
            database.close()
            file.delete()
            preferences.write(original)
        }
    }
}

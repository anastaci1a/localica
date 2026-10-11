package io.github.anastaci1a.localica

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
class PreviousPlaybackTest {
    @Test fun thresholdRestartsOrMovesBackWithoutResumingPausedPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val savedSettings = preferences.read()
        preferences.write(QueueSettings())
        val file = File(context.cacheDir, "previous-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = (0..2).map { Track(file.toURI().toString(), "Track $it", fileName = file.name, relativePath = file.name, id = "previous:$it") }
        val queue = QueueEdits.create(tracks, 1, "Previous test")
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun ready() = await { var result = false; instrumentation.runOnMainSync { result = player.playbackState == Player.STATE_READY }; result }
        fun checkPrevious(position: Long, expectedIndex: Int) {
            instrumentation.runOnMainSync { player.pause(); player.seekTo(1, position) }
            ready()
            queues.previous()
            await { var result = false; instrumentation.runOnMainSync {
                result = player.currentMediaItemIndex == expectedIndex && player.currentPosition == 0L
            }; result }
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady) }
        }
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            ready()
            checkPrevious(4999, 0)
            checkPrevious(5000, 1)
            queues.settings(QueueSettings(previousThresholdSeconds = 2))
            await { queues.state.value.settings.previousThresholdSeconds == 2 }
            checkPrevious(1999, 0)
            checkPrevious(2000, 1)
            queues.settings(QueueSettings(previousBehavior = PreviousBehavior.PreviousSong))
            await { queues.state.value.settings.previousBehavior == PreviousBehavior.PreviousSong }
            checkPrevious(5000, 0)
            queues.settings(QueueSettings(previousBehavior = PreviousBehavior.RestartSong, previousThresholdSeconds = 8))
            await { queues.state.value.settings.previousBehavior == PreviousBehavior.RestartSong }
            checkPrevious(1000, 1)
            assertEquals(queues.state.value.settings, QueuePreferences(context).read())
            queues.settings(QueueSettings())
            await { queues.state.value.settings == QueueSettings() }
            instrumentation.runOnMainSync { player.seekTo(0, 1000) }
            ready()
            queues.previous()
            await { var result = false; instrumentation.runOnMainSync { result = player.currentMediaItemIndex == 0 && player.currentPosition == 0L }; result }
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); file.delete(); preferences.write(savedSettings)
        }
    }
}

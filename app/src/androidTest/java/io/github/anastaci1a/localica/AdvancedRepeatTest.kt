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
class AdvancedRepeatTest {
    @Test fun endPoliciesPauseAdvanceResetAndRestoreTheNextQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val saved = preferences.read()
        preferences.write(QueueSettings())
        val file = File(context.cacheDir, "repeat-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = (0..2).map { Track(file.toURI().toString(), "Track $it", fileName = file.name, relativePath = file.name, id = "repeat:$it") }
        val first = QueueEdits.create(tracks, 0, "First")
        val second = QueueEdits.create(tracks, 1, "Second").copy(positionMs = 1000)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(first, second), first.id)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun check(condition: () -> Boolean) = await { var result = false; instrumentation.runOnMainSync { result = condition() }; result }
        fun finish(index: Int, policy: AdvancedRepeat) {
            queues.settings(QueueSettings(advancedRepeat = policy))
            await { queues.state.value.settings.advancedRepeat == policy }
            instrumentation.runOnMainSync { player.pause(); player.seekTo(index, 0) }
            check { player.playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync { player.seekTo(index, player.duration - 150); player.play() }
        }
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            check { player.playbackState == Player.STATE_READY }
            finish(0, AdvancedRepeat(songEnd = SongEnd.Stop))
            check { !player.playWhenReady && player.currentMediaItemIndex == 0 && player.currentPosition >= player.duration - 30 }
            finish(0, AdvancedRepeat(songEnd = SongEnd.NextPaused))
            check { !player.playWhenReady && player.currentMediaItemIndex == 1 && player.currentPosition == 0L }
            finish(0, AdvancedRepeat(songEnd = SongEnd.Next))
            check { player.playWhenReady && player.currentMediaItemIndex == 1 }
            finish(0, AdvancedRepeat(songEnd = SongEnd.Repeat))
            check { player.playWhenReady && player.currentMediaItemIndex == 0 && player.currentPosition < 1000 }
            finish(2, AdvancedRepeat(queueEnd = QueueEnd.Repeat))
            check { player.playWhenReady && player.currentMediaItemIndex == 0 }
            finish(2, AdvancedRepeat(resetToFirst = true))
            check { !player.playWhenReady && player.currentMediaItemIndex == 0 && player.currentPosition == 0L }
            finish(2, AdvancedRepeat(songEnd = SongEnd.NextPaused, queueEnd = QueueEnd.Repeat))
            check { !player.playWhenReady && player.currentMediaItemIndex == 0 && player.currentPosition == 0L }
            finish(2, AdvancedRepeat(queueEnd = QueueEnd.NextQueue, resetToFirst = true))
            check { queues.state.value.library.activeId == second.id && player.currentMediaItemIndex == 1 && player.playWhenReady }
            instrumentation.runOnMainSync { assertTrue(player.currentPosition >= 1000) }
            await { queues.state.value.library.queues.first { it.id == first.id }.currentEntryId == first.entries.first().id }
            assertEquals(queues.state.value.settings, QueuePreferences(context).read())
            queues.repeat()
            await { queues.state.value.settings.advancedRepeat == null }
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            preferences.write(saved); db.close(); file.delete()
        }
    }
}

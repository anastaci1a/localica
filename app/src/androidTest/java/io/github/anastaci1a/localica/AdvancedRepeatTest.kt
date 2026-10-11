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
        val events = ArrayDeque<String>()
        fun record(event: String) {
            events.addLast("$event: ${player.currentMediaItemIndex} ${player.currentPosition} ${player.playWhenReady}")
            while (events.size > 50) events.removeFirst()
        }
        fun check(condition: () -> Boolean) {
            try { await { var result = false; instrumentation.runOnMainSync { result = condition() }; result } }
            catch (failure: AssertionError) {
                var detail = ""
                instrumentation.runOnMainSync {
                    val active = queues.state.value.library.active
                    detail = "policy=${queues.state.value.settings.advancedRepeat}, playing=${player.playWhenReady}, " +
                        "state=${player.playbackState}, position=${player.currentPosition}/${player.duration}, " +
                        "entry=${active?.entries?.indexOfFirst { it.id == player.currentMediaItem?.mediaId }}, " +
                        "journey=${active?.journey}, problem=${queues.state.value.problem}\n${events.joinToString("\n")}"
                }
                throw AssertionError(detail, failure)
            }
        }
        fun finish(index: Int, policy: AdvancedRepeat) {
            instrumentation.runOnMainSync { events.clear() }
            queues.settings(QueueSettings(advancedRepeat = policy))
            await { queues.state.value.settings.advancedRepeat == policy }
            val previousVisit = queues.state.value.library.active?.journey?.current?.token
            queues.selectEntry(first.entries[index].id)
            check {
                val selected = queues.state.value.library.active?.journey?.current
                selected?.token != previousVisit && selected?.entryId == first.entries[index].id &&
                    player.currentMediaItem?.mediaMetadata?.extras?.getString("queueVisit") == selected.token &&
                    player.playbackState == Player.STATE_READY
            }
            instrumentation.runOnMainSync { player.seekTo(player.duration - 150); player.play() }
        }
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            check { player.playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync { player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = record("ready $playWhenReady/$reason")
                override fun onPlaybackStateChanged(playbackState: Int) = record("state $playbackState")
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
                    record("jump $reason ${oldPosition.mediaItemIndex}/${oldPosition.positionMs} -> ${newPosition.mediaItemIndex}/${newPosition.positionMs}")
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = record("item $reason")
            }) }
            finish(0, AdvancedRepeat(songEnd = SongEnd.Stop))
            check { !player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[0].id && player.currentPosition >= player.duration - 30 }
            finish(0, AdvancedRepeat(songEnd = SongEnd.NextPaused))
            check { !player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[1].id && player.currentPosition == 0L }
            finish(0, AdvancedRepeat(songEnd = SongEnd.Next))
            check { player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[1].id }
            finish(0, AdvancedRepeat(songEnd = SongEnd.Repeat))
            check { player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[0].id && player.currentPosition < 1000 }
            finish(2, AdvancedRepeat(queueEnd = QueueEnd.Repeat))
            check { player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[0].id }
            finish(2, AdvancedRepeat(resetToFirst = true))
            check { !player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[0].id && player.currentPosition == 0L }
            repeat(4) {
                finish(2, AdvancedRepeat(songEnd = SongEnd.NextPaused, queueEnd = QueueEnd.Repeat))
                check { !player.playWhenReady && player.currentMediaItem?.mediaId == first.entries[0].id &&
                    player.currentPosition == 0L && player.playbackState == Player.STATE_READY }
            }
            finish(2, AdvancedRepeat(queueEnd = QueueEnd.NextQueue, resetToFirst = true))
            check { queues.state.value.library.activeId == second.id && player.currentMediaItem?.mediaId == second.entries[1].id && player.playWhenReady }
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

package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
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
class PreparedQueuePlaybackTest {
    @Test fun largePreparedMovesKeepNativeTokensAndRepairInactiveRestoredJourney() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        preferences.write(QueueSettings())
        val file = File(context.cacheDir, "prepared-queue-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val song = Track(file.toURI().toString(), "Duplicate", fileName = file.name, relativePath = file.name)
        val active = QueueEdits.create(List(13_000) { song }, 0, "Large queue").let {
            it.copy(positionMs = 1000, journey = QueueJourney.start(it.entries.map { entry -> entry.id }, it.currentEntryId, JourneyModes()))
        }
        val inactive = QueueEdits.create(List(3) { song }, 0, "Restored queue").copy(
            journey = QueueJourney(history = listOf(QueueVisit("removed", 0)), cursor = 0))
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(active, inactive), active.id)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun barrier() {
            val done = CompletableDeferred<Boolean>()
            queues.endFileEdit(null) { done.complete(it) }
            assertTrue(runBlocking { withTimeout(30_000) { done.await() } })
            assertNull(queues.state.value.problem)
        }
        fun at(visit: QueueVisit, action: String, requestedAt: Long) {
            await {
                var result = false
                instrumentation.runOnMainSync {
                    result = player.currentMediaItem?.mediaMetadata?.extras?.getString("queueVisit") == visit.token &&
                        queues.state.value.library.active?.journey?.current == visit && player.playbackState == Player.STATE_READY
                }
                result
            }
            val readyAfter = SystemClock.elapsedRealtime() - requestedAt
            barrier()
            // Diagnostic wall times include polling/dispatch; they are not physical-device audio latency budgets.
            Log.i("QueueScale", "entries=13000 action=$action readyMs=$readyAfter barrierMs=${SystemClock.elapsedRealtime() - requestedAt}")
            assertEquals(visit, runBlocking { db.queues().read().active!!.journey!!.current })
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady); assertTrue(player.mediaItemCount <= 129) }
        }
        fun move(action: String, visit: QueueVisit, command: () -> Unit) {
            val requestedAt = SystemClock.elapsedRealtime()
            command()
            at(visit, action, requestedAt)
        }
        try {
            val restoreAt = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            at(active.journey!!.current!!, "restore", restoreAt)
            assertEquals(inactive.journey, queues.state.value.library.queues.last().journey)
            val firstNext = queues.state.value.library.active!!.journey!!.next!!
            move("next", firstNext) { queues.next() }
            val repaired = queues.state.value.library.queues.last().journey!!
            assertEquals(inactive.currentEntryId, repaired.current?.entryId)
            assertTrue(repaired.history.none { it.entryId == "removed" })
            assertEquals(repaired, runBlocking { db.queues().read().queues.last().journey })
            move("previous", active.journey.current!!) { queues.previous(alwaysSong = true) }
            move("forward-history", firstNext) { queues.next() }
            for (shuffle in listOf(false, true)) {
                val settingsAt = SystemClock.elapsedRealtime()
                queues.settings(QueueSettings(shuffle = shuffle, repeat = QueueRepeat.All))
                barrier()
                Log.i("QueueScale", "entries=13000 action=repeat-settings-shuffle-$shuffle barrierMs=${SystemClock.elapsedRealtime() - settingsAt}")
                val next = queues.state.value.library.active!!.journey!!.next!!
                move("repeat-next-shuffle-$shuffle", next) { queues.next() }
                val previous = queues.state.value.library.active!!.journey!!.previous!!
                move("repeat-previous-shuffle-$shuffle", previous) { queues.previous(alwaysSong = true) }
                move("repeat-forward-shuffle-$shuffle", next) { queues.next() }
            }
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); file.delete(); preferences.write(original)
        }
    }
}

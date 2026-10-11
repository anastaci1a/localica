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
class QueueJourneyPlaybackTest {
    @Test fun rewoundHistoryRestoresPausedAndBranchingKeepsPreviouslyVisitedSongsIneligible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        preferences.write(QueueSettings(shuffle = true))
        val file = File(context.cacheDir, "journey-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val song = Track(file.toURI().toString(), "Duplicate", fileName = file.name, relativePath = file.name)
        val queue = QueueEdits.create(List(8) { song }, 0, "History")
        val ids = queue.entries.map { it.id }
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun check(condition: () -> Boolean) = await {
            var result = false
            instrumentation.runOnMainSync { result = condition() }
            result
        }
        fun start() {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            check { player.playbackState == Player.STATE_READY }
        }
        fun at(index: Int) {
            check { player.currentMediaItem?.mediaId == ids[index] && queues.state.value.library.active?.currentEntryId == ids[index] }
            instrumentation.runOnMainSync { player.pause() }
            await { runBlocking { db.queues().read().active?.currentEntryId == ids[index] } }
        }
        try {
            start()
            assertNull(queues.state.value.library.active!!.journey!!.previous)
            for (index in listOf(3, 1, 5)) { queues.selectEntry(ids[index]); at(index) }
            queues.previous(alwaysSong = true); at(1)
            queues.previous(alwaysSong = true); at(3)
            val rewound = queues.state.value.library.active!!.journey!!
            assertEquals(listOf(0, 3, 1, 5).map { ids[it] }, rewound.history.map { it.entryId })
            assertEquals(1, rewound.cursor)
            instrumentation.runOnMainSync { queues.detach(player); player.release() }
            await { runBlocking { db.queues().read().active?.journey == rewound } }
            instrumentation.runOnMainSync { scope.cancel() }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            start()
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady) }
            assertEquals(rewound, queues.state.value.library.active!!.journey)
            queues.next(); at(1)
            queues.next(); at(5)
            queues.previous(alwaysSong = true); at(1)
            queues.previous(alwaysSong = true); at(3)
            queues.selectEntry(ids[6]); at(6)
            val branched = queues.state.value.library.active!!.journey!!
            assertEquals(listOf(0, 3, 6).map { ids[it] }, branched.history.map { it.entryId })
            assertTrue(branched.visited.containsAll(listOf(ids[1], ids[5])))
            assertTrue(branched.future.none { it.entryId in branched.visited })
            val expectedNext = branched.next!!
            instrumentation.runOnMainSync {
                assertEquals(expectedNext.token, player.getMediaItemAt(player.nextMediaItemIndex).mediaMetadata.extras!!.getString("queueVisit"))
                assertEquals(listOf(song.uri, song.uri), queues.artworkNeighbors())
            }
            val moved = CompletableDeferred<Boolean>()
            queues.moveEntry(expectedNext.entryId, -2, queue.id) { moved.complete(it) }
            assertTrue(runBlocking { withTimeout(10000) { moved.await() } })
            assertEquals(expectedNext, queues.state.value.library.active!!.journey!!.next)
            // Natural advancement uses the exact planned visit and leaves the preceding visit buffered.
            check { player.playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync { player.seekTo(player.duration - 150); player.play() }
            check { queues.state.value.library.active?.journey?.current == expectedNext }
            instrumentation.runOnMainSync { player.pause() }
            queues.previous(alwaysSong = true); at(6)
            queues.removeEntry(expectedNext.entryId)
            await { queues.state.value.library.active!!.entries.none { it.id == expectedNext.entryId } }
            assertTrue(queues.state.value.library.active!!.journey!!.history.none { it.entryId == expectedNext.entryId })
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); file.delete(); preferences.write(original)
        }
    }
}

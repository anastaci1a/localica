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
class PlayNextJourneyTest {
    @Test fun orderedPlayNextAfterPrevious() = reproduce(false)
    @Test fun shuffledPlayNextAfterPrevious() = reproduce(true)
    @Test fun batchesKeepNativeOrderAndPositionAcrossRestoreAutoplayAndModeReset() = reproduce(true, true)
    @Test fun forwardSkipsPreserveScheduledTokensAndPriority() {
        val original = listOf("A", "B", "C", "D")
        val ids = listOf("A", "X", "Y", "B", "C", "D")
        val path = QueueJourney.start(original, "A", JourneyModes(shuffle = true), kotlin.random.Random(4))
            .playNext(listOf("X", "Y"), ids)
        val pending = path.history.drop(1)
        val selected = path.select("D", ids)
        assertEquals(pending, selected.history.drop(selected.cursor + 1))
        val arrived = path.arrive(path.future.last().token, ids)
        assertEquals(pending, arrived.history.drop(arrived.cursor + 1))
        assertEquals(setOf("A", "D"), selected.visited)
        assertEquals("X", arrived.next!!.entryId)
    }

    @Test fun previousAcrossCycleBoundaryDoesNotReplayScheduledOccurrencesInActiveCycle() {
        val original = listOf("A", "B", "C")
        var path = QueueJourney.start(original, "A", JourneyModes(shuffle = true, loop = true), kotlin.random.Random(8))
        repeat(4) { path = path.arrive(path.next!!.token, original) }
        val cycle = path.cycle
        path = path.arrive(path.history.first().token, original)
        val ids = original + listOf("X", "Y")
        path = path.playNext(listOf("X", "Y"), ids)
        assertEquals("Scheduled visits belong to the active cycle after Previous", cycle, path.next!!.cycle)
        val counts = mutableMapOf<String, Int>()
        repeat(10) {
            val next = path.next!!
            if (next.cycle <= cycle && next.entryId in listOf("X", "Y")) counts[next.entryId] = counts.getOrDefault(next.entryId, 0) + 1
            path = path.arrive(next.token, ids)
        }
        assertEquals(mapOf("X" to 1, "Y" to 1), counts)
    }

    private fun reproduce(shuffled: Boolean, comprehensive: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        preferences.write(QueueSettings(shuffle = shuffled))
        val file = File(context.cacheDir, "journey-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val song = Track(file.toURI().toString(), "Duplicate", fileName = file.name, relativePath = file.name)
        val extraFile = File(context.cacheDir, "journey-next-tone.mp3")
        file.copyTo(extraFile, overwrite = true)
        val extra = song.copy(uri = extraFile.toURI().toString(), id = "next-song", title = "Next")
        val queue = QueueEdits.create(List(3) { song }, 0, "History")
        val ids = queue.entries.map { it.id }
        val databaseName = "play-next-journey.db"
        context.deleteDatabase(databaseName)
        fun openDatabase() = Room.databaseBuilder(context, QueueDatabase::class.java, databaseName).build()
        var db = openDatabase()
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
            queues.next()
            check { queues.state.value.library.active!!.currentEntryId != ids[0] }
            queues.previous(alwaysSong = true); at(0)
            queues.seek(1200)
            check { player.currentPosition == 1200L }
            val before = queues.state.value.library.active!!
            val done = CompletableDeferred<Boolean>()
            queues.act(listOf(song), SongTap.PlayNext) { done.complete(it) }
            assertTrue(runBlocking { withTimeout(10000) { done.await() } })
            val after = queues.state.value.library.active!!
            val inserted = after.entries[1].id
            assertFalse(inserted in ids)
            assertEquals(before.journey!!.current, after.journey!!.current)
            instrumentation.runOnMainSync {
                assertEquals("Native next must be the Play Next occurrence", inserted,
                    player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
            }
            assertEquals(inserted, after.journey!!.next!!.entryId)
            if (comprehensive) {
                fun insertBatch(tracks: List<Track>): List<String> {
                    val oldIds = queues.state.value.library.active!!.entries.map { it.id }.toSet()
                    val completed = CompletableDeferred<Boolean>()
                    queues.act(tracks, SongTap.PlayNext) { completed.complete(it) }
                    assertTrue(runBlocking { withTimeout(10000) { completed.await() } })
                    return queues.state.value.library.active!!.entries.filterNot { it.id in oldIds }.map { it.id }
                }
                val earlier = insertBatch(listOf(extra, song))
                val newest = insertBatch(listOf(song, extra))
                val expected = newest + earlier + inserted
                val saved = queues.state.value.library.active!!
                assertEquals(expected, saved.entries.drop(1).take(expected.size).map { it.id })
                assertEquals(before.journey!!.current, saved.journey!!.current)
                assertEquals(before.journey!!.visited, saved.journey!!.visited)
                fun assertNativeNext(id: String) {
                    val active = queues.state.value.library.active!!
                    val visit = active.journey!!.next!!
                    assertEquals(id, visit.entryId)
                    instrumentation.runOnMainSync {
                        val item = player.getMediaItemAt(player.nextMediaItemIndex)
                        assertEquals(id, item.mediaId)
                        assertEquals(visit.token, item.mediaMetadata.extras!!.getString("queueVisit"))
                        assertEquals(active.entries.first { it.id == id }.track.uri, queues.artworkNeighbors().first())
                        assertEquals(active.entries.first { it.id == id }.track.uri, queues.artworkPreloadUris().first())
                        assertEquals(visit, active.journey!!.artworkWindow().first())
                    }
                }
                instrumentation.runOnMainSync {
                    assertEquals(1200L, player.currentPosition)
                    assertFalse(player.playWhenReady)
                    queues.detach(player); player.release()
                }
                await { runBlocking { db.queues().read().active?.journey == saved.journey } }
                instrumentation.runOnMainSync { scope.cancel() }
                db.close()
                db = openDatabase()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                start()
                assertEquals(saved.journey, queues.state.value.library.active!!.journey)
                instrumentation.runOnMainSync { assertEquals(1200L, player.currentPosition); assertFalse(player.playWhenReady) }
                expected.forEachIndexed { index, id ->
                    assertNativeNext(id)
                    if (index % 2 == 0) queues.next() else instrumentation.runOnMainSync {
                        player.seekTo(player.duration - 150); player.play()
                    }
                    check { player.currentMediaItem?.mediaId == id && queues.state.value.library.active!!.currentEntryId == id }
                    instrumentation.runOnMainSync { player.pause() }
                    check { player.playbackState == Player.STATE_READY }
                }
                assertNativeNext(before.journey!!.next!!.entryId)
                val anchor = queues.state.value.library.active!!.journey!!.current
                queues.settings(queues.state.value.settings.copy(shuffle = false))
                check { !queues.state.value.library.active!!.journey!!.modes.shuffle }
                queues.settings(queues.state.value.settings.copy(shuffle = true))
                check { queues.state.value.library.active!!.journey!!.modes.shuffle }
                assertEquals(anchor, queues.state.value.library.active!!.journey!!.current)
                assertNativeNext(queues.state.value.library.active!!.journey!!.next!!.entryId)
                val afterReset = insertBatch(listOf(extra, song))
                assertNativeNext(afterReset.first())
            } else {
                queues.next()
                check { player.currentMediaItem?.mediaId == inserted }
            }
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); context.deleteDatabase(databaseName)
            file.delete(); extraFile.delete(); preferences.write(original)
        }
    }
}

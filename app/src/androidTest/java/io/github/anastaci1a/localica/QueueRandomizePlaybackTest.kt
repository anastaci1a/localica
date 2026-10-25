package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.random.Random

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueRandomizePlaybackTest {
    @Test fun pausedActiveRandomizePinsTheSecondDuplicateAndPreservesPlayback() =
        activeRandomize(playing = false)

    @Test fun playingActiveRandomizePinsTheSecondDuplicateAndPreservesPlayback() =
        activeRandomize(playing = true)

    private fun activeRandomize(playing: Boolean) {
        for (shuffle in listOf(false, true)) fixture(shuffle, playing) { f ->
            val before = f.snapshot()
            val queue = before.state.library.active!!
            assertEquals("active-current", queue.currentEntryId)
            assertNotEquals(queue.currentEntryId, queue.entries.first().id)
            assertEquals(2, queue.entries.count { it.track.id == "duplicate" })
            assertEquals(1, queue.journey!!.cursor)
            assertEquals(3, queue.journey!!.history.size)
            assertEquals(queue.currentEntryId, before.mediaId)
            assertEquals(playing, before.playWhenReady)
            val seed = seedWithoutAccidentalPin(queue)
            val current = queue.entries.single { it.id == queue.currentEntryId }
            val expected = listOf(current) + queue.entries.filterNot { it.id == current.id }.shuffled(Random(seed))
            assertTrue(f.randomize(queue.id, seed))
            val after = f.snapshot()
            val randomized = after.state.library.active!!
            assertEquals("active-current", randomized.entries.first().id)
            assertEquals(expected, randomized.entries)
            assertOrderEdit(queue, randomized)
            assertEquals(before.state.library.queues.single { it.id == "inactive" },
                after.state.library.queues.single { it.id == "inactive" })
            f.assertPlaybackPreserved(before, after)
            val stored = f.db.queues().read().active!!
            assertEquals(randomized.copy(positionMs = stored.positionMs), stored)
            f.assertPositionPreserved(before, f.snapshot(), stored.positionMs)
        }
    }

    @Test fun inactiveRandomizeLeavesActivePlaybackAndTraversalAlone() {
        for (shuffle in listOf(false, true)) fixture(shuffle, playing = true) { f ->
            val before = f.snapshot()
            val inactive = before.state.library.queues.single { it.id == "inactive" }
            val seed = seedWithoutAccidentalPin(inactive)
            val expected = inactive.entries.shuffled(Random(seed))
            assertTrue(f.randomize(inactive.id, seed))
            val after = f.snapshot()
            val randomized = after.state.library.queues.single { it.id == inactive.id }
            assertEquals(expected, randomized.entries)
            assertOrderEdit(inactive, randomized)
            assertEquals(inactive.positionMs, randomized.positionMs)
            assertEquals(randomized, f.db.queues().read().queues.single { it.id == inactive.id })
            assertEquals(before.state.library.active!!.copy(positionMs = after.state.library.active!!.positionMs),
                after.state.library.active)
            assertEquals(before.timeline, after.timeline)
            f.assertPlaybackPreserved(before, after)
        }
    }

    @Test fun pausedActiveReverseKeepsTheSecondDuplicateAtItsReversedPositionAndPreservesPlayback() =
        activeReverse(playing = false)

    @Test fun playingActiveReverseKeepsTheSecondDuplicateAtItsReversedPositionAndPreservesPlayback() =
        activeReverse(playing = true)

    private fun activeReverse(playing: Boolean) {
        for (shuffle in listOf(false, true)) fixture(shuffle, playing, ascending = true) { f ->
            val before = f.snapshot()
            val queue = before.state.library.active!!
            assertEquals("active-current", queue.currentEntryId)
            assertEquals(2, queue.entries.count { it.track.id == "duplicate" })
            assertTrue(f.reverse(queue.id))
            val after = f.snapshot()
            val reversed = after.state.library.active!!
            assertEquals(queue.entries.reversed(), reversed.entries)
            // Unlike Randomize, Reverse must not pin the playing occurrence to the top.
            assertNotEquals(reversed.currentEntryId, reversed.entries.first().id)
            assertOrderEdit(queue, reversed)
            assertEquals(before.state.library.queues.single { it.id == "inactive" },
                after.state.library.queues.single { it.id == "inactive" })
            f.assertPlaybackPreserved(before, after)
            val stored = f.db.queues().read().active!!
            assertEquals(reversed.copy(positionMs = stored.positionMs), stored)
            f.assertPositionPreserved(before, f.snapshot(), stored.positionMs)
        }
    }

    @Test fun inactiveReverseLeavesActivePlaybackAndTraversalAlone() {
        for (shuffle in listOf(false, true)) fixture(shuffle, playing = true) { f ->
            val before = f.snapshot()
            val inactive = before.state.library.queues.single { it.id == "inactive" }
            assertTrue(f.reverse(inactive.id))
            val after = f.snapshot()
            val reversed = after.state.library.queues.single { it.id == inactive.id }
            assertEquals(inactive.entries.reversed(), reversed.entries)
            assertOrderEdit(inactive, reversed)
            assertEquals(inactive.positionMs, reversed.positionMs)
            assertEquals(reversed, f.db.queues().read().queues.single { it.id == inactive.id })
            assertEquals(before.state.library.active!!.copy(positionMs = after.state.library.active!!.positionMs),
                after.state.library.active)
            assertEquals(before.timeline, after.timeline)
            f.assertPlaybackPreserved(before, after)
        }
    }

    private fun seedWithoutAccidentalPin(queue: ListeningQueue): Int {
        // Make full-shuffle and current-first branches observably different, without chance passes.
        val seed = (0..100).first { queue.entries.shuffled(Random(it)).first().id != queue.currentEntryId }
        assertNotEquals(queue.currentEntryId, queue.entries.shuffled(Random(seed)).first().id)
        return seed
    }

    private fun assertOrderEdit(before: ListeningQueue, after: ListeningQueue) {
        assertEquals(before.entries.size, after.entries.size)
        assertEquals(before.entries.associateBy { it.id }, after.entries.associateBy { it.id })
        assertEquals(before.entries.groupingBy { it.track.id }.eachCount(),
            after.entries.groupingBy { it.track.id }.eachCount())
        assertEquals(SavedOrder(after.entries.map { it.id }), after.order)
        assertEquals(before.currentEntryId, after.currentEntryId)
        val history = before.journey!!
        val randomizedHistory = after.journey!!
        assertEquals(history.history, randomizedHistory.history)
        assertEquals(history.cursor, randomizedHistory.cursor)
        assertEquals(history.cycle, randomizedHistory.cycle)
        assertEquals(history.visited, randomizedHistory.visited)
        assertEquals(history.modes, randomizedHistory.modes)
        // Sequential future follows the new order; an existing shuffled plan stays intact.
        if (history.modes.shuffle) assertEquals(history, randomizedHistory)
        assertEquals(before.copy(entries = after.entries, order = after.order,
            positionMs = after.positionMs, journey = after.journey), after)
    }

    private data class Snapshot(val state: QueueUiState, val positionMs: Long,
        val observedAt: Long, val mediaId: String?, val visit: String?, val playWhenReady: Boolean,
        val isPlaying: Boolean, val shuffle: Boolean, val repeat: Int, val timeline: List<String?>)

    private class Fixture(val db: QueueDatabase, val preferences: QueuePreferences,
        val queues: QueueController, val player: ExoPlayer) {
        suspend fun snapshot(): Snapshot = withContext(Dispatchers.Main) {
            Snapshot(queues.state.value, player.currentPosition, SystemClock.elapsedRealtime(),
                player.currentMediaItem?.mediaId,
                player.currentMediaItem?.mediaMetadata?.extras?.getString("queueVisit"),
                player.playWhenReady, player.isPlaying, player.shuffleModeEnabled, player.repeatMode,
                (0 until player.mediaItemCount).map {
                    player.getMediaItemAt(it).mediaMetadata.extras?.getString("queueVisit")
                })
        }

        suspend fun randomize(id: String, seed: Int): Boolean {
            val done = CompletableDeferred<Boolean>()
            queues.randomize(id, Random(seed)) { done.complete(it) }
            return withTimeout(10_000) { done.await() }
        }

        suspend fun reverse(id: String): Boolean {
            val done = CompletableDeferred<Boolean>()
            queues.reverse(id) { done.complete(it) }
            return withTimeout(10_000) { done.await() }
        }

        suspend fun assertPlaybackPreserved(before: Snapshot, after: Snapshot) {
            assertNull(after.state.problem)
            assertEquals(before.state.library.activeId, after.state.library.activeId)
            assertEquals(before.state.settings, after.state.settings)
            assertEquals(before.state.settings, preferences.read())
            assertEquals(before.mediaId, after.mediaId)
            assertEquals(before.visit, after.visit)
            assertEquals(before.playWhenReady, after.playWhenReady)
            assertEquals(before.isPlaying, after.isPlaying)
            assertEquals(before.shuffle, after.shuffle)
            assertEquals(before.repeat, after.repeat)
            assertPositionPreserved(before, after, after.state.library.active!!.positionMs)
            if (before.isPlaying) awaitMain {
                player.isPlaying && player.currentMediaItem?.mediaId == before.mediaId &&
                    player.currentPosition > after.positionMs
            }
        }

        fun assertPositionPreserved(before: Snapshot, after: Snapshot, savedPosition: Long) {
            if (before.isPlaying) {
                // Playback advances during the serialized save. It must not restart or seek ahead.
                assertTrue(after.positionMs >= before.positionMs)
                assertTrue(after.positionMs <= before.positionMs + after.observedAt - before.observedAt + 250)
                assertTrue(savedPosition >= before.positionMs)
                assertTrue(savedPosition <= after.positionMs)
            } else {
                assertEquals(1234L, before.positionMs)
                assertEquals(before.positionMs, after.positionMs)
                assertEquals(before.positionMs, savedPosition)
            }
        }

        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
    }

    private fun fixture(shuffle: Boolean, playing: Boolean, ascending: Boolean = false, action: suspend (Fixture) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferencesName = "randomize-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferencesName, mode)
        })
        val settings = QueueSettings(shuffle = shuffle, repeat = QueueRepeat.All, switching = QueueSwitch.Paused)
        preferences.write(settings)
        val file = File(context.cacheDir, "$preferencesName.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val duplicate = Track(file.toURI().toString(), "Alpha", id = "duplicate", fileName = file.name, relativePath = file.name)
        fun queue(id: String): ListeningQueue {
            val entries = listOf(
                QueueEntry("$id-first", duplicate, "origin-first"),
                QueueEntry("$id-middle", duplicate.copy(id = "middle", title = "Middle"), "origin-middle"),
                QueueEntry("$id-current", duplicate, "origin-current"),
                QueueEntry("$id-last", duplicate.copy(id = "last", title = "Zulu"), "origin-last"),
            )
            val ids = entries.map { it.id }
            val journey = QueueJourney.start(ids, "$id-first", JourneyModes.from(settings), Random(17))
                .select("$id-current", ids, Random(18)).select("$id-middle", ids, Random(19)).copy(cursor = 1)
            val sorted = QueueEdits.sort(ListeningQueue(id = id, name = id, entries = entries,
                currentEntryId = "$id-current", positionMs = 1234, journey = journey), TrackSort.Title, ascending)
            return sorted.copy(journey = journey.reconcile(sorted.entries.map { it.id },
                sorted.currentEntryId, JourneyModes.from(settings)))
        }
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: ExoPlayer? = null
        try {
            db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue("active"), queue("inactive")), "active"))
            val f = withContext(Dispatchers.Main) {
                val native = ExoPlayer.Builder(context).build().apply { volume = 0f }
                player = native
                val queues = QueueController(db.queues(), preferences,
                    (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(native)
                Fixture(db, preferences, queues, native)
            }
            f.awaitMain { !f.queues.state.value.loading && f.player.playbackState == Player.STATE_READY }
            f.queues.playbackRequested(playing)
            f.awaitMain { f.player.playWhenReady == playing && f.player.isPlaying == playing }
            if (playing) f.awaitMain { f.player.currentPosition > 1234L }
            action(f)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { player?.release() }
            db.close()
            file.delete()
            context.deleteSharedPreferences(preferencesName)
        }
    }
}

package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real queue edit lease, metadata refresh, native teardown/restoration and statistics observer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueListeningEditTest {
    @Test fun currentTrackEditKeepsPartialListening() = fixture {
        advance(20_000)
        edit("one")
        advance(9_999); assertEquals(0, plays.size)
        advance(1); assertEquals(1, plays.size)
    }

    @Test fun upcomingTrackEditKeepsCurrentTraversalAndItsPlaylistOrigin() = fixture {
        advance(20_000)
        edit("two")
        advance(10_000)
        assertEquals(1, plays.size)
        assertEquals("one", plays.single().trackId)
        assertEquals("playlist-one", plays.single().playlistId)
    }

    @Test fun alreadyQualifiedTraversalDoesNotCountAgainAfterCurrentOrUpcomingEdit() = fixture {
        advance(30_000)
        assertEquals(1, plays.size)
        edit("one"); advance(30_000)
        edit("two"); advance(30_000)
        assertEquals(1, plays.size)
    }

    @Test fun selectingTheSameOccurrenceDuringAnEditStartsANewTraversal() = fixture {
        advance(20_000)
        val before = queues.state.value.library.active!!.journey!!.current!!.token
        val entry = queues.state.value.library.active!!.currentEntryId!!
        queues.withFileEdit("one") {
            queues.selectEntry(entry)
            awaitMain { queues.state.value.library.active!!.journey!!.current!!.token != before }
            withContext(Dispatchers.Main) { time += 100_000 }
        }
        awaitMain { player.isPlaying }
        advance(10_000); assertEquals(0, plays.size)
        advance(20_000); assertEquals(1, plays.size)
    }

    @Test fun cancelledWriterRestoresPartialListeningWithoutCountingTheHold() = fixture {
        advance(20_000)
        val entered = CompletableDeferred<Unit>()
        val writer = launch {
            queues.withFileEdit("one") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        withContext(Dispatchers.Main) { time += 100_000 }
        writer.cancelAndJoin()
        awaitMain { player.isPlaying }
        advance(10_000)
        assertEquals(1, plays.size)
    }

    @Test fun clearingBetweenServiceAttachmentsStartsFreshWhenTheRetainedQueueReopens() = fixture {
        advance(30_000)
        val original = queues.state.value.library.active!!
        val detached = withContext(Dispatchers.Main) {
            observer.close()
            queues.detach(player).also { player.release() }
        }
        detached.await()
        queues.clearPlayback()
        awaitMain { queues.state.value.library.activeId == null }
        queues.settings(QueueSettings(switching = QueueSwitch.Resume))
        withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
            observer = PlaybackStatistics(player, session)
            queues.attach(player)
        }
        queues.switchTo(original.id)
        awaitMain { player.isPlaying }
        assertEquals(original.journey!!.current!!.token, queues.state.value.library.active!!.journey!!.current!!.token)
        advance(30_000)
        assertEquals(2, plays.size)
    }

    private fun fixture(test: suspend Fixture.() -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "queue-listening-edit"
        context.deleteSharedPreferences(name)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val files = listOf("one", "two").map { id ->
            File(context.cacheDir, "listening-$id.wav").also { writeWave(it) }
        }
        val songs = files.mapIndexed { i, file -> Track(Uri.fromFile(file).toString(), "Before edit",
            fileName = file.name, relativePath = file.name, id = if (i == 0) "one" else "two") }
        val isolated = preparedTestContext(object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        })
        var fixture: Fixture? = null
        try {
            val active = withContext(Dispatchers.Main) {
                val f = Fixture(this@runBlocking)
                val session = ListeningSession({ f.time }, record = { source, _ -> f.plays.add(source) })
                f.session = session
                f.player = ExoPlayer.Builder(context).build()
                f.observer = PlaybackStatistics(f.player, session)
                f.queues = QueueController(db.queues(), QueuePreferences(isolated),
                    (context.applicationContext as LocalicaApplication).library, actor, listening = session)
                f.songs = songs
                f.queues.attach(f.player)
                f
            }
            fixture = active
            active.awaitMain { !active.queues.state.value.loading }
            active.queues.tap(songs, 0, playlistOrigins = listOf("playlist-one", "playlist-two"))
            active.awaitMain { active.player.isPlaying }
            withTimeout(20_000) { active.test() }
        } finally {
            fixture?.let { f -> withContext(Dispatchers.Main) {
                f.observer.close(); f.queues.detach(f.player); f.player.release()
            } }
            actor.cancel(); db.close()
            files.forEach { it.delete() }
            context.deleteSharedPreferences(name)
        }
    }

    private class Fixture(scope: CoroutineScope) : CoroutineScope by scope {
        var time = 0L
        val plays = mutableListOf<ListeningSource>()
        lateinit var player: ExoPlayer
        lateinit var observer: PlaybackStatistics
        lateinit var session: ListeningSession
        lateinit var queues: QueueController
        lateinit var songs: List<Track>
        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
        suspend fun advance(milliseconds: Long) = withContext(Dispatchers.Main) {
            assertTrue(player.isPlaying)
            time += milliseconds
            observer.onIsPlayingChanged(player.isPlaying)
        }
        suspend fun edit(trackId: String) {
            val visit = queues.state.value.library.active!!.journey!!.current!!.token
            queues.withFileEdit(trackId) {
                withContext(Dispatchers.Main) {
                    assertNull(player.currentMediaItem)
                    time += 100_000
                }
                val saved = CompletableDeferred<Boolean>()
                queues.refreshTrack(songs.first { it.id == trackId }.copy(title = "Edited title")) { saved.complete(it) }
                assertTrue(saved.await())
            }
            awaitMain { player.isPlaying }
            assertEquals(visit, queues.state.value.library.active!!.journey!!.current!!.token)
        }
    }

    private fun writeWave(file: File) {
        val samples = 180 * 8000
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        file.writeBytes(wav.array())
    }
}

package io.github.anastaci1a.localica

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.queueMediaItem
import io.github.anastaci1a.localica.statistics.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real Media3 listener ordering and teardown; only the listening wall clock is controlled. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackStatisticsLifetimeTest {
    @Test fun nativeEditTeardownPreservesPartialTimeAndExcludesThePause() = fixture {
        advance(20_000)
        edit()
        advance(9_999)
        assertEquals(0, plays.size)
        advance(1)
        assertEquals(1, plays.size)
    }

    @Test fun qualifiedPlaySurvivesNativeEditAndObserverReplacement() = fixture {
        advance(30_000)
        assertEquals(1, plays.size)
        edit(recreate = true)
        advance(30_000)
        assertEquals(1, plays.size)
        // Explicit restart at zero creates another qualification, even for the same queue visit.
        main { player.seekTo(2000) }
        await { player.currentPosition >= 2000 }
        main { player.seekTo(0) }
        advance(30_000)
        assertEquals(2, plays.size)
    }

    @Test fun serviceReplacementDuringEditRetainsUnqualifiedPartialTime() = fixture {
        advance(20_000)
        edit(recreate = true)
        advance(10_000)
        assertEquals(1, plays.size)
    }

    private fun fixture(test: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.start(); fixture.test() } finally { fixture.close() }
    }

    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val file = File(context.cacheDir, "listening-lifetime.wav")
        private var time = 0L
        val plays = mutableListOf<Pair<ListeningSource, String>>()
        private val session = ListeningSession({ time }, record = { source, id -> plays.add(source to id) })
        lateinit var player: ExoPlayer
        private lateinit var observer: PlaybackStatistics
        private lateinit var item: MediaItem
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            var done = false
            while (!done && System.currentTimeMillis() < deadline) {
                main { done = condition() }
                if (!done) Thread.sleep(10)
            }
            assertTrue("Native playback did not become ready", done)
        }
        fun start() {
            val samples = 180 * 8000
            val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            file.writeBytes(wav.array())
            main {
                val original = queueMediaItem("entry", Track(Uri.fromFile(file).toString(), "Before edit",
                    fileName = file.name, relativePath = file.name, id = "track"), "playlist")
                item = original.buildUpon().setMediaMetadata(original.mediaMetadata.buildUpon()
                    .setExtras(Bundle(original.mediaMetadata.extras).apply { putString("queueVisit", "visit") }).build()).build()
                createPlayer()
                player.setMediaItem(item); player.prepare(); player.play()
            }
            await { player.isPlaying }
        }
        private fun createPlayer() {
            player = ExoPlayer.Builder(context).build()
            observer = PlaybackStatistics(player, session)
        }
        fun advance(milliseconds: Long) = main {
            assertTrue(player.isPlaying)
            time += milliseconds
            observer.onIsPlayingChanged(player.isPlaying)
        }
        fun edit(recreate: Boolean = false) {
            var position = 0L
            main {
                position = player.currentPosition
                session.suspendForEdit()
                player.pause(); player.stop(); player.clearMediaItems()
            }
            await { player.currentMediaItem == null }
            main {
                if (recreate) { observer.close(); player.release(); createPlayer() }
                time += 100_000
                observer.onIsPlayingChanged(false)
                item = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle("After edit").build()).build()
                player.setMediaItem(item, position); player.prepare(); player.play()
                session.finishEdit("visit")
            }
            await { player.isPlaying }
        }
        fun close() {
            main { if (::observer.isInitialized) observer.close(); if (::player.isInitialized) player.release() }
            file.delete()
        }
    }
}

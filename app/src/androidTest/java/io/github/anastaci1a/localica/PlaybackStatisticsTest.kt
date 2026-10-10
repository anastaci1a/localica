package io.github.anastaci1a.localica

import android.net.Uri
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
import java.util.concurrent.CopyOnWriteArrayList

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackStatisticsTest {
    @Test fun realPlayerCountsRepeatTraversalsAndKeepsPlaylistOriginWhilePausedSeeksDoNotCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "statistics-tone.wav")
        val samples = 4 * 44100
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { wav.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 44100) * 1000).toInt().toShort()) }
        file.writeBytes(wav.array())
        val plays = CopyOnWriteArrayList<ListeningSource>()
        var cleanup: (() -> Unit)? = null
        lateinit var player: ExoPlayer
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                val observer = PlaybackStatistics(player, plays::add)
                cleanup = { observer.close(); player.release() }
                player.setMediaItem(queueMediaItem("entry", Track(Uri.fromFile(file).toString(), "Statistics",
                    fileName = file.name, relativePath = file.name, id = "track"), "playlist"))
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.prepare()
            }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = player.playbackState == Player.STATE_READY }
                ready
            }
            instrumentation.runOnMainSync { player.seekTo(3500) }
            Thread.sleep(2200)
            assertTrue(plays.isEmpty())
            instrumentation.runOnMainSync { player.seekTo(0); player.play() }
            await { plays.size >= 2 }
            instrumentation.runOnMainSync { player.pause() }
            val count = plays.size
            Thread.sleep(2200)
            assertEquals(count, plays.size)
            assertTrue(plays.all { it == ListeningSource("entry", "track", "playlist") })
        } finally {
            instrumentation.runOnMainSync { cleanup?.invoke() }
            file.delete()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Player/statistics did not reach expected state", condition())
    }
}

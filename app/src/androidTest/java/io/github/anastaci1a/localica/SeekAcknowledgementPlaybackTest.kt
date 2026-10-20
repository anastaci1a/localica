package io.github.anastaci1a.localica

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.queue.QueueSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercise the actual in-process UI command -> actor -> native player -> atomic connection receipt path. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SeekAcknowledgementPlaybackTest {
    @Test fun nativeEndpointMaskUsesTheLastMicrosecondOfThePeriod() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.cacheDir, "seek-endpoint-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> audio.outputStream().use(input::copyTo) }
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(audio.toURI().toString()))
                    prepare()
                }
            }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = player!!.playbackState == Player.STATE_READY }
                ready
            }
            instrumentation.runOnMainSync {
                val native = player!!
                val timeline = native.currentTimeline
                val window = timeline.getWindow(native.currentMediaItemIndex, Timeline.Window())
                val period = timeline.getPeriod(native.currentPeriodIndex, Timeline.Period())
                assertEquals("Fixture must contain one period", window.firstPeriodIndex, window.lastPeriodIndex)
                assertEquals(0L, period.positionInWindowUs)
                assertTrue(period.durationUs > 0)
                val durationMs = native.duration
                // Media3 1.10.1 Timeline.getPeriodPositionUs clamps below durationUs;
                // ExoPlayer's synchronous seek mask then truncates microseconds to milliseconds.
                val expectedMs = minOf(durationMs * 1_000, period.durationUs - 1) / 1_000
                native.seekTo(durationMs)
                assertEquals("Native endpoint: durationMs=$durationMs, periodUs=${period.durationUs}",
                    expectedMs, native.currentPosition)
                assertTrue(durationMs - native.currentPosition in 0L..1L)
                assertFalse(native.playWhenReady)
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            audio.delete()
        }
    }

    @Test fun queuedSeeksPublishReceiptAndPositionTogetherAndDoNotLeakAcrossDuplicateVisits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.cacheDir, "seek-ack-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> audio.outputStream().use(input::copyTo) }
        val track = Track(audio.toURI().toString(), "Seek acknowledgement", fileName = audio.name,
            relativePath = audio.name, id = "seek-ack-track")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            lateinit var playback: PlaybackConnection
            instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
            await { playback.state.value.connected && !playback.queues.state.value.loading }
            val savedSettings = playback.queues.state.value.settings
            try {
                instrumentation.runOnMainSync { playback.clear(); playback.queues.settings(QueueSettings()) }
                await { playback.state.value.trackUri == null }
                instrumentation.runOnMainSync { playback.queues.tap(listOf(track, track), 0) }
                await { playback.state.value.trackId == track.id && playback.state.value.isPlaying }
                instrumentation.runOnMainSync { playback.toggle() }
                await { !playback.state.value.playWhenReady && !playback.state.value.isPlaying && !playback.state.value.isBuffering }
                val visit = playback.state.value.playbackVisit
                assertNotNull("Native visit must be published, including duplicate-file generations", visit)
                var revision = playback.state.value.seekAcknowledgement?.requestId ?: 0L
                for (target in listOf(1_500L, 500L)) {
                    instrumentation.runOnMainSync { playback.seekTo(target) }
                    await { playback.state.value.seekAcknowledgement?.let { it.requestId > revision && it.positionMs == target } == true }
                    val sample = playback.state.value
                    assertEquals("Receipt destination and playback are one paused publication", target, sample.positionMs)
                    assertEquals(visit, sample.seekAcknowledgement!!.playbackVisit)
                    assertEquals(visit, sample.playbackVisit)
                    assertFalse(sample.playWhenReady)
                    revision = sample.seekAcknowledgement.requestId
                }
                // ABA destinations remain distinct even if the remote event batch coalesces.
                var lastRequest = 0L
                instrumentation.runOnMainSync {
                    playback.requestSeek(2_000, visit)
                    playback.requestSeek(750, visit)
                    lastRequest = playback.requestSeek(2_000, visit)
                }
                await { playback.state.value.seekAcknowledgement?.requestId == lastRequest }
                assertEquals(2_000L, playback.state.value.positionMs)
                instrumentation.runOnMainSync { playback.toggle() }
                await { playback.state.value.isPlaying && playback.state.value.positionMs > 2_250 }
                assertEquals("Applied receipt must not freeze natural progress", lastRequest,
                    playback.state.value.seekAcknowledgement!!.requestId)
                instrumentation.runOnMainSync { playback.toggle() }
                await { !playback.state.value.playWhenReady && !playback.state.value.isPlaying }
                val beforeClamp = playback.state.value.seekAcknowledgement!!.requestId
                instrumentation.runOnMainSync { playback.requestSeek(Long.MAX_VALUE, visit) }
                await { playback.state.value.seekAcknowledgement?.requestId?.let { it > beforeClamp } == true }
                val endpoint = playback.state.value
                val receipt = endpoint.seekAcknowledgement!!
                assertTrue(receipt.applied)
                assertEquals(visit, receipt.playbackVisit)
                assertEquals(visit, endpoint.playbackVisit)
                assertEquals("Clamped receipt and position must be one publication", receipt.positionMs, endpoint.positionMs)
                // Media3 clamps to durationUs - 1, then floors to milliseconds. Depending
                // on the period's microsecond remainder, that is durationMs or durationMs - 1.
                assertTrue("Native endpoint must remain within its final millisecond: $endpoint",
                    endpoint.durationMs!! - endpoint.positionMs in 0L..1L)
                assertFalse(endpoint.playWhenReady)
                instrumentation.runOnMainSync { playback.next() }
                await { playback.state.value.playbackVisit != visit && playback.state.value.trackId == track.id }
                val next = playback.state.value
                assertTrue("Old receipt cannot accompany a new visit",
                    next.seekAcknowledgement == null || next.seekAcknowledgement.playbackVisit == next.playbackVisit)
                val rejected = java.util.concurrent.atomic.AtomicReference<Boolean?>()
                instrumentation.runOnMainSync {
                    playback.queues.requestSeek(2_000, visit) { applied, _ -> rejected.set(!applied) }
                }
                await { rejected.get() != null }
                assertTrue("A command for the previous same-file visit must be rejected", rejected.get() == true)
            } finally {
                instrumentation.runOnMainSync { playback.queues.settings(savedSettings); playback.clear(); playback.close() }
                audio.delete()
            }
        }
    }
}

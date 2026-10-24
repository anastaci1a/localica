package io.github.anastaci1a.localica

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.playback.PlaybackExtractors
import io.github.anastaci1a.localica.playback.PlaybackRenderersFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real progressive loading: RESULT_SEEK closes/reopens the DataSource, unlike a byte-array input. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDataSourceTest {
    @Test fun unknownLengthDoesNotAddReopensComparedWithNative() {
        val native = play(DefaultExtractorsFactory(), allowReopen = true)
        val wrapped = play(PlaybackExtractors(), allowReopen = true)
        assertEquals(1, native.opens.get())
        assertEquals(native.opens.get(), wrapped.opens.get())
        assertEquals(native.closes.get(), wrapped.closes.get())
    }

    @Test fun unknownLengthNonReopenableSourcePlaysToEnd() {
        val native = play(DefaultExtractorsFactory(), allowReopen = false)
        val wrapped = play(PlaybackExtractors(), allowReopen = false)
        assertEquals(1, native.opens.get())
        assertEquals(1, wrapped.opens.get())
        assertEquals(1, wrapped.closes.get())
    }

    @Test fun roundedEditBindingDoesNotReopenUnknownLengthSingleUseSource() {
        val source = play(PlaybackExtractors(decodedEditBinding = true), allowReopen = false)
        assertEquals(1, source.opens.get())
        assertEquals(1, source.closes.get())
    }

    @Test fun roundedFastStartValidationAddsNoSourceReopen() {
        val unbound = play(PlaybackExtractors(), allowReopen = true, knownLength = true, fixture = "boundary-rounded-streamable.m4a")
        val bound = play(PlaybackExtractors(decodedEditBinding = true), allowReopen = true, knownLength = true, fixture = "boundary-rounded-streamable.m4a")
        assertEquals(unbound.opens.get(), bound.opens.get())
        assertEquals(unbound.closes.get(), bound.closes.get())
    }

    private fun play(extractors: ExtractorsFactory, allowReopen: Boolean, knownLength: Boolean = false,
        fixture: String = "boundary-streamable.m4a"): ProbeSource {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open(fixture).use { it.readBytes() }
        val source = ProbeSource(bytes, allowReopen, knownLength)
        val ended = AtomicBoolean()
        val failure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext, PlaybackRenderersFactory(instrumentation.targetContext)).build()
            player.volume = 0f
            // No foreground Activity/service: retain default disabled focus handling.
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.set(true) }
                override fun onPlayerError(error: PlaybackException) { failure.set(error) }
            })
            val media = ProgressiveMediaSource.Factory(DataSource.Factory { source }, extractors)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                .createMediaSource(MediaItem.fromUri("memory:///boundary-streamable.m4a"))
            player.setMediaSource(media)
            player.prepare()
            player.play()
        }
        try {
            await(15_000) {
                failure.get()?.let { throw AssertionError("Unknown-length progressive source failed", it) }
                ended.get()
            }
            assertTrue("Real media bytes must be read", source.bytesRead.get() >= bytes.size)
        } finally {
            instrumentation.runOnMainSync { player.release() }
            await { source.closes.get() == source.opens.get() }
            Log.i("QueueDataSource", "factory=${extractors.javaClass.simpleName} allowReopen=$allowReopen " +
                "opens=${source.opens.get()} closes=${source.closes.get()} bytes=${source.bytesRead.get()} " +
                "ended=${ended.get()} error=${failure.get()}")
        }
        return source
    }

    private class ProbeSource(bytes: ByteArray, private val allowReopen: Boolean, private val knownLength: Boolean,
        private val delegate: ByteArrayDataSource = ByteArrayDataSource(bytes)) : DataSource by delegate {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val bytesRead = AtomicInteger()
        override fun open(dataSpec: DataSpec): Long {
            val count = opens.incrementAndGet()
            if (!allowReopen && count > 1) throw IOException("Source cannot be reopened")
            val length = delegate.open(dataSpec)
            return if (knownLength) length else -1L // Unknown source length, including when every byte is still readable.
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { if (it > 0) bytesRead.addAndGet(it) }
        override fun close() { closes.incrementAndGet(); delegate.close() }
    }
}

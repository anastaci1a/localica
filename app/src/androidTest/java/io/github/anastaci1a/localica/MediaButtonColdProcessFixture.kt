package io.github.anastaci1a.localica

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #828 host-driven seed: persist one queue and let the service register its media session (so the system records the manifest
 * MEDIA_BUTTON receiver), paused. The host then force-stops the package, sends KEYCODE_MEDIA_PLAY and watches a NEW process
 * restore from queues.db. Skipped unless the host passes mediaButtonColdProcessPhase=seed.
 */
class MediaButtonColdProcessFixture {
    @Test fun seedPausedQueueWithARegisteredSession(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("mediaButtonColdProcessPhase")
        assumeTrue("Requires the adb cold-process driver", phase == "seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as LocalicaApplication
        val file = File(context.filesDir, "media-button-828.wav").also { writeWav(it) }
        val track = Track(file.toURI().toString(), "Media button 828", durationMs = 600_000, fileName = file.name, relativePath = file.name)
        withContext(Dispatchers.Main) { app.queues.tap(listOf(track), 0) }
        val controller = withContext(Dispatchers.Main) {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        withTimeout(20_000) { while (!controller.isDone) delay(20) }
        withTimeout(20_000) { while (withContext(Dispatchers.Main) { app.queues.playbackSample() } == null) delay(20) }
        withContext(Dispatchers.Main) { app.queues.playbackRequested(false) }
        delay(3_000) // Structural saves await durability; this also lets the cursor checkpoint land before the host kills us.
    }

    private fun writeWav(file: File) {
        val rate = 8_000
        val samples = ShortArray(rate * 600)
        val pcm = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach(pcm::putShort)
        file.writeBytes(pcm.array())
    }
}

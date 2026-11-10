package io.github.anastaci1a.localica

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #828: a headset or Bluetooth play key (Samsung Sound Assistant's "Favourite media app") reaches the manifest's MEDIA_BUTTON
 * receiver while no PlaybackService exists, and playback starts with no Activity. Instrumentation shares the app process and
 * LocalicaApplication.queues is already in memory, so this proves a warm-process SERVICE cold start, NOT a disk restore. The
 * cold-process proof (force-stop, input keyevent, restore from queues.db) is MediaButtonColdProcessFixture driven by adb (#828).
 */
@RunWith(AndroidJUnit4::class)
class MediaButtonResumptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val receiver = ComponentName(context, "androidx.media3.session.MediaButtonReceiver")

    @Test fun manifestResolvesTheMediaButtonReceiver() {
        val resolved = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName), 0)
        assertTrue(resolved.any { it.activityInfo.name == receiver.className && it.activityInfo.exported })
    }

    @Test fun playKeyColdStartsPlaybackOfTheRestoredQueue(): Unit = runBlocking {
        val app = context.applicationContext as LocalicaApplication
        val file = File(context.cacheDir, "media-button-828.wav").also { writeWav(it) }
        val track = Track(file.toURI().toString(), "Media button", durationMs = 60_000, fileName = file.name, relativePath = file.name)
        context.stopService(Intent(context, PlaybackService::class.java))
        withContext(Dispatchers.Main) { app.queues.tap(listOf(track), 0) }
        withTimeout(10_000) { while (app.queues.state.value.library.active == null) delay(20) }
        delay(1_500) // Let the checkpoint settle; the service here still re-attaches the in-memory queue.
        withContext(Dispatchers.Main) { app.queues.playbackRequested(false) }
        context.stopService(Intent(context, PlaybackService::class.java))
        withTimeout(10_000) { while (sample(app) != null) delay(20) }
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)))
        }
        val start = withTimeout(20_000) { var sample = sample(app); while (sample == null) { delay(20); sample = sample(app) }; sample }
        val advanced = withTimeout(20_000) { var latest = sample(app); while ((latest?.positionMs ?: 0) < start.positionMs + 750) { delay(50); latest = sample(app) }; latest!! }
        assertEquals("The restored queue's visit is the one playing", start.playbackVisit, advanced.playbackVisit)
        withContext(Dispatchers.Main) { app.queues.playbackRequested(false) }
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    private suspend fun sample(app: LocalicaApplication) = withContext(Dispatchers.Main) { app.queues.playbackSample() }

    private fun writeWav(file: File) {
        val rate = 8_000
        val samples = ShortArray(rate * 60)
        val pcm = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach(pcm::putShort)
        file.writeBytes(pcm.array())
    }
}

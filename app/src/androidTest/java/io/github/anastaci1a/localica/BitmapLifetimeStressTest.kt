package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.RectangleShape
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.decodeArtwork
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** #501: Android 8 died freeing a Bitmap's heap pixels on ReferenceQueueDaemon (corrupted
 * jemalloc metadata). Hammer the API < 31 bitmap owners together for a bounded time: interrupted
 * ArtworkBlend crossfades (frozen layer readback, then CPU blur on Default, cancelled by the next
 * change), cancelled off-main blurs of fresh copies, parallel decodes, and frequent GC so the
 * native cleaners run while those owners are active. Any heap corruption aborts the process. */
@OptIn(ExperimentalTestApi::class)
class BitmapLifetimeStressTest {
    // The API 26 runner disables system animations. This fixture must exercise
    // an interrupted animation even when run alone after the selected suite.
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    private fun artwork(random: Random, size: Int = 96 + random.nextInt(96)): Bitmap {
        val colors = IntArray(size * size)
        val a = random.nextInt(); val b = random.nextInt()
        for (i in colors.indices) {
            val alpha = if (random.nextInt(4) == 0) 0x60 + random.nextInt(0x9f) else 0xff
            colors[i] = (alpha shl 24) or ((if ((i / size + i % size) % 7 < 3) a else b) and 0xffffff)
        }
        return Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun visual(random: Random): PlayerVisual {
        val cover = artwork(random)
        val soft = artwork(random, cover.width / 2)
        return PlayerVisual(cover.asImageBitmap(), softArtwork = soft.asImageBitmap(), contentKey = "stress-${random.nextLong()}")
    }

    @Test fun interruptedArtworkBlendsWithConcurrentBlurDecodeAndGcStayAlive() {
        val random = Random(501)
        var shown by mutableStateOf(visual(random))
        val frozenFrames = AtomicInteger()
        val admitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        compose.setContent {
            ArtworkBlend(shown, Modifier.size(220.dp), duration = 1000,
                onBackgroundFrame = { if (it.frozenVisible) frozenFrames.incrementAndGet() }) { frame ->
                SideEffect { frame.contentKey?.let { admitted.add(it) } }
                PlayerArtworkFrame(frame, Modifier.size(220.dp), RectangleShape, ContentScale.Crop)
            }
        }
        compose.waitForIdle()
        val originalAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        val encoded = ByteArrayOutputStream().also { artwork(random, 320).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val blurs = AtomicInteger(); val cancelledBlurs = AtomicInteger(); val decodes = AtomicInteger()
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repeat(2) { worker ->
            background.launch {
                val local = Random(worker)
                while (isActive) {
                    val source = artwork(local)
                    val blurred = withTimeoutOrNull(local.nextLong(1, 12)) { blurGlassSnapshot(source.asImageBitmap(), 6f + local.nextInt(30)) }
                    if (blurred == null) cancelledBlurs.incrementAndGet() else blurs.incrementAndGet()
                    readGlassPixels(source.asImageBitmap(), 1 + local.nextInt(4))
                    // Cancellation checks do not suspend. Leave Default workers available
                    // for the interrupted blend's own blur between stress iterations.
                    yield()
                }
            }
        }
        background.launch(Dispatchers.IO) {
            while (isActive) {
                decodeArtwork(encoded, 64 + Random.nextInt(256))?.also { if (Build.VERSION.SDK_INT >= 28) it.prepareToDraw() }
                decodes.incrementAndGet()
                yield()
            }
        }
        val deadline = SystemClock.uptimeMillis() + 40_000
        var changes = 0
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                val next = visual(random)
                compose.runOnUiThread { shown = next }
                // Await admission after readback/CPU preparation, without letting an entire
                // transition elapse. Constant supersession can otherwise cancel every
                // blur before a frozen frame is ever published.
                compose.waitUntil(2000) {
                    compose.mainClock.advanceTimeByFrame()
                    next.contentKey in admitted
                }
                // Interrupt the admitted animation after a partial real draw.
                compose.mainClock.advanceTimeBy(16L + random.nextLong(140))
                SystemClock.sleep(16L + random.nextLong(40))
                if (++changes % 8 == 0) { Runtime.getRuntime().gc(); System.runFinalization() }
            }
        } finally {
            try { runBlocking { background.coroutineContext[Job]!!.cancelAndJoin() } }
            finally { compose.mainClock.autoAdvance = originalAutoAdvance }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(100) }
        val settle = SystemClock.uptimeMillis() + 5_000
        while (ReadbackCompletion.activeProcessReadCount() > 0 && SystemClock.uptimeMillis() < settle) SystemClock.sleep(50)
        val summary = "changes=$changes frozen=${frozenFrames.get()} blurs=${blurs.get()} cancelledBlurs=${cancelledBlurs.get()} decodes=${decodes.get()}"
        assertEquals("No layer readback remains outstanding: $summary", 0, ReadbackCompletion.activeProcessReadCount())
        assertTrue("Interruptions reached the frozen readback path: $summary", frozenFrames.get() > 0)
        assertTrue("Concurrent blur and decode work ran: $summary", blurs.get() + cancelledBlurs.get() > 0 && decodes.get() > 0)
    }
}

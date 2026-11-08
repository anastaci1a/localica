package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.pow

/**
 * #576: the full player's blurred backdrop is graded only as much as the chosen ink needs, lightness moves only
 * toward contrast, and chroma is kept. Each cover is rendered through the real player in three contexts (player,
 * lyrics open, Queue-driven) and written as a PNG to the additional test output directory. This class uses only
 * APIs that exist before the change, so the same file produces the "before" evidence on the base commit.
 */
class PlayerBackdropGradingTest {
    @get:Rule val compose = createComposeRule()

    private class Cover(val name: String, val base: Int, val accent: Int? = null)
    private val covers = listOf(
        Cover("pink", 0xD8B4BD, 0xE8604A), Cover("saturated-dark", 0x3A0CA3), Cover("saturated-light", 0xFFE600),
        Cover("mid-grey", 0x808080), Cover("near-white", 0xF6F5F3), Cover("near-black", 0x0B0B0D))

    private fun bitmap(cover: Cover) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFF000000.toInt() or cover.base)
        cover.accent?.let { accent -> for (y in 40 until 96) for (x in 44 until 96) setPixel(x, y, 0xFF000000.toInt() or accent) }
    }

    private fun linear(x: Float) = if (x <= .04045f) x / 12.92f else ((x + .055f) / 1.055f).pow(2.4f)
    private fun luminance(r: Float, g: Float, b: Float) = .2126f * linear(r) + .7152f * linear(g) + .0722f * linear(b)
    private fun oklab(r: Float, g: Float, b: Float): FloatArray {
        val lr = linear(r); val lg = linear(g); val lb = linear(b)
        val l = Math.cbrt((.4122214708 * lr + .5363325363 * lg + .0514459929 * lb)).toFloat()
        val m = Math.cbrt((.2119034982 * lr + .6806995451 * lg + .1073969566 * lb)).toFloat()
        val s = Math.cbrt((.0883024619 * lr + .2817188376 * lg + .6299787005 * lb)).toFloat()
        return floatArrayOf(.2104542553f * l + .793617785f * m - .0040720468f * s,
            1.9779984951f * l - 2.428592205f * m + .4505937099f * s, .0259040371f * l + .7827717662f * m - .808675766f * s)
    }

    @Test fun backdropIsGradedMinimallyForTheChosenInkInEveryContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val output = File(root, "backdrop-grading"); assertTrue(output.isDirectory || output.mkdirs())
        val failures = mutableListOf<String>()
        var cover by mutableStateOf(covers[0])
        var context by mutableStateOf("player")
        val polarity = PlayerBackgroundPolarityState()
        val visuals = covers.associate { c -> c.name to bitmap(c).let { PlayerVisual(it.asImageBitmap(), runBlocking { playerPalette(it) }) } }
        compose.setContent { LocalicaTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val progress = remember { Animatable(1f) }
                val queue = remember { Animatable(0f) }
                LaunchedEffect(context) { queue.snapTo(if (context == "queue") 1f else 0f) }
                PlayerSurface(PlaybackState(title = "Now and then"), true, {}, InterfaceSettings(reducedMotion = true), progress,
                    {}, {}, {}, {}, {}, {}, lyricsOpen = context == "lyrics", queueProgress = queue,
                    visual = visuals.getValue(cover.name), backgroundPolarity = polarity) {}
            }
        } }
        for (c in covers) for (ctx in listOf("player", "lyrics", "queue")) {
            // #678 instrumentation (log only): which iterations hit the 8 s cap, and what the sampler state is.
            val tagLog = "${c.name}/$ctx"
            fun state(): String {
                val frame = polarity.frame; val d = polarity.decision
                return "conf=${d.confidence} unavailable=${d.unavailable} light=${d.lightInk} dest=${polarity.destinationLightInk} " +
                    "clock=${polarity.artworkClock} transitioning=${frame?.transitioning} frozen=${frame?.frozenVisible} liveBlur=${frame?.liveBlur} " +
                    "roles=${frame?.roles?.map { "${if (it.visual === visuals.getValue(c.name)) "same" else "other"}/${if (it.soft) "soft" else "main"}/${if (it.incoming) "in" else "out"}/${it.weight}" }} " +
                    "grades=${polarity.roleGrades.size}"
            }
            var last = ""
            val started = System.nanoTime()
            fun observe(phase: String) {
                val now = state()
                if (now != last) { android.util.Log.i("Trim678", "$tagLog $phase +${(System.nanoTime() - started) / 1_000_000} ms $now"); last = now }
            }
            compose.runOnIdle { cover = c; context = ctx }
            val raw = Color(0xFF000000.toInt() or c.base)
            var waited = false
            try {
                compose.waitUntil(8000) {
                    observe("wait")
                    val frame = polarity.frame
                    polarity.decision.confidence == PlayerBackgroundConfidence.Current && frame != null &&
                        frame.roles.isNotEmpty() && frame.roles.all { it.visual === visuals.getValue(c.name) }
                }
                waited = true
            } catch (_: Throwable) { /* a context whose sampler is unavailable is still photographed */ }
            android.util.Log.i("Trim678", "$tagLog waitUntil ${if (waited) "satisfied" else "TIMED OUT"} after ${(System.nanoTime() - started) / 1_000_000} ms")
            val sleepUntil = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < sleepUntil) { Thread.sleep(50); observe("sleep") }
            compose.waitForIdle(); observe("idle")
            val image = compose.onRoot().captureToImage()
            File(output, "${c.name}-$ctx-api${Build.VERSION.SDK_INT}.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            // The left gutter beside the title and controls: backdrop only, no text or cover.
            val pixels = image.toPixelMap()
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for (y in (pixels.height * .55f).toInt() until (pixels.height * .8f).toInt() step 4)
                for (x in (pixels.width * .01f).toInt() until (pixels.width * .045f).toInt()) {
                    val p = pixels[x, y]; r += p.red; g += p.green; b += p.blue; n++
                }
            r /= n; g /= n; b /= n
            val light = polarity.decision.lightInk
            val y = luminance(r, g, b)
            val contrast = if (light) 1.05f / (y + .05f) else (y + .05f) / .05f
            val before = oklab(raw.red, raw.green, raw.blue); val after = oklab(r, g, b)
            val tag = "${c.name}/$ctx lightInk=$light"
            android.util.Log.i("Trim678", "$tagLog measured +${(System.nanoTime() - started) / 1_000_000} ms n=$n rgb=$r,$g,$b light=$light y=$y contrast=$contrast " +
                "L=${before[0]}->${after[0]} chroma=${hypot(before[1], before[2])}->${hypot(after[1], after[2])}")
            // Lightness never moves away from contrast: no darkening behind dark ink, no lightening behind light ink.
            if (light && after[0] > before[0] + .06f) failures += "$tag lightened behind light ink: ${before[0]} -> ${after[0]}"
            if (!light && after[0] < before[0] - .06f) failures += "$tag darkened behind dark ink: ${before[0]} -> ${after[0]}"
            if (contrast < 4.2f) failures += "$tag contrast %.2f".format(contrast)
            if (c.name == "pink" && hypot(after[1], after[2]) < .7f * hypot(before[1], before[2]))
                failures += "$tag chroma ${hypot(before[1], before[2])} -> ${hypot(after[1], after[2])}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

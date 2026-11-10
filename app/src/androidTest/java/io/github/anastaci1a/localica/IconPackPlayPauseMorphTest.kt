package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** #662: imported play/pause icons morph like the built-in pair instead of cross-fading. */
class IconPackPlayPauseMorphTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() { IconPacks.reset(context) }

    private fun svg(vararg paths: String) =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">${paths.joinToString("") { "<path d=\"$it\"/>" }}</svg>""".toByteArray()
    private val triangle = svg("M3 5 L22 12 L3 19 Z")
    private val twoBars = svg("M5 3 H7 A1 1 0 0 1 8 4 V20 A1 1 0 0 1 7 21 H5 A1 1 0 0 1 4 20 V4 A1 1 0 0 1 5 3 Z",
        "M17 3 H19 A1 1 0 0 1 20 4 V20 A1 1 0 0 1 19 21 H17 A1 1 0 0 1 16 20 V4 A1 1 0 0 1 17 3 Z")
    private val oneBar = svg("M10 3H14V21H10Z")
    private fun rasterBar(): ByteArray {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(72f, 24f, 120f, 168f, Paint().apply { color = android.graphics.Color.BLACK })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun install(play: Pair<String, ByteArray>? = null, pause: Pair<String, ByteArray>? = null) {
        val source = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("pack.json", """{"name":"Play pause morph fixture","version":"1"}""".toByteArray())
            play?.let { entry("icons/transport.play.${it.first}", it.second) }
            pause?.let { entry("icons/transport.pause.${it.first}", it.second) }
        } }.toByteArray()
        IconPacks.import(context, source.inputStream())
    }

    private var playing by mutableStateOf(false)
    private var reduced by mutableStateOf(false)
    private fun content() {
        playing = false; reduced = false
        compose.setContent { LocalicaTheme {
            Column(Modifier.background(Color.White)) {
                TransportButton(TransportGlyph.PlayPause, playing, "Play", { playing = !playing },
                    Modifier.size(96.dp).background(Color.White).testTag("button"), 72.dp, Color.Black, true, reduced)
                for ((tag, icon) in listOf("reference-play" to AppIcon.TransportPlay, "reference-pause" to AppIcon.TransportPause)) {
                    Box(Modifier.size(96.dp).background(Color.White).testTag(tag), contentAlignment = Alignment.Center) {
                        Icon(appIconPainter(icon), null, Modifier.size(72.dp), tint = Color.Black)
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun frame(tag: String): ImageBitmap = compose.onNodeWithTag(tag).captureNativeFrame()
    private fun exact(expected: ImageBitmap, actual: ImageBitmap, message: String) {
        val e = expected.toPixelMap(); val a = actual.toPixelMap()
        assertEquals(e.width, a.width); assertEquals(e.height, a.height)
        for (y in 0 until e.height) for (x in 0 until e.width)
            assertEquals("$message differs at $x,$y", e[x, y], a[x, y])
    }
    /** Black ink on white: coverage is 1 - red. Partial ink mid-way between the two levels is the cross-fade signature. */
    private fun partialShare(frame: ImageBitmap): Float {
        val p: PixelMap = frame.toPixelMap()
        var ink = 0; var partial = 0
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val coverage = 1f - p[x, y].red
            if (coverage > .1f) ink++
            if (coverage > .25f && coverage < .75f) partial++
        }
        return if (ink == 0) 0f else partial.toFloat() / ink
    }
    private fun samePixels(a: ImageBitmap, b: ImageBitmap) = a.asAndroidBitmap().sameAs(b.asAndroidBitmap())

    /** Frames every 26 ms after the toggle, for local evidence and the assertions below. */
    private fun animate(toPlaying: Boolean): List<ImageBitmap> {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { playing = toPlaying }
        compose.mainClock.advanceTimeByFrame()
        val frames = mutableListOf<ImageBitmap>()
        for (i in 1..10) { compose.mainClock.advanceTimeBy(26); frames += frame("button") }
        compose.mainClock.advanceTimeBy(400)
        frames += frame("button")
        compose.mainClock.autoAdvance = true
        return frames
    }

    private fun saveStrip(name: String, frames: List<ImageBitmap>) {
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))).let { File(it, "pack-morph-662") }.apply { mkdirs() }
        val cell = frames.first().asAndroidBitmap()
        val strip = Bitmap.createBitmap(cell.width * frames.size, cell.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        frames.forEachIndexed { i, f -> canvas.drawBitmap(f.asAndroidBitmap(), (i * cell.width).toFloat(), 0f, null) }
        File(root, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun twoBarVectorPackMorphsWithSolidInkAndExactEndFrames() {
        install("svg" to triangle, "svg" to twoBars)
        content()
        val play = frame("reference-play"); val pause = frame("reference-pause")
        exact(play, frame("button"), "Resting play frame")
        val frames = animate(toPlaying = true)
        saveStrip("two-bars-to-pause", frames)
        frames.dropLast(1).forEachIndexed { i, f ->
            assertTrue("Frame $i has cross-fade ink: share ${partialShare(f)}", partialShare(f) < .15f)
        }
        val distinct = frames.dropLast(1).count { f -> frames.none { it !== f && samePixels(it, f) } }
        assertTrue("The morph moves through distinct frames: $distinct", distinct >= 5)
        assertFalse("The first frame has left the play icon", samePixels(play, frames.first()))
        exact(pause, frames.last(), "Settled pause frame")
        val back = animate(toPlaying = false)
        saveStrip("two-bars-to-play", back)
        back.dropLast(1).forEach { assertTrue(partialShare(it) < .15f) }
        exact(play, back.last(), "Settled play frame after returning")
    }

    @Test fun oneShapePauseAndRasterPausesKeepTheCrossFade() {
        install("svg" to triangle, "svg" to oneBar)
        content()
        val frames = animate(toPlaying = true)
        assertTrue("A one-shape pause still cross-fades", frames.dropLast(1).maxOf(::partialShare) > .2f)
        exact(frame("reference-pause"), frames.last(), "One-shape pause end frame")
        IconPacks.reset(context)
        install("svg" to triangle, "png" to rasterBar())
        compose.runOnIdle { playing = false }
        compose.waitForIdle()
        exact(frame("reference-play"), frame("button"), "Raster pack resting frame")
        val raster = animate(toPlaying = true)
        assertTrue("A raster pause still cross-fades", raster.dropLast(1).maxOf(::partialShare) > .2f)
        exact(frame("reference-pause"), raster.last(), "Raster pause end frame")
    }

    private fun partialPackEndpoints(playOnly: Boolean) {
        if (playOnly) install(play = "svg" to triangle) else install(pause = "svg" to twoBars)
        content()
        val play = frame("reference-play"); val pause = frame("reference-pause")
        exact(play, frame("button"), "Partial pack initial play frame")
        val forward = animate(toPlaying = true)
        saveStrip(if (playOnly) "play-only-to-pause" else "pause-only-to-pause", forward)
        exact(pause, forward.last(), "Partial pack settled pause frame")
        val back = animate(toPlaying = false)
        exact(play, back.last(), "Partial pack settled play frame")
    }

    @Test fun playOnlyPackKeepsExactBuiltinPauseEndpoint() = partialPackEndpoints(true)
    @Test fun pauseOnlyPackKeepsExactBuiltinPlayEndpoint() = partialPackEndpoints(false)

    @Test fun reducedMotionSnapsBetweenTheStaticPackIcons() {
        install("svg" to triangle, "svg" to twoBars)
        content()
        compose.runOnIdle { reduced = true }
        compose.waitForIdle()
        val play = frame("reference-play"); val pause = frame("reference-pause")
        exact(play, frame("button"), "Reduced play frame")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { playing = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        exact(pause, frame("button"), "Reduced motion snaps to pause")
        compose.runOnIdle { playing = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        exact(play, frame("button"), "Reduced motion snaps back to play")
        compose.mainClock.autoAdvance = true
    }
}

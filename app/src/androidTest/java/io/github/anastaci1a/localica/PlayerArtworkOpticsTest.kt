package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PlayerArtworkOpticsTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 1f })

    private fun visual(color: Int, key: String, width: Int = 32, height: Int = 96) = PlayerVisual(
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap(),
        PlayerPalette(Color(0xFF303040), Color(0xFF303040), Color(0xFF303040), aspect = width.toFloat() / height), contentKey = key)

    private fun output(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        return File(root, "player-artwork-optics").also { check(it.isDirectory || it.mkdirs()) }
    }

    @Test fun headerGlassKeepsTheCoverAcrossExpansionAndCollapse() {
        assumeTrue("SDK <33 has cached Legacy glass rather than live Modern sampling", Build.VERSION.SDK_INT >= 33)
        val progress = Animatable(1f)
        // Fill the header's horizontal region; a very narrow portrait leaves it
        // outside the actual fitted image even though its layout box spans the width.
        val red = visual(android.graphics.Color.RED, "red", width = 64, height = 100)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(PlaybackState(connected = true, title = "Track", artist = "Artist", durationMs = 120000),
                true, {}, InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground), progress,
                {}, {}, {}, {}, {}, {}, visual = red)
        } } }
        val directory = output()
        val samples = linkedMapOf<String, Float>()
        fun sample(name: String) {
            compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val lens = compose.onNodeWithContentDescription("Collapse player").fetchSemanticsNode().boundsInRoot
            val image = compose.onRoot().captureToImage()
            File(directory, "glass-$name.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val pixels = image.toPixelMap()
            var chroma = 0f
            var count = 0
            for (y in lens.top.toInt() until lens.bottom.toInt()) for (x in lens.left.toInt() until lens.right.toInt()) {
                val dx = x + .5f - lens.center.x
                val dy = y + .5f - lens.center.y
                val radius = dx * dx + dy * dy
                // Inside the glass face, outside the neutral arrow glyph and perimeter.
                if (radius !in lens.width * lens.width * .28f * .28f..lens.width * lens.width * .39f * .39f) continue
                val color = pixels[(x - root.left).toInt(), (y - root.top).toInt()]
                chroma += color.red - color.green
                count++
            }
            assertTrue("The glass annulus has sampled pixels", count > 20)
            samples[name] = chroma / count
        }
        sample("settled-start")
        for ((name, p) in listOf("collapse" to .95f, "near-collapse" to .999999f,
            "settled-reverse" to 1f, "near-expand" to .999999f, "expand" to .95f, "settled-end" to 1f)) {
            compose.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) } }
            sample(name)
        }
        File(directory, "glass-samples.txt").writeText(samples.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        val settled = samples.getValue("settled-start")
        assertTrue("Settled glass must actually sample the red artwork: $samples", settled > .04f)
        for (name in listOf("near-collapse", "near-expand", "settled-reverse", "settled-end"))
            assertEquals("Glass keeps the same artwork at $name: $samples", settled, samples.getValue(name), .025f)
    }

    @Test fun coverBlendKeepsItsFrameAtBothPlayerBoundaries() {
        val red = visual(android.graphics.Color.RED, "red")
        val blue = visual(android.graphics.Color.BLUE, "blue")
        val progress = Animatable(1f)
        var endpoint by mutableFloatStateOf(1f)
        var layout by mutableStateOf(ArtworkLayout.FullBackground)
        var incoming by mutableStateOf(false)
        var scene by mutableIntStateOf(0)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { key(scene) { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(PlaybackState(connected = true, trackId = if (incoming) "blue" else "red",
                title = "Track", artist = "Artist", durationMs = 120000), endpoint == 1f, {},
                InterfaceSettings(artworkLayout = layout), progress,
                {}, {}, {}, {}, {}, {}, visual = if (incoming) blue else red)
        } } } }
        val directory = output()
        val failures = mutableListOf<String>()
        val observations = mutableListOf<String>()
        try {
            for (artworkLayout in ArtworkLayout.entries) for (end in listOf(1f, 0f)) {
                compose.mainClock.autoAdvance = true
                compose.runOnIdle {
                    incoming = false; layout = artworkLayout; endpoint = end
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(end) }
                    scene++
                }
                compose.waitForIdle()
                val coordinates = compose.onNodeWithTag("artwork-gesture", true).fetchSemanticsNode().layoutInfo.coordinates
                val cover = compose.runOnUiThread { coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false) }
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val visible = cover.intersect(root)
                val x = (visible.center.x - root.left).toInt()
                val y = (visible.top + visible.height * .3f - root.top).toInt()
                val prefix = "$artworkLayout-end-$end"
                fun sample(phase: String): Color {
                    val image = compose.onRoot().captureToImage()
                    File(directory, "$prefix-$phase.png").outputStream().use {
                        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    return image.toPixelMap()[x, y].also { observations += "$prefix-$phase=$it" }
                }
                sample("red")
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread { incoming = true }
                // Establish the fixture's admitted fade before testing either
                // progress boundary. Native recording can delay its first frame.
                // Boundary samples below never wait for an optical result.
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(InterfaceSettings().motionDuration(550).toLong() / 3)
                compose.waitUntil(15000) {
                    compose.mainClock.advanceTimeByFrame()
                    val color = compose.onRoot().captureToImage().toPixelMap()[x, y]
                    observations += "$prefix-admitting-${compose.mainClock.currentTime}=$color"
                    color.red in .2f.. .8f && color.blue in .2f.. .8f
                }
                val mixed = sample("mixed")
                compose.runOnUiThread {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(if (end == 1f) .999999f else .000001f) }
                }
                compose.mainClock.advanceTimeByFrame()
                val moving = sample("moving")
                compose.runOnUiThread { scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(end) } }
                compose.mainClock.advanceTimeByFrame()
                val returned = sample("returned")
                compose.mainClock.advanceTimeBy(InterfaceSettings().motionDuration(550).toLong() + 100)
                // Only the final endpoint drains outstanding draw ownership. The
                // motion-entry and return samples above observe their exact frames.
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                sample("late")
                if (mixed.red <= .05f || mixed.blue <= .05f) failures += "$prefix did not establish a mixed frame: $mixed"
                // At 1x speed the default blur reveal can advance by .195 in
                // one 16ms frame; allow quantization, but reject a fresh solid target.
                if (abs(moving.blue - mixed.blue) > .22f || abs(moving.red - mixed.red) > .22f)
                    failures += "$prefix jumped on entering player motion: $mixed -> $moving"
                if (returned.blue < moving.blue - .04f) failures += "$prefix jumped backwards on settle: $moving -> $returned"
            }
        } finally {
            compose.mainClock.autoAdvance = true
            File(directory, "blend-samples.txt").writeText(observations.joinToString("\n") + "\n")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.AndroidArtwork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

class PlayerBackdropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coverColorsContinueBelowTheArtworkWithoutAFlatBand() = verifyBackdrop(prepared = true)

    @Test fun coldMountPresentsTheEmbeddedCoverAndItsColorContinuation() = verifyBackdrop(prepared = false)

    private fun verifyBackdrop(prepared: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.cacheDir, "player-wash.mp3")
        val cover = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
        Canvas(cover).apply {
            drawColor(android.graphics.Color.rgb(22, 114, 166))
            val paint = Paint()
            for (y in 0..2) for (x in 0..3) {
                paint.color = if ((x + y) % 2 == 0) android.graphics.Color.rgb(225, 102, 18) else android.graphics.Color.rgb(214, 200, 172)
                drawRect(x * 96f, 168f + y * 72f, (x + 1) * 96f, 240f + y * 72f, paint)
            }
        }
        try {
            instrumentation.context.assets.open("tagged.mp3").use { input -> audio.outputStream().use(input::copyTo) }
            val bytes = ByteArrayOutputStream().also { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            AudioFileIO.read(audio).apply { tag.deleteArtworkField(); tag.setField(object : AndroidArtwork() {
                override fun setImageFromData() = true
            }.apply { binaryData = bytes; mimeType = "image/png"; width = 384; height = 384; pictureType = 3 }); commit() }
            ArtworkCache.invalidate()
            val state = PlaybackState(connected = true, trackUri = audio.toURI().toString(), title = "Color study", artist = "Localica", durationMs = 180000)
            // Strict geometry checks start ready; the separate cold case uses the default
            // asynchronous player path and its own end-to-end readiness budget.
            val visual = if (prepared) runBlocking { PlayerVisualCache.read(context, state.trackUri!!) } else null
            if (prepared) {
                assertNotNull("The embedded fixture cover must decode", visual?.artwork)
                assertNotNull("The fixture must include its prepared color continuation", visual?.palette?.wash)
            }
            compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(state, true, {}, InterfaceSettings(reducedMotion = true), remember { Animatable(1f) }, {}, {}, {}, {}, {}, {}, visual = visual)
            } } }
            val player = compose.onNodeWithTag("player-surface")
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?.let(::File) ?: context.getExternalFilesDir(null) ?: context.cacheDir
            output.mkdirs()
            if (!prepared) {
                var last: ImageBitmap? = null
                try {
                    // Actual pixels acknowledge decode, palette preparation and presentation;
                    // a cache hit alone does not establish that the cold player has drawn.
                    compose.waitUntil(30_000) {
                        val image = player.captureToImage().also { last = it }
                        val pixels = image.toPixelMap()
                        val coverColor = pixels[pixels.width / 2, pixels.height / 6]
                        val bounds = player.fetchSemanticsNode().boundsInRoot
                        val art = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
                        val y = (art.bottom - bounds.top + 12).toInt().coerceIn(0, pixels.height - 1)
                        val washColor = pixels[pixels.width / 8, y]
                        coverColor.blue > coverColor.red * 1.4f && washColor.red > washColor.blue * 1.4f
                    }
                } catch (failure: Throwable) {
                    android.util.Log.e("PlayerBackdropTest", "Cold mount preparation: " + PlayerVisualCache.peek(state.trackUri!!))
                    runCatching {
                        last?.asAndroidBitmap()?.let { bitmap ->
                            File(output, "player-cold-timeout.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            try {
                                File(output, "player-cold-native-timeout.png").outputStream().use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            } finally { bitmap.recycle() }
                        }
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
                File(output, "player-cold-ready.png").outputStream().use {
                    requireNotNull(last).asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                return
            }
            val image = player.captureToImage()
            File(output, "player-artwork-wash.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val pixels = image.toPixelMap()
            val bounds = player.fetchSemanticsNode().boundsInRoot
            val art = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
            val coverColor = pixels[pixels.width / 2, pixels.height / 6]
            assertTrue("The prepared blue cover must be presented", coverColor.blue > coverColor.red * 1.4f)
            val washY = (art.bottom - bounds.top + 12).toInt().coerceIn(0, pixels.height - 1)
            val washColor = pixels[pixels.width / 8, washY]
            assertTrue("The prepared orange continuation must be presented", washColor.red > washColor.blue * 1.4f)
            val unclipped = compose.onNodeWithTag("player-artwork").getUnclippedBoundsInRoot()
            assertEquals("Keep the cover's aspect ratio", (unclipped.right - unclipped.left).value,
                (unclipped.bottom - unclipped.top).value, 1f)
            assertEquals("Keep full-width artwork", bounds.width, art.width, 1f)
            val row = (art.bottom - bounds.top + 12).toInt().coerceAtMost(pixels.height - 1)
            val left = pixels[(pixels.width * 0.12f).toInt(), row]
            val right = pixels[(pixels.width * 0.88f).toInt(), row]
            assertTrue("Artwork colors should remain spatially varied below its edge", abs(left.green - right.green) + abs(left.blue - right.blue) > 0.08f)
            // Sample outside the metadata/controls, which can cross the cover's bottom edge.
            val x = (pixels.width * 0.02f).toInt()
            val edge = (art.bottom - bounds.top).toInt()
            val above = pixels[x, edge - 2]; val below = pixels[x, edge + 2]
            assertTrue("No visible color seam at the cover boundary", abs(above.red - below.red) + abs(above.green - below.green) + abs(above.blue - below.blue) < 0.08f)
        } finally { audio.delete(); cover.recycle(); ArtworkCache.invalidate() }
    }
}

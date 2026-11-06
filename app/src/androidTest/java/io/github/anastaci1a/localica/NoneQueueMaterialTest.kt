package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class NoneQueueMaterialTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun minimizedDrawerHasOneOpaqueMaterial() = drawer(0f)
    @Test fun midPullDrawerHidesThePlayerAndKeepsOneMaterial() = drawer(.45f)

    private fun drawer(fraction: Float) {
        val tracks = (1..18).map { Track("none:$it", "Queue song $it", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 0, "Queue material")
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        var playerWhite by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassAppearance provides GlassAppearance(whiteTint = .35f, contrast = .15f, shadowStrength = 0f)
        ) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalSessionSelection provides remember { SessionSelection() }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(Color.Black)
                        drawRect(Color.White, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                    }
                    // Sharp player controls are displayed over, and excluded from, its material source.
                    Canvas(Modifier.fillMaxSize()) {
                        for (y in 0 until size.height.toInt() step 16) drawRect(if (playerWhite) Color.White else Color.Black,
                            Offset(0f, y.toFloat()), Size(size.width, 8f))
                    }
                    QueueSurface(remember { Animatable(fraction) }, InterfaceSettings(), {}) {
                        Box(Modifier.weight(1f)) { QueueScreen(state, null, library, onMinimize = {}, playerOpen = false) }
                    }
                }
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        fun capture(label: String): androidx.compose.ui.graphics.ImageBitmap {
            val image = compose.onNodeWithTag("queue-surface").captureToImage()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val output = File(context.getExternalFilesDir(null), "none-queue-api${Build.VERSION.SDK_INT}").apply { mkdirs() }
            File(output, "$fraction-$label.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, "$fraction-$label-screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
            return image
        }
        val first = capture("black-player").toPixelMap()
        // captureToImage retains the full layout height, including offscreen blank pixels.
        val visibleHeight = compose.onNodeWithTag("queue-surface").fetchSemanticsNode().boundsInRoot.height.toInt().coerceAtMost(first.height)
        compose.runOnIdle { playerWhite = true }
        val second = capture("white-player").toPixelMap()
        var maximum = 0f
        // Exclude the rounded contour and its translucent rim; include the actual header/list.
        for (y in 12 until visibleHeight - 12) for (x in first.width / 10 until first.width * 9 / 10) {
            val a = first[x, y]; val b = second[x, y]
            maximum = maxOf(maximum, abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        }
        assertTrue("Player controls must not show through the None drawer at progress=$fraction: maximum channel delta=$maximum", maximum < .025f)
        val expected = .5f + .15f * .5f * .5f
        if (Build.VERSION.SDK_INT < 33) {
            val bounds = compose.onNodeWithTag("queue-surface").fetchSemanticsNode().boundsInRoot
            val sourceTop = compose.runOnIdle { source.location.coordinates!!.localToRoot(Offset.Zero).y }
            val footprint = Rect(0f, (bounds.top - sourceTop).coerceAtLeast(0f), source.size.width.toFloat(), source.size.height.toFloat())
            fun padded(value: Int): Int {
                var extent = 1
                while (extent < value) extent *= 2
                return extent
            }
            val histogram = mutableMapOf<Float, Int>()
            for (y in 12 until visibleHeight * 2 / 3) for (x in first.width / 10 until first.width * 9 / 10) {
                val red = first[x, y].red
                histogram[red] = (histogram[red] ?: 0) + 1
            }
            assertLegacyAreaBound(histogram.maxBy { it.value }.key, expected, footprint, padded(source.size.width), padded(source.size.height))
        }
        if (fraction > 0f) for (description in listOf("Minimize queue", "Sort queue", "Save queue to playlist")) {
            val pixels = compose.onNodeWithContentDescription(description).captureToImage().asAndroidBitmap()
            val histogram = mutableMapOf<Int, Int>()
            for (y in pixels.height / 4 until pixels.height * 3 / 4) for (x in pixels.width / 4 until pixels.width * 3 / 4) {
                val pixel = pixels.getPixel(x, y)
                histogram[pixel] = (histogram[pixel] ?: 0) + 1
            }
            val fill = Color(histogram.maxBy { it.value }.key)
            assertEquals("$description inherits parent red without another tint/tone", expected, fill.red, .025f)
            assertEquals("$description inherits parent green without another tint/tone", expected, fill.green, .025f)
            assertEquals("$description inherits parent blue without another tint/tone", expected, fill.blue, .025f)
        }
    }
}

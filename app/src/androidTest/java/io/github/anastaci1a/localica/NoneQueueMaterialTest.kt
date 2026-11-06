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
            LocalGlassAppearance provides GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
        ) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalSessionSelection provides remember { SessionSelection() }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFF406080)) }
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
            return image
        }
        val first = capture("black-player").toPixelMap()
        compose.runOnIdle { playerWhite = true }
        val second = capture("white-player").toPixelMap()
        var maximum = 0f
        // Exclude the rounded contour and its translucent rim; include the actual header/list.
        for (y in 12 until first.height - 12) for (x in first.width / 10 until first.width * 9 / 10) {
            val a = first[x, y]; val b = second[x, y]
            maximum = maxOf(maximum, abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        }
        assertTrue("Player controls must not show through the None drawer at progress=$fraction: maximum channel delta=$maximum", maximum < .025f)
    }
}

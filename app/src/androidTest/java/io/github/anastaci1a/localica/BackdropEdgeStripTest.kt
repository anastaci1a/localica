package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** #711 evidence: the settled full-background player backdrop (wash + feathered cover) for every fixture, saved as one strip. */
class BackdropEdgeStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordsTheSettledFullBackgroundFrameForEveryFixture() {
        val covers = BackdropEdgeCovers.bordered() + BackdropEdgeCovers.borderless()
        var index by mutableIntStateOf(0)
        val palettes = covers.map { runBlocking { playerPalette(it.bitmap) } }
        val images = covers.map { it.bitmap.asImageBitmap() }
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            Box(Modifier.size(270.dp, 585.dp).testTag("backdrop-frame")) {
                with(density) {
                    // Settled FullBackground geometry: full-width cover, wash continuation and the 0.26 edge feather.
                    PlayerBackdrop(palettes[index], Modifier.fillMaxSize(), fullArtBlend = 1f,
                        artTop = 80.dp.toPx(), artHeight = 270.dp.toPx(), artLeft = 0f, artWidth = 270.dp.toPx(), softness = 0f)
                }
                Image(images[index], null, Modifier.offset(y = 80.dp).size(270.dp).artworkEdgeFeather(1f, .26f, 1f),
                    contentScale = ContentScale.Crop)
            }
        } }
        val frames = covers.indices.map { i ->
            compose.runOnIdle { index = i }
            compose.waitForIdle()
            compose.onNodeWithTag("backdrop-frame").captureToImage().asAndroidBitmap()
        }
        val gutter = 6
        val strip = Bitmap.createBitmap(frames.sumOf { it.width } + gutter * (frames.size - 1), frames.maxOf { it.height }, Bitmap.Config.ARGB_8888)
        strip.eraseColor(android.graphics.Color.MAGENTA)
        val canvas = android.graphics.Canvas(strip)
        var x = 0
        for (frame in frames) { canvas.drawBitmap(frame, x.toFloat(), 0f, null); x += frame.width + gutter }
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))).let { File(it, "backdrop-edge-711") }
        root.mkdirs()
        File(root, "strip-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(root, "strip-api${android.os.Build.VERSION.SDK_INT}.txt").writeText(covers.joinToString("\n") { it.name } + "\n")
        assertEquals(covers.size, frames.size)
    }
}

package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Background-only Image oracle: no control recording participates in production sampling. */
class PlayerBackgroundImageMappingTest {
    @get:Rule val compose = createComposeRule()
    private data class Scene(val width: Int, val height: Int, val portrait: Boolean,
        val policy: Int, val feather: Boolean, val inset: Int)

    @Test fun actualPainterMatchesSamplesAtRoundedBordersLetterboxesAndFeatherStops() {
        fun pixels(width: Int, height: Int) = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            android.graphics.Color.rgb(255 * x / (width - 1), 255 * y / (height - 1), if (y == height - 1) 0 else 255)
        }
        val landscape = BackgroundPixels(7, 4, pixels(7, 4))
        val portrait = BackgroundPixels(4, 7, pixels(4, 7))
        fun bitmap(pixels: BackgroundPixels) = Bitmap.createBitmap(pixels.colors, pixels.width, pixels.height,
            Bitmap.Config.ARGB_8888).asImageBitmap()
        val images = mapOf(false to bitmap(landscape), true to bitmap(portrait))
        var scene by mutableStateOf(Scene(31, 27, false, 0, false, 0))
        fun policy(scene: Scene) = when (scene.policy) {
            0 -> ContentScale.Fit
            1 -> ContentScale.Crop
            else -> playerArtworkContentScale(32f, .4f, false)
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(scene.inset.dp)) {
                    Box(Modifier.size(scene.width.dp, scene.height.dp).testTag("painted-background")
                        .artworkEdgeFeather(if (scene.feather) .8f else 0f, .26f, if (scene.feather) 1f else 0f)) {
                        Box(Modifier.fillMaxSize().background(Color(.2f, .3f, .4f)))
                        Image(images.getValue(scene.portrait), null, Modifier.fillMaxSize(), contentScale = policy(scene))
                    }
                }
            }
        }
        for (isPortrait in listOf(false, true)) for ((width, height) in listOf(31 to 27, 27 to 31)) {
            for (mode in 0..2) for (feather in listOf(false, true)) {
                val next = Scene(width, height, isPortrait, mode, feather, if (feather) 17 else 0)
                compose.runOnIdle { scene = next }
                val image = compose.onNodeWithTag("painted-background").captureToImage().toPixelMap()
                val source = if (isPortrait) portrait else landscape
                val bounds = requireNotNull(playerBackgroundImageBounds(Size(source.width.toFloat(), source.height.toFloat()),
                    Rect(0f, 0f, width.toFloat(), height.toFloat()), policy(next)))
                // Include the entire one-pixel perimeter and letterbox boundaries. Interior
                // points also cross every 33-stop feather segment on these small frames.
                var worst = 0f
                for (y in 0 until height) for (x in 0 until width) {
                    val u = (x + .5f - bounds.left) / bounds.width
                    val v = (y + .5f - bounds.top) / bounds.height
                    val base = if (u in 0f..1f && v in 0f..1f) source.at(u, v) else BackgroundRgb(.2f, .3f, .4f)
                    val alpha = if (feather) playerBackgroundFeather((x + .5f) / width, (y + .5f) / height, .8f, .26f, 1f) else 1f
                    val actual = image[x, y]
                    worst = maxOf(worst, abs(actual.red - base.r * alpha), abs(actual.green - base.g * alpha), abs(actual.blue - base.b * alpha))
                }
                // Bilinear GPU filtering and three separately quantized DstIn masks differ
                // from Float CPU metadata by a few 8-bit steps, not by a source texel.
                assertTrue("$next bounds=$bounds max channel error=$worst", worst <= .035f)
            }
        }
    }
}

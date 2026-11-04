package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkBlendLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingTargetsCannotFreezeAnInterruptedBlendOrReviveAnOldVisit() {
        fun cover(key: String, color: Int): PlayerVisual {
            val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(color) }.asImageBitmap()
            // Blurring an opaque solid cover preserves its pixels on both render paths.
            return PlayerVisual(image, softArtwork = image, contentKey = key)
        }
        val red = cover("red", android.graphics.Color.RED)
        val blue = cover("blue", android.graphics.Color.BLUE)
        val green = cover("green", android.graphics.Color.GREEN)
        var visual by mutableStateOf(red)
        var visit by mutableStateOf("red-visit")
        var latest: ArtworkBackgroundFrame? = null
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                ArtworkBlend(visual, Modifier.size(144.dp).testTag("loading-blend"), 1000,
                    occurrence = visit, onBackgroundFrame = { latest = it }) { frame ->
                    Image(requireNotNull(frame.artwork), null, Modifier.fillMaxSize())
                }
            }
        } }
        data class Sample(val time: Long, val frame: ArtworkBackgroundFrame)
        fun samples(duration: Long): List<Sample> {
            val result = mutableListOf<Sample>()
            val start = compose.mainClock.currentTime
            while (compose.mainClock.currentTime - start < duration) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                result += compose.runOnUiThread {
                    Sample(compose.mainClock.currentTime, requireNotNull(latest))
                }
            }
            return result
        }
        fun settled(sample: Sample, key: String) {
            assertFalse("Loading cannot leave an animation frozen after its full frame budget at ${sample.time}",
                sample.frame.transitioning)
            assertFalse("No frozen composite may remain after settlement", sample.frame.frozenVisible)
            assertFalse("No live blur may remain after settlement", sample.frame.liveBlur)
            val weights = sample.frame.roles.groupBy { it.visual.contentKey }
                .mapValues { (_, roles) -> roles.sumOf { it.weight.toDouble() }.toFloat() }
            assertEquals(setOf(key), weights.keys)
            assertEquals(1f, weights.getValue(key), .001f)
        }
        fun pixels(color: Color) {
            val time = compose.mainClock.currentTime
            val image = compose.onNodeWithTag("loading-blend").captureToImage().toPixelMap()
            assertEquals("Pixel sampling must hold the animation frame", time, compose.mainClock.currentTime)
            val center = image[image.width / 2, image.height / 2]
            assertEquals(color.red, center.red, .01f)
            assertEquals(color.green, center.green, .01f)
            assertEquals(color.blue, center.blue, .01f)
            assertEquals(1f, center.alpha, .001f)
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { visual = blue; visit = "blue-visit" }
            val opening = samples(480)
            assertTrue("Interrupt an actually blended frame, not an already settled cover", opening.any { sample ->
                sample.frame.transitioning && listOf("red", "blue").all { key ->
                    sample.frame.roles.any { it.visual.contentKey == key && it.weight in 0.1f..0.9f }
                }
            })
            compose.runOnUiThread {
                visual = blue.copy(loading = true, loadingUri = "loading-third")
                visit = "third-visit"
            }
            val held = samples(1400)
            settled(held.last(), "blue")
            pixels(Color.Blue)

            compose.runOnUiThread {
                visual = blue.copy(loading = true, loadingUri = "loading-fourth")
                visit = "fourth-visit"
            }
            samples(160).forEach { settled(it, "blue") }
            pixels(Color.Blue)

            compose.runOnUiThread { visual = green; visit = "fourth-visit" }
            val resolved = samples(1400)
            assertTrue("The resolved visit must still own a real transition", resolved.any { it.frame.transitioning })
            settled(resolved.last(), "green")
            pixels(Color.Green)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
}

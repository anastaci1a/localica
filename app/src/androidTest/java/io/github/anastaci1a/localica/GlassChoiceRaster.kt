package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue

/** Shared real-control raster fixture, calibrated from each scenario's resting silhouette. */
internal class GlassChoiceRaster(private val compose: ComposeContentTestRule) {
    var touchSlop = 0f
        private set
    private var presentationStage by mutableIntStateOf(0)
    private lateinit var pressureView: View

    fun setContent(labels: List<String>, horizontal: Boolean = false, damping: Float = 8f, reducedMotion: () -> Boolean = { false }) {
        var source: FrostedBackdrop? = null
        compose.setContent { LocalicaTheme {
            pressureView = LocalView.current
            touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
            val backing = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
            source = backing
            Box(Modifier.width(284.dp).padding(top = 100.dp).testTag("pressure-scene")) {
                // Record a distinct scene, then cover its direct presentation.
                // Only the real lens can display its red pixels; labels and the
                // track remain on blue, allowing a renderer-independent scan.
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) { drawRect(Color.Red) }
                Canvas(Modifier.matchParentSize()) { drawRect(Color.Blue) }
                CompositionLocalProvider(LocalFrostedBackdrop provides backing,
                    LocalReducedMotion provides reducedMotion(),
                    LocalGlassAppearance provides LocalGlassAppearance.current.copy(shapeDamping = damping)) {
                    Box(Modifier.padding(12.dp)) { GlassChoices(labels, 0, horizontal = horizontal) { } }
                }
                PresentationMarker { presentationStage }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source?.lensSnapshot != null }
        compose.waitForIdle()
    }

    fun bounds(): Rect {
        compose.runOnIdle { presentationStage++ }
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("pressure-scene"), presentationStage) { pressureView }
        var left = pixels.width; var top = pixels.height; var right = -1; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .6f && color.green < .4f && color.blue < .4f) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("Real selected lens must have a visible red silhouette", right >= left && bottom >= top)
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat()).also {
            android.util.Log.i("GlassPressure", "stage=$presentationStage displayedBounds=$it")
        }
    }

}

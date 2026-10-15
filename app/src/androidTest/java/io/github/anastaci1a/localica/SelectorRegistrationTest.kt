package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Diagnostic fixture for #223. It deliberately exercises the current layout-layer
 * transform shared with SelectionLens, before choosing a coordinate-system fix. */
class SelectorRegistrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun centerLandmarkStaysRegisteredThroughHorizontalAndVerticalDeformation() = registration(false)
    @Test fun centerLandmarkStaysRegisteredInsideOffsetModal() = registration(true)

    private fun registration(modal: Boolean) {
        var shape by mutableStateOf(GlassShape())
        var vertical by mutableStateOf(false)
        var readySource: FrostedBackdrop? = null
        lateinit var sceneView: View
        compose.setContent { LocalicaTheme {
            @Composable fun Scene() {
                sceneView = LocalView.current
                val source = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
                readySource = source
                CompositionLocalProvider(LocalFrostedBackdrop provides source,
                    LocalGlassAppearance provides LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, contrast = 0f)) {
                    // Offset both source and consumer inside their window. The same
                    // marker must survive a different Dialog screen origin as well.
                    Box(Modifier.padding(start = 17.dp, top = 29.dp, end = 11.dp, bottom = 13.dp)) {
                        Box(Modifier.size(280.dp, 240.dp).testTag("registration-scene")) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                                val cell = 10.dp.toPx()
                                for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                                    drawRect(if ((x + y) % 2 == 0) Color.Blue else Color.Black,
                                        Offset(x * cell, y * cell), Size(cell, cell))
                                }
                                val marker = 2.dp.toPx()
                                drawRect(Color.Red, center - Offset(marker, marker), Size(marker * 2, marker * 2))
                            }
                            LiquidGlassSurface(Modifier.align(Alignment.Center).size(220.dp, 120.dp)
                                .selectorDeformation({ shape }, vertical).testTag("registration-lens"), pressFeedback = false, shadow = false, body = { shape })
                        }
                    }
                }
            }
            if (modal) GlassMorphPopup(true, rememberGlassAnchor(), {}) { Scene() } else Scene()
        } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { readySource?.lensSnapshot != null }
        compose.waitForIdle()
        val momentum = GlassMomentum()
        repeat(180) { momentum.advance(0f, 1f / 60, pressure = -.15f, contact = 1f) }
        val stages = mutableListOf("rest" to GlassShape(), "held" to momentum.shape)
        repeat(32) { frame ->
            momentum.advance(0f, 1f / 60)
            if (frame in listOf(0, 6, 12, 18, 27)) stages += "release-$frame" to momentum.shape
        }
        val failures = mutableListOf<String>()
        for (axis in listOf(false, true)) for ((stage, next) in stages) {
            compose.runOnIdle { vertical = axis; shape = next }
            val pixels = capturePopupPixels(compose, compose.onNodeWithTag("registration-scene")) { sceneView }
            var red = 0f; var blue = 0f
            for (y in pixels.height / 2 - 1..pixels.height / 2 + 1)
                for (x in pixels.width / 2 - 1..pixels.width / 2 + 1) {
                    red += pixels[x, y].red; blue += pixels[x, y].blue
                }
            val bounds = compose.onNodeWithTag("registration-lens").fetchSemanticsNode().boundsInRoot
            val result = "modal=$modal vertical=$axis stage=$stage strain=${next.strain} depth=${next.depth} " +
                "lensBounds=$bounds sourceOrigin=${readySource?.origin} sourceSize=${readySource?.size} " +
                "red=${red / 9} blue=${blue / 9}"
            android.util.Log.i("SelectorRegistration", result)
            if (!(red / 9 > .6f && blue / 9 < .3f)) failures += result
        }
        assertTrue("Symmetric lens center must retain its red landmark at every stage:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}

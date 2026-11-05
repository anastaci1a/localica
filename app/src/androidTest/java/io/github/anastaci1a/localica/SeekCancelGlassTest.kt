package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.sqrt

/**
 * #562: the seek-cancel (X) refracts the composited player scene, UI included, not only the
 * artwork recording. The target is a sibling of the recorded scene, so it never samples itself.
 */
class SeekCancelGlassTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelTargetRefractsTheUiBehindItAndOnlyTheSceneRecordingDoesSo() {
        var drawn: GlassSurfaceDrawObservation? = null
        var chosenScene by mutableStateOf(false)
        var progress by mutableFloatStateOf(1f)
        var selected: FrostedBackdrop? = null
        lateinit var artRecording: FrostedBackdrop
        lateinit var sceneRecording: FrostedBackdrop
        val state = SeekCancelTargetState()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true,
            LocalGlassSurfaceDrawObserver provides { drawn = it }) {
            artRecording = rememberFrostedBackdrop("art", dark = false)
            sceneRecording = rememberFrostedBackdrop("scene", dark = false)
            Box(Modifier.size(300.dp)) {
                // The scene: artwork plus UI (high-contrast stripes), recorded as one composite.
                Box(Modifier.fillMaxSize().recordFrostedBackdrop(sceneRecording)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(artRecording)) { drawRect(Color(0xFF808080)) }
                    Canvas(Modifier.fillMaxSize()) {
                        val stripe = 6.dp.toPx()
                        var x = 0f; var i = 0
                        while (x < size.width) {
                            drawRect(if (i % 2 == 0) Color.Black else Color.White, Offset(x, size.height * .6f), Size(stripe, size.height * .4f))
                            x += stripe; i++
                        }
                    }
                }
                val source = seekCancelBackdrop(sceneRecording.takeIf { chosenScene }, artRecording, progress)
                selected = source
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
                        SeekCancelTarget(state, false, 0.dp, true)
                    }
                }
            }
        } } }
        compose.runOnIdle { state.update(true) }
        compose.waitForIdle()
        fun awaitSource(source: FrostedBackdrop) {
            // Compose idleness does not include the Legacy renderer's off-thread
            // readback and lens preparation. A cold surface otherwise exposes the
            // scene stripes beneath it, even when its selected recording is gray.
            compose.waitUntil(10000) {
                compose.runOnIdle {
                    val legacy = source.rendererGeneration.backend == GlassBackend.Legacy
                    val painting = drawn?.source
                    val rasterReady = !legacy || (source.snapshot != null && source.fallback != null &&
                        source.lensSnapshot != null && source.lensFrostSnapshot != null)
                    selected === source && source.recorded && rasterReady &&
                        painting?.identity == System.identityHashCode(source) && painting.recorded &&
                        (!legacy || (painting.snapshotIdentity == System.identityHashCode(source.snapshot) &&
                            painting.lensIdentity == System.identityHashCode(source.lensSnapshot)))
                }
            }
            compose.waitForIdle()
        }
        fun spread(): Float {
            val pixels = compose.onNodeWithTag("cancel-seek").captureToImage().toPixelMap()
            val y = pixels.height * 85 / 100 // below the X glyph, inside the circle
            val values = (pixels.width * 3 / 10 until pixels.width * 7 / 10).map {
                val c = pixels[it, y]; .3f * c.red + .59f * c.green + .11f * c.blue
            }
            val mean = values.average().toFloat()
            return sqrt(values.map { (it - mean) * (it - mean) }.average().toFloat())
        }
        compose.runOnIdle { chosenScene = false }; compose.waitForIdle()
        awaitSource(artRecording)
        val artOnly = spread()
        compose.runOnIdle { chosenScene = true }; compose.waitForIdle()
        compose.runOnIdle { assertSame("A fully open player refracts its scene", sceneRecording, selected) }
        awaitSource(sceneRecording)
        val withUi = spread()
        assertTrue("The UI behind the X is refracted by the scene recording: scene=$withUi artwork-only=$artOnly", withUi > artOnly + .03f)
        compose.runOnIdle { progress = .5f }; compose.waitForIdle()
        compose.runOnIdle { assertSame("Before the scene is recorded the artwork recording is used", artRecording, selected) }
    }
}

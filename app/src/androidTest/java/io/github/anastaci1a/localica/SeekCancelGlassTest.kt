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
import io.github.anastaci1a.localica.navigation.GlassRendererMode
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

    @Test fun cancelTargetRefractsTheUiBehindItAndOnlyTheSceneRecordingDoesSo() = verifyScene(GlassRendererMode.Normal)

    @Test fun cancelTargetUsesUniformAverageWithMatchingDrawSource() = verifyScene(GlassRendererMode.SingleToneBlur)

    private fun verifyScene(mode: GlassRendererMode) {
        val optical = mode == GlassRendererMode.Normal && android.os.Build.VERSION.SDK_INT >= 33
        val runtime = GlassRendererRuntime(mode)
        var drawn: GlassSurfaceDrawObservation? = null
        var chosenScene by mutableStateOf(false)
        var progress by mutableFloatStateOf(1f)
        var selected: FrostedBackdrop? = null
        lateinit var artRecording: FrostedBackdrop
        lateinit var sceneRecording: FrostedBackdrop
        val state = SeekCancelTargetState()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true,
            LocalGlassRendererRuntime provides runtime, LocalGlassSurfaceDrawObserver provides { drawn = it }) {
            artRecording = rememberFrostedBackdrop("art", dark = false)
            sceneRecording = rememberFrostedBackdrop("scene", dark = false)
            Box(Modifier.size(300.dp)) {
                // The scene: artwork plus UI (high-contrast stripes), recorded as one composite.
                Box(Modifier.fillMaxSize().recordFrostedBackdrop(sceneRecording)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(artRecording)) { drawRect(if (optical) Color(0xFF808080) else Color(0xFF404040)) }
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
            // #605: selected ownership and a matching material draw are required;
            // older Android additionally waits for its average thumbnail publication.
            compose.waitUntil(10000) {
                compose.runOnIdle {
                    val cpu = android.os.Build.VERSION.SDK_INT < 33
                    val painting = drawn?.source
                    selected === source && source.recorded &&
                        (!cpu || (source.snapshot != null && source.snapshotReadbackRequest >= 0)) &&
                        painting?.identity == System.identityHashCode(source) && painting.recorded &&
                        (!cpu || (painting.snapshotIdentity == System.identityHashCode(source.snapshot) &&
                            painting.publishedReadback == source.snapshotReadbackRequest))
                }
            }
            compose.waitForIdle()
        }
        fun sample(): Pair<Float, Float> {
            val pixels = compose.onNodeWithTag("cancel-seek").captureToImage().toPixelMap()
            val y = pixels.height * 85 / 100 // below the X glyph, inside the circle
            val values = (pixels.width * 3 / 10 until pixels.width * 7 / 10).map {
                val c = pixels[it, y]; .3f * c.red + .59f * c.green + .11f * c.blue
            }
            val mean = values.average().toFloat()
            return sqrt(values.map { (it - mean) * (it - mean) }.average().toFloat()) to mean
        }
        compose.runOnIdle { chosenScene = false }; compose.waitForIdle()
        awaitSource(artRecording)
        val artOnly = sample()
        compose.runOnIdle { chosenScene = true }; compose.waitForIdle()
        compose.runOnIdle { assertSame("A fully open player refracts its scene", sceneRecording, selected) }
        awaitSource(sceneRecording)
        val withUi = sample()
        if (optical) {
            assertTrue("The UI behind the X is refracted: scene=$withUi artwork-only=$artOnly", withUi.first > artOnly.first + .03f)
        } else {
            assertTrue("$mode has no stripe detail on this API: $withUi", withUi.first < .04f)
            assertTrue("The averaged scene replaces the darker artwork: scene=$withUi artwork=$artOnly", withUi.second > artOnly.second + .1f)
        }
        compose.runOnIdle { progress = .5f }; compose.waitForIdle()
        compose.runOnIdle { assertSame("Before the scene is recorded the artwork recording is used", artRecording, selected) }
    }
}

package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.MorphFaces
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #733: a callable reference to a local function inside a composable (`::fn`) kept the values captured at its first
 * composition. Each test changes a captured value after the first composition and requires the current one. */
class LocalFunReferenceFreshnessTest {
    @get:Rule val compose = createComposeRule()

    private fun pixels(tag: String): List<Int> {
        val map = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return (0 until map.height).flatMap { y -> (0 until map.width).map { x -> map[x, y].hashCode() } }
    }

    @Test fun glassMorphFaceFollowsExpandedAfterReducedMotionFirstComposition() {
        var expanded by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        compose.setContent { EngineTheme(reduced = true) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                key(generation) {
                    Box(Modifier.testTag("morph")) {
                        MorphFaces(expanded, shared = {}, compact = { Box(Modifier.size(60.dp).background(Color.Red)) },
                            content = { Box(Modifier.size(100.dp).background(Color.Blue)) })
                    }
                }
            }
        } }
        compose.waitForIdle()
        val collapsed = pixels("morph")
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        val updated = pixels("morph")
        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        val fresh = pixels("morph")
        assertTrue("The fresh expanded face must differ from the compact one", fresh != collapsed)
        assertEquals("Toggling expanded after the first composition must draw what a fresh composition draws", fresh, updated)
    }

    @Test fun queueSettleReportsToTheCurrentCallback() {
        val progress = Animatable(1f)
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        var handler by mutableStateOf<(Boolean) -> Unit>({ first += it })
        compose.setContent { EngineTheme(reduced = true) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                QueueSurface(progress, InterfaceSettings(reducedMotion = true), handler) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("settle-list")) {
                        items(60) { Text("Song $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                }
            }
        } }
        fun pull() {
            val bounds = compose.onNodeWithTag("settle-list").fetchSemanticsNode().boundsInRoot
            val start = bounds.topLeft + Offset(bounds.width / 2f, bounds.height * .15f)
            compose.onRoot().performTouchInput {
                down(start)
                moveBy(Offset(0f, 80f), delayMillis = 80)
                advanceEventTime(300); up()
            }
            compose.waitForIdle()
        }
        pull()
        compose.runOnIdle { assertEquals(1, first.size); handler = { second += it } }
        compose.waitForIdle()
        pull()
        compose.runOnIdle {
            assertEquals("The replaced callback must not hear the second settle", 1, first.size)
            assertEquals("The current callback must hear the second settle", 1, second.size)
        }
    }

    @Test fun collectionBackdropFollowsTheCurrentArtTop() {
        val wash = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until 16) for (x in 0 until 16) bitmap.setPixel(x, y, android.graphics.Color.rgb(y * 16, 255 - y * 16, 128))
        }
        val visual = PlayerVisual(palette = NeutralPalette.copy(wash = wash, softWash = wash, collectionWash = wash))
        var artTop by mutableFloatStateOf(0f)
        var generation by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            key(generation) {
                Box(Modifier.size(240.dp, 400.dp).testTag("backdrop")) { CollectionBackdrop(visual, artTop, Modifier.fillMaxSize()) }
            }
        } }
        compose.waitForIdle()
        val initial = pixels("backdrop")
        compose.runOnIdle { artTop = 150f }
        compose.waitForIdle()
        val updated = pixels("backdrop")
        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        val fresh = pixels("backdrop")
        assertTrue("Moving the art top must change the backdrop", fresh != initial)
        assertEquals("A moved art top must draw what a fresh composition draws", fresh, updated)
    }
}

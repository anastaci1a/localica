package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.materialReductionExtent
import io.github.anastaci1a.localica.ui.rememberMaterialAreaReduction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/** Compare the retained chain against main's exact, always-recorded implementation. */
class MaterialAreaReductionReuseTest {
    @get:Rule val compose = localicaComposeRule()
    private class ProbeFailure : RuntimeException()
    private data class Input(val token: Int = 0, val phase: Int = 0,
        val crop: Rect = Rect(3.25f, 5.75f, 20.25f, 18.75f), val minimum: Int = 1,
        val failB: Boolean = false, val recreate: Boolean = false, val fontScale: Float = 1f)
    private class State {
        var parentRecords = 0
        var counts: Map<Int, Int> = emptyMap()
        var recovered = false
        var releasedOutput = false
        var result: GraphicsLayer? = null
    }

    @Test fun exactPixelsAndLiveChildrenSurviveReuseFailureAndRecreation() {
        val input = mutableStateOf(Input())
        val state = State()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, input.value.fontScale)) {
                val graphics = LocalGraphicsContext.current
                val reference = remember(graphics) { ForcedReduction(graphics) }
                DisposableEffect(reference) { onDispose { reference.release() } }
                val cached = rememberMaterialAreaReduction()
                val child = rememberGraphicsLayer()
                val parent = rememberGraphicsLayer()
                val retainedOutput = rememberGraphicsLayer()
                Canvas(Modifier.size(216.dp, 144.dp).testTag("reuse")) {
                    val request = input.value
                    val counts = mutableMapOf<Int, Int>()
                    var fail = request.failB
                    cached.onLevelRecord = { index, _ ->
                        counts[index] = (counts[index] ?: 0) + 1
                        if (fail && index == 2) throw ProbeFailure()
                    }
                    child.record(size = IntSize(63, 47)) {
                        drawRect(if (request.phase == 0) Color(0x805B193D) else Color(0xA0197D35))
                        for (x in 0 until 63) if ((x + request.phase) % 3 == 0)
                            drawRect(Color(0xC0F3D107), Offset(x.toFloat(), 0f), Size(1f, 47f))
                        drawRect(Color(0x407030E0), Offset(9.5f, 11.25f), Size(8.75f, 19.5f))
                    }
                    if (state.parentRecords == 0) {
                        parent.record(size = IntSize(63, 47)) { drawLayer(child) }
                        state.parentRecords++
                    }
                    state.recovered = false
                    if (request.failB) {
                        try {
                            with(cached) { record(IntSize(65, 35)) { drawRect(Color.Cyan) } }
                            error("The partially rebuilt B chain must throw")
                        } catch (_: ProbeFailure) { state.recovered = true }
                        fail = false
                    }
                    state.releasedOutput = false
                    if (request.recreate) {
                        val previous = requireNotNull(state.result)
                        cached.release()
                        state.releasedOutput = previous.isReleased
                    }
                    val crop = request.crop
                    val extent = IntSize(ceil(crop.width).toInt(), ceil(crop.height).toInt())
                    val paintCrop: DrawScope.() -> Unit = {
                        clipRect(right = crop.width, bottom = crop.height) {
                            translate(-crop.left, -crop.top) { drawLayer(parent) }
                        }
                    }
                    val expected = with(reference) { record(extent, request.minimum, paintCrop) }
                    val actual = with(cached) { record(extent, request.minimum, paintCrop) }
                    state.result = actual
                    state.counts = counts.toMap()
                    if (!request.recreate) retainedOutput.record(size = actual.size) { drawLayer(actual) }
                    drawRect(Color.Black)
                    drawRect(Color.White, Offset(0f, 72f), Size(216f, 72f))
                    fun DrawScope.cell(layer: GraphicsLayer, x: Float, y: Float) {
                        translate(x, y) {
                            scale(64f / layer.size.width, 64f / layer.size.height, Offset.Zero) { drawLayer(layer) }
                        }
                    }
                    for (y in listOf(4f, 76f)) {
                        cell(expected, 4f, y)
                        cell(actual, 76f, y)
                        cell(retainedOutput, 148f, y)
                    }
                }
            }
        }
        val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "reduction-reuse-api${Build.VERSION.SDK_INT}").also { check(it.mkdirs() || it.isDirectory) }
        val receipts = mutableListOf<String>()
        fun checkFrame(name: String, request: Input, reuse: Boolean = false, retained: Boolean = false): Int {
            compose.runOnIdle { input.value = request }
            compose.waitForIdle()
            val image = compose.onNodeWithTag("reuse").captureToImage().asAndroidBitmap()
            File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var differences = 0
            var retainedDifferences = 0
            for (top in listOf(4, 76)) for (y in top until top + 64) for (x in 4 until 68) {
                if (image.getPixel(x, y) != image.getPixel(x + 72, y)) differences++
                if (image.getPixel(x, y) != image.getPixel(x + 144, y)) retainedDifferences++
            }
            val counts = compose.runOnUiThread { state.counts }
            receipts += "$name differingPixels=$differences retainedDifferences=$retainedDifferences records=$counts parentRecords=${state.parentRecords} recovered=${state.recovered} released=${state.releasedOutput}"
            File(output, "comparison.txt").writeText(receipts.joinToString("\n", postfix = "\n"))
            assertEquals("$name exact RGBA output over black and white", 0, differences)
            if (retained) assertEquals("Released child content survives its retained parent", 0, retainedDifferences)
            if (reuse) assertEquals("$name only the original crop records", mapOf(0 to 1), counts)
            else assertTrue("$name rebuilds downstream levels", counts.keys.any { it > 0 })
            assertEquals("Source parent display list remains unchanged", 1, state.parentRecords)
            return image.getPixel(32, 32)
        }
        val a = Input(token = 1)
        val initial = checkFrame("initial", a.copy(recreate = true))
        checkFrame("unchanged", a.copy(token = 2), reuse = true)
        val changed = checkFrame("child-only", a.copy(token = 3, phase = 1), reuse = true)
        assertNotEquals("A child-only update propagates through the retained chain", initial, changed)
        checkFrame("fractional-origin", a.copy(token = 4, crop = Rect(2.5f, 4.25f, 19.5f, 17.25f)), reuse = true)
        checkFrame("larger-odd-extent", a.copy(token = 5, crop = Rect(1.25f, 2.75f, 34.25f, 23.75f)))
        checkFrame("back-to-a", a.copy(token = 6))
        checkFrame("a-failed-b-a", a.copy(token = 7, failB = true))
        assertTrue("Partial B reconstruction was injected", state.recovered)
        checkFrame("released-output-recreate", a.copy(token = 8, recreate = true), retained = true)
        assertTrue("The retained parent really references a released child", state.releasedOutput)
        checkFrame("minimum-32", a.copy(token = 9, crop = Rect(0f, 0f, 63f, 47f), minimum = 32))
        checkFrame("minimum-1", a.copy(token = 10, crop = Rect(0f, 0f, 63f, 47f)))
        checkFrame("font-scale", a.copy(token = 11, crop = Rect(0f, 0f, 63f, 47f), fontScale = 1.25f))
    }

    /** Original main 3ca9b635 arithmetic; intentionally records every level. */
    private class ForcedReduction(private val graphics: GraphicsContext) {
        private val levels = mutableListOf<GraphicsLayer>()
        private fun level(index: Int): GraphicsLayer {
            while (levels.size <= index) levels += graphics.createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
            }
            return levels[index]
        }
        fun DrawScope.record(extent: IntSize, minimum: Int, draw: DrawScope.() -> Unit): GraphicsLayer {
            var dimensions = materialReductionExtent(extent)
            var previous = level(0)
            previous.record(Density(density, fontScale), layoutDirection, dimensions, draw)
            var index = 1
            while (dimensions.width > minimum || dimensions.height > minimum) {
                val next = IntSize((dimensions.width / 2).coerceAtLeast(minimum).coerceAtMost(dimensions.width),
                    (dimensions.height / 2).coerceAtLeast(minimum).coerceAtMost(dimensions.height))
                val input = previous
                val before = dimensions
                previous = level(index++)
                previous.record(size = next) {
                    scale(next.width.toFloat() / before.width, next.height.toFloat() / before.height, Offset.Zero) {
                        drawLayer(input)
                    }
                }
                dimensions = next
            }
            return previous
        }
        fun release() { levels.forEach(graphics::releaseGraphicsLayer); levels.clear() }
    }
}

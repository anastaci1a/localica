package io.github.anastaci1a.localica

import android.util.Log
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** Expands the original 2-body proof without relaxing its declared 5/255 budget.
 * Fixed weights include a non-native rounding-screen hypothesis, not a native defect claim. */
class MultiShadowQuantizationTest {
    @get:Rule val compose = createComposeRule()
    private data class Case(val name: String, val bodies: List<Pair<RoundRect, Float>>)
    private val extent = Size(360f, 210f)
    private val cases = listOf(
        "ordinary-3" to listOf(1f, .5f, .25f),
        "ordinary-4" to listOf(1f, .75f, .5f, .25f),
        "screen-3" to listOf(1f, .18126415f, .43256444f),
        "screen-4" to listOf(1f, .18126415f, .43256444f, .21986524f),
        "rounded-4" to listOf(1f, .18f, .43f, .22f),
    ).flatMap { (name, weights) -> listOf(false, true).map { offset ->
        Case("$name-${if (offset) "offset" else "coincident"}", weights.mapIndexed { index, weight ->
            val x = if (offset) 20f + index * 70f else 120f
            RoundRect(x, 50f, x + 100f, 130f, CornerRadius(24f)) to weight
        })
    } }
    private val uniform = cases.filter { it.name.startsWith("ordinary-") }
        .map { Case("uniform-${it.name}", it.bodies.map { (shape, _) -> shape to .5f }) }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.oldUniform(bodies: List<Pair<RoundRect, Float>>) {
        val cache = GlassShadowGeometry()
        cache.update(bodies.map { it.first }, 4f + .6f * 28f, 2f)
        clipPath(cache.body, ClipOp.Difference) {
            cache.rings.forEachIndexed { index, path ->
                val t = (16 - index) / 16f
                val alpha = bodies.first().second * .07f * (1f - t).pow(2)
                if (alpha > 0) drawPath(path, Color.Black.copy(alpha = alpha))
            }
        }
    }

    private fun render(bodies: List<Pair<RoundRect, Float>>, retained: RetainedGlassShadow? = null,
        baseline: Boolean = false): PixelMap {
        val image = ImageBitmap(360, 210)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), extent) {
            drawRect(Color.White)
            when {
                baseline -> oldUniform(bodies)
                retained != null -> with(retained) { draw(bodies, 1f, .6f) }
                else -> glassShadows(bodies, 1f, .6f, GlassShadowGeometry())
            }
        }
        return image.toPixelMap()
    }

    private data class Difference(val maximum: Float, val x: Int, val y: Int)
    private fun difference(actual: PixelMap, singles: List<PixelMap>, markerHeight: Int = 0): Difference {
        var maximum = 0f; var worstX = -1; var worstY = -1
        // All cases share bottom130. Sample below every body's global interior cutout.
        // Independent singles and union use identical backing, density and origin.
        for (y in 132 until 175) for (x in 10 until 350) {
            val expected = singles.minOf { it[x, y + markerHeight].red }
            val error = abs(actual[x, y + markerHeight].red - expected)
            if (error > maximum) { maximum = error; worstX = x; worstY = y }
        }
        return Difference(maximum, worstX, worstY)
    }
    private fun report(path: String, case: Case, value: Difference, failures: MutableList<String>) {
        Log.i("MultiShadowQuantization", "path=$path case=${case.name} maxError=${value.maximum} point=${value.x},${value.y}")
        if (value.maximum > 5f / 255f) failures += "$path/${case.name}:${value.maximum}"
    }

    @Test fun softwareAndRetainedThreeAndFourBodyFieldsRespectTheExistingBudget() = runBlocking {
        val failures = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        suspend fun settled(bodies: List<Pair<RoundRect, Float>>): PixelMap {
            val retained = RetainedGlassShadow(scope)
            withContext(Dispatchers.Main) { render(bodies, retained) }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { retained.rasterizations } == 0) delay(10) }
            return withContext(Dispatchers.Main) { render(bodies, retained) }
        }
        try {
            for (case in cases) {
                val directSingles = case.bodies.map { render(listOf(it)) }
                val direct = render(case.bodies)
                report("direct", case, difference(direct, directSingles), failures)
                val retainedSingles = case.bodies.map { settled(listOf(it)) }
                val retained = settled(case.bodies)
                report("retained", case, difference(retained, retainedSingles), failures)
                report("direct-v-retained", case, difference(direct, listOf(retained)), failures)
            }
            for (case in uniform) {
                val baseline = render(case.bodies, baseline = true); val actual = render(case.bodies)
                var maximum = 0f
                for (y in 0 until actual.height) for (x in 0 until actual.width)
                    maximum = maxOf(maximum, abs(actual[x, y].red - baseline[x, y].red))
                Log.i("MultiShadowQuantization", "path=uniform-baseline case=${case.name} maxError=$maximum")
                if (maximum != 0f) failures += "uniform/${case.name}:$maximum"
            }
        } finally { scope.cancel() }
        assertTrue("Multi-body quantization budget: $failures", failures.isEmpty())
    }

    @Test fun actualWindowThreeAndFourBodyFieldsRespectTheExistingBudget() {
        data class Scene(val bodies: List<Pair<RoundRect, Float>>, val baseline: Boolean = false)
        val scenes = cases.flatMap { case -> case.bodies.map { Scene(listOf(it)) } + Scene(case.bodies) } +
            uniform.flatMap { listOf(Scene(it.bodies, baseline = true), Scene(it.bodies)) }
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val cache = remember { GlassShadowGeometry() }
                Column(Modifier.background(Color.White).testTag("multi-weighted-window")) {
                    // Consecutive stages differ; wrap before the existing marker's channel exceeds1.
                    PresentationMarker { stage % 30 + 1 }
                    Canvas(Modifier.size(360.dp, 210.dp)) {
                        val scene = scenes[stage]
                        if (scene.baseline) oldUniform(scene.bodies) else glassShadows(scene.bodies, 1f, .6f, cache)
                    }
                }
            }
        }
        val captures = mutableListOf<PixelMap>()
        for (index in scenes.indices) {
            compose.runOnIdle { stage = index }
            captures += capturePresentedPopupPixels(compose, compose.onNodeWithTag("multi-weighted-window"), index % 30 + 1) { view }
        }
        val failures = mutableListOf<String>(); var start = 0
        for (case in cases) {
            val end = start + case.bodies.size
            report("window", case, difference(captures[end], captures.subList(start, end), markerHeight = 8), failures)
            start = end + 1
        }
        for (case in uniform) {
            val expected = captures[start++]; val actual = captures[start++]
            var maximum = 0f
            for (y in 8 until actual.height) for (x in 0 until actual.width)
                maximum = maxOf(maximum, abs(actual[x, y].red - expected[x, y].red))
            Log.i("MultiShadowQuantization", "path=window-uniform-baseline case=${case.name} maxError=$maximum")
            if (maximum != 0f) failures += "window-uniform/${case.name}:$maximum"
        }
        assertTrue("Window multi-body quantization budget: $failures", failures.isEmpty())
    }
}

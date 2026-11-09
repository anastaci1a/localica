package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native pixel witnesses, separate from timed app captures. Every original footprint is retained. */
class NoneReductionWitnessTest {
    @get:Rule val compose = localicaComposeRule()

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun rawAndStyledFullFootprintWitnessesCompareCurrentEightAndSixteenLive() {
        var fixture by mutableIntStateOf(0)
        var phase by mutableFloatStateOf(0f)
        val outputs = mutableMapOf<Pair<Int, Boolean>, GraphicsLayer>()
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            LocalicaTheme {
                Row {
                    for (side in listOf(0, 8, 16)) Column {
                        for (styled in listOf(false, true)) {
                            val reduction = rememberMaterialAreaReduction()
                            val output = rememberGraphicsLayer()
                            val renderer = remember { MaterialAverageRenderer(true) }
                            SideEffect { outputs[side to styled] = output }
                            Canvas(Modifier.size(80.dp).testTag("witness-$side-$styled")) {
                                // Odd, fractional footprint; all arms sample precisely this complete region.
                                val bounds = Size(257.25f, 129.5f)
                                val paint: DrawScope.() -> Unit = {
                                    when (fixture) {
                                        0 -> drawRect(Color(.2f + phase, .4f, .7f), size = bounds)
                                        1 -> drawRect(Brush.horizontalGradient(listOf(Color.Red, Color.Blue),
                                            startX = phase * bounds.width, endX = bounds.width), size = bounds)
                                        2 -> {
                                            drawRect(Color.Black, size = bounds)
                                            for (y in 0..130 step 2) for (x in 0..258 step 2)
                                                if ((x / 2 + y / 2) % 2 == 0) drawRect(Color.White,
                                                    Offset(x + phase, y.toFloat()), Size(2f, 2f))
                                        }
                                        3 -> {
                                            drawRect(Color.Blue, size = bounds)
                                            for (x in 0..257 step 7) drawRect(Color.White,
                                                Offset(x + phase, 0f), Size(1f, bounds.height))
                                        }
                                        else -> {
                                            drawRect(Color.Red.copy(alpha = .25f), size = bounds)
                                            drawRect(Color.Green.copy(alpha = .75f), Offset(phase, 0f),
                                                Size(bounds.width / 2, bounds.height))
                                        }
                                    }
                                }
                                val result = with(reduction) {
                                    if (side == 0) recordFullBounds(bounds, draw = paint)
                                    else record(IntSize(side, side)) {
                                        scale(side / bounds.width, side / bounds.height, Offset.Zero) {
                                            clipRect(right = bounds.width, bottom = bounds.height, block = paint)
                                        }
                                    }
                                }
                                output.renderEffect = renderer.effect(if (styled)
                                    MaterialAverageStyle(.15f, GlassTone()) else MaterialAverageStyle(0f, GlassTone()))
                                output.record(size = IntSize(1, 1)) { drawLayer(result) }
                                scale(size.width, size.height, Offset.Zero) { drawLayer(output) }
                            }
                        }
                    }
                }
            }
        }
        val rows = mutableListOf<String>()
        for (pattern in 0..4) for (offset in listOf(0f, .375f)) {
            compose.runOnIdle { fixture = pattern; phase = offset }
            compose.waitForIdle()
            val copied = CompletableDeferred<Map<Pair<Int, Boolean>, Color>>()
            compose.runOnIdle { scope.launch {
                try {
                    copied.complete(outputs.mapValues { (_, layer) -> layer.toImageBitmap().toPixelMap()[0, 0] })
                } catch (failure: Throwable) { copied.completeExceptionally(failure) }
            } }
            compose.waitUntil(15_000) { copied.isCompleted }
            val native = runBlocking { copied.await() }
            for (styled in listOf(false, true)) {
                val colors = listOf(0, 8, 16).map { side ->
                    requireNotNull(native[side to styled])
                }
                rows += "{\"pattern\":$pattern,\"phase\":$offset,\"styled\":$styled,\"rgba\":[" +
                    colors.joinToString(",") { "[${it.red * it.alpha},${it.green * it.alpha},${it.blue * it.alpha},${it.alpha}]" } + "]}"
                if (pattern == 0) for (candidate in colors.drop(1)) {
                    assertEquals(colors[0].red, candidate.red, 2f / 255)
                    assertEquals(colors[0].green, candidate.green, 2f / 255)
                    assertEquals(colors[0].blue, candidate.blue, 2f / 255)
                }
            }
        }
        val dir = nativeEvidenceDirectory("none-reduction-witness",
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir("none-reduction-witness"))
        dir.mkdirs()
        java.io.File(dir, "native-colours-api${Build.VERSION.SDK_INT}.json").writeText("[\n" + rows.joinToString(",\n") + "\n]\n")
    }
}

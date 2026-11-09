package io.github.anastaci1a.localica.ui

import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** NEVER MERGE. Explicit hardware colour oracle route; never present in timing or ordinary visual captures. */
@RequiresApi(33)
@Composable internal fun NoneColourWitness() {
    var pattern by remember { mutableIntStateOf(0) }
    var phase by remember { mutableFloatStateOf(0f) }
    var opaque by remember { mutableStateOf(false) }
    var epoch by remember { mutableIntStateOf(0) }
    var complete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val outputs = remember { mutableMapOf<Pair<Int, Boolean>, GraphicsLayer>() }
    val drawn = remember { mutableMapOf<Pair<Int, Boolean>, Int>() }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row {
            for (side in listOf(0, 8, 16, 64)) Column {
                for (styled in listOf(false, true)) {
                    val reduction = rememberMaterialAreaReduction()
                    val output = rememberGraphicsLayer()
                    val renderer = remember { MaterialAverageRenderer(reduced = side != 64, uniformOpaque = true) }
                    SideEffect { outputs[side to styled] = output }
                    Canvas(Modifier.size(80.dp)) {
                        val bounds = Size(257.25f, 129.5f)
                        val paint: DrawScope.() -> Unit = {
                            when (pattern) {
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
                        val tile = if (side == 64) 8 else side
                        val result = with(reduction) {
                            if (side == 0) recordFullBounds(bounds, draw = paint)
                            else record(IntSize(tile, tile), minimum = if (side == 64) 8 else 1) {
                                scale(tile / bounds.width, tile / bounds.height, Offset.Zero) {
                                    clipRect(right = bounds.width, bottom = bounds.height, block = paint)
                                }
                            }
                        }
                        output.renderEffect = renderer.effect(if (styled)
                            MaterialAverageStyle(.15f, GlassTone(), opaque) else MaterialAverageStyle(0f, GlassTone()))
                        output.record(size = if (side == 64) IntSize(8, 8) else IntSize(1, 1)) { drawLayer(result) }
                        scale(size.width / output.size.width, size.height / output.size.height, Offset.Zero) { drawLayer(output) }
                        drawn[side to styled] = epoch
                    }
                }
            }
        }
        Text(if (complete) "Colour witness complete" else "Collecting native colours")
    }
    LaunchedEffect(Unit) {
        val rows = JSONArray()
        try {
            for (fixture in 0..4) for (offset in listOf(0f, .375f)) for (panel in listOf(false, true)) {
                pattern = fixture; phase = offset; opaque = panel; epoch++
                repeat(2) { withFrameNanos { } }
                while (drawn.size != 8 || drawn.values.any { it != epoch }) withFrameNanos { }
                val native = outputs.mapValues { (_, layer) -> layer.toImageBitmap().toPixelMap()[0, 0] }
                for (styled in listOf(false, true)) {
                    val rgba = JSONArray()
                    for (side in listOf(0, 8, 16, 64)) {
                        val color = requireNotNull(native[side to styled])
                        rgba.put(JSONArray(listOf(color.red * color.alpha, color.green * color.alpha,
                            color.blue * color.alpha, color.alpha)))
                    }
                    rows.put(JSONObject().put("pattern", fixture).put("phase", offset).put("styled", styled)
                        .put("opaque", panel).put("epoch", epoch).put("rgba", rgba))
                }
            }
            val dir = requireNotNull(context.getExternalFilesDir("none-colour-witness"))
            dir.mkdirs()
            java.io.File(dir, "native-colours-a35.json").writeText(JSONObject()
                .put("arms", JSONArray(listOf("current", "grid8", "grid16", "gather8")))
                .put("footprint", JSONArray(listOf(257.25, 129.5))).put("rows", rows).toString(2))
            android.util.Log.i("NoneColourWitness", "complete rows=${rows.length()}")
            complete = true
        } catch (failure: Throwable) { android.util.Log.e("NoneColourWitness", "failed", failure) }
    }
}

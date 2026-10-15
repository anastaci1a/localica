package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Coordinate-coded source tests the real adapters against the shared inverse map.
 * Blue identifies the presented source frame independently of red/green lookup.
 * No blur, tone, reflection or shadow participates in this optical-only probe. */
class LensMeshSamplingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultInverseMapPreservesCardinalSamplingAcrossPixelAlignments() {
        val shapes = listOf(LensGeometry(Rect(0f, 0f, 161f, 161f), 80.5f),
            LensGeometry(Rect(0f, 0f, 241f, 121f), 60.5f),
            LensGeometry(Rect(0f, 0f, 241f, 181f), 28f),
            LensGeometry(Rect(0f, 0f, 121f, 241f), 60.5f),
            LensGeometry(Rect(0f, 0f, 160f, 160f), 80f),
            LensGeometry(Rect(0f, 0f, 240f, 120f), 60f),
            LensGeometry(Rect(0f, 0f, 120f, 240f), 60f))
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            val shape = shapes[stage / 2]
            val power = (stage % 2).toFloat()
            val width = shape.bounds.width.toInt(); val height = shape.bounds.height.toInt()
            val source = remember(stage) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    val pixels = IntArray(width * height) { i ->
                        val r = ((i % width + .5f) * 255 / width).roundToInt()
                        val g = ((i / width + .5f) * 255 / height).roundToInt()
                        android.graphics.Color.rgb(r, g, 32 + stage * 16)
                    }
                    setPixels(pixels, 0, width, 0, 0, width, height)
                }.asImageBitmap()
            }
            val mesh = remember { LensMeshRenderer() }
            val renderer = remember { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null }
            val density = LocalDensity.current.density
            var modifier = Modifier.size((width / density).dp, (height / density).dp).testTag("mesh-grid")
            if (Build.VERSION.SDK_INT >= 33) modifier = modifier.graphicsLayer {
                renderEffect = requireNotNull(renderer).effect(shape, 0f, 1.35f, power = power,
                    illumination = 0f)
            }
            Canvas(modifier) {
                if (Build.VERSION.SDK_INT >= 33) drawImage(source)
                else with(mesh) { draw(shape, source, IntSize(width, height), Offset.Zero,
                    1.35f, power = power, lighting = false) }
            }
        }
        val failures = mutableListOf<String>()
        for (next in shapes.indices.flatMap { listOf(it * 2, it * 2 + 1) }) {
            compose.runOnIdle { stage = next }
            lateinit var pixels: androidx.compose.ui.graphics.PixelMap
            compose.waitUntil(5000) {
                pixels = capturePopupPixels(compose, compose.onNodeWithTag("mesh-grid")) { view }
                abs(pixels[pixels.width / 2, pixels.height / 2].blue - (32 + next * 16) / 255f) < .005f
            }
            val shape = shapes[next / 2]
            val power = (next % 2).toFloat()
            var maximumError = 0f
            val xs = ((3 until pixels.width - 3 step 7).toList() + listOf((pixels.width - 1) / 2, pixels.width / 2)).distinct()
            val ys = ((3 until pixels.height - 3 step 7).toList() + listOf((pixels.height - 1) / 2, pixels.height / 2)).distinct()
            for (y in ys) for (x in xs) {
                val p = Offset(x + .5f, y + .5f)
                if (shape.field(p).distance < 3f) continue
                val offset = lensRay(shape.field(p), shape.span, 1.35f).offset
                val expected = p + offset * power
                val actual = pixels[x, y]
                // Pixel-coordinate residual includes one 8-bit quantization
                // step plus 1.5px for the existing interpolated legacy mesh.
                val errorX = abs(actual.red * pixels.width - expected.x)
                val errorY = abs(actual.green * pixels.height - expected.y)
                maximumError = maxOf(maximumError, errorX, errorY)
                if (errorX > 1.5f + pixels.width / 255f || errorY > 1.5f + pixels.height / 255f)
                    failures += "stage=$next p=$p expected=$expected actual=$actual error=($errorX,$errorY)"
            }
            val center = pixels[pixels.width / 2, pixels.height / 2]
            android.util.Log.i("LensMeshSampling", "api=${Build.VERSION.SDK_INT} stage=$next maxCoordinateErrorPx=$maximumError center=$center")
        }
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }
}

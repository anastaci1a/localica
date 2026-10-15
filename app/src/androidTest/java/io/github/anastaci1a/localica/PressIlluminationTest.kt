package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
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

/** Static-only profile proof. Declared before device measurement: RGB3/255 AGSL,
 *5/255 legacy; inferred alpha3/255; inverse-coordinate1.5px AGSL/2px legacy (per axis).
 * At maximum encoded span284px, two8-bit half-steps under inverse gain give
 *284/510*(1+1/(1-.16))<1.22px. AGSL adds .2px half-precision allowance;
 * legacy adds .6px mesh allowance. Both fit the declared thresholds.
 * The original boundary/default optical probes remain unchanged. */
class PressIlluminationTest {
    @get:Rule val compose = createComposeRule()
    private data class Scene(val shape: LensGeometry, val press: PressIllumination?,
        val alpha: Int = 255, val whiteBackground: Boolean = false, val grid: Boolean = false,
        val sourceOffset: Offset = Offset.Zero)
    private lateinit var view: View
    private var top = 0
    private val glyph = Rect(20f, 60f, 32f, 70f)
    private val tone = GlassTone(trim = .25f, lightInk = true)

    @Composable private fun DrawScene(scene: Scene, stage: Int) {
        view = LocalView.current
        val d = LocalDensity.current.density
        val shape = scene.shape
        val w = shape.bounds.right.toInt(); val h = shape.bounds.bottom.toInt()
        val sourceW = w + 2 * scene.sourceOffset.x.toInt(); val sourceH = h + 2 * scene.sourceOffset.y.toInt()
        val foregroundY = sourceH + 2f
        val mesh = remember { LensMeshRenderer() }
        val renderer = remember { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null }
        val source = remember(scene) {
            Bitmap.createBitmap(sourceW, sourceH, Bitmap.Config.ARGB_8888).apply {
                val colors = IntArray(sourceW * sourceH) { i ->
                    val raw = if (scene.grid) floatArrayOf((i % sourceW + .5f) / sourceW, (i / sourceW + .5f) / sourceH, .25f)
                        else floatArrayOf(64 / 255f, 128 / 255f, 192 / 255f)
                    val c = raw.map { if (Build.VERSION.SDK_INT < 33 && !scene.grid) tone.channel(it) else it }
                    android.graphics.Color.argb(scene.alpha, (c[0] * 255).roundToInt(), (c[1] * 255).roundToInt(), (c[2] * 255).roundToInt())
                }
                setPixels(colors, 0, sourceW, 0, 0, sourceW, sourceH)
            }.asImageBitmap()
        }
        val actualTone = if (scene.grid) GlassTone() else tone
        val optics = if (scene.grid) BoundaryOptics() else null
        val power = if (scene.grid) 1f else 0f
        top = with(LocalDensity.current) { 8.dp.roundToPx() }
        Column(Modifier.background(if (scene.whiteBackground) Color.White else Color.Black).testTag("press-scene")) {
            PresentationMarker { stage }
            var modifier = Modifier.size(((sourceW + 2) / d).dp, ((sourceH + h + 3) / d).dp)
            if (Build.VERSION.SDK_INT >= 33) modifier = modifier.graphicsLayer {
                renderEffect = requireNotNull(renderer).effect(shape, 0f, 1.35f, power = power,
                    illumination = 0f, tone = actualTone, boundaryProfile = optics, pressProfile = scene.press,
                    atlas = LensAtlas(Size(w.toFloat(), h.toFloat()), Size(sourceW.toFloat(), sourceH.toFloat()), scene.sourceOffset, foregroundY))
            }
            Canvas(modifier) {
                if (Build.VERSION.SDK_INT >= 33) {
                    drawImage(source, Offset(1f, 1f))
                    drawRect(Color.Magenta, Offset(glyph.left, glyph.top + foregroundY), glyph.size)
                } else {
                    with(mesh) { draw(shape, source, IntSize(sourceW, sourceH), scene.sourceOffset, 1.35f,
                        power = power, lighting = false, tone = actualTone, boundaryProfile = optics, pressProfile = scene.press) }
                    drawRect(Color.Magenta, glyph.topLeft, glyph.size)
                }
            }
        }
    }

    @Test fun staticLiftPreservesAlphaForegroundAndZeroBaseline() {
        val shape = LensGeometry(Rect(0f, 0f, 161f, 121f), 32f)
        val profiles = listOf(null, PressIllumination(0f, shape.bounds.center, 24f),
            PressIllumination(.5f, shape.bounds.center, 24f), PressIllumination(1f, shape.bounds.center, 24f),
            PressIllumination(1f, shape.bounds.center, 1f), PressIllumination(1f, Offset(50.5f, 40.5f), 1f),
            PressIllumination(1f, Offset(-100f, -100f), 400f))
        val scenes = listOf(128, 255).flatMap { alpha -> listOf(false, true).flatMap { white ->
            profiles.map { Scene(shape, it, alpha, white) }
        } }
        var index by mutableIntStateOf(0)
        compose.setContent { DrawScene(scenes[index], index + 1) }
        val failures = mutableListOf<String>()
        var baseline: PixelMap? = null
        val captured = mutableMapOf<Pair<Int, Int>, PixelMap>()
        for (i in scenes.indices) {
            compose.runOnIdle { index = i }
            val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("press-scene"), i + 1) { view }
            val scene = scenes[i]; val profile = scene.press?.resolve(shape)
            val alpha = scene.alpha / 255f; val background = if (scene.whiteBackground) 1f else 0f
            var maximum = 0f; var zeroError = 0f; var alphaError = 0f; var outsideError = 0f
            if (i % profiles.size == 0) baseline = pixels
            for (y in 3 until 118) for (x in 3 until 158) {
                val point = Offset(x + .5f, y + .5f)
                val actual = pixels[x, top + y]
                if (i % profiles.size == 1) zeroError = maxOf(zeroError, abs(actual.red - baseline!![x, top + y].red),
                    abs(actual.green - baseline!![x, top + y].green), abs(actual.blue - baseline!![x, top + y].blue))
                if (shape.field(point).distance < -3f) {
                    outsideError = maxOf(outsideError, abs(actual.red - baseline!![x, top + y].red))
                    continue
                }
                if (shape.field(point).distance < 3f) continue
                if (glyph.contains(point)) {
                    maximum = maxOf(maximum, abs(actual.red - 1f), abs(actual.green), abs(actual.blue - 1f))
                    continue
                }
                val expected = listOf(64, 128, 192).map { raw ->
                    val mapped = tone.channel(raw / 255f)
                    (profile?.channel(mapped, tone.channel(1f), point) ?: mapped) * alpha + background * (1f - alpha)
                }
                maximum = maxOf(maximum, abs(actual.red - expected[0]), abs(actual.green - expected[1]), abs(actual.blue - expected[2]))
                if (scene.whiteBackground) {
                    val black = captured[scene.alpha to (i % profiles.size)]!![x, top + y]
                    alphaError = maxOf(alphaError, abs(actual.red - black.red - (1f - alpha)),
                        abs(actual.green - black.green - (1f - alpha)), abs(actual.blue - black.blue - (1f - alpha)))
                }
            }
            if (!scene.whiteBackground) captured[scene.alpha to (i % profiles.size)] = pixels
            val limit = (if (Build.VERSION.SDK_INT >= 33) 3f else 5f) / 255f
            val evidence = "api=${Build.VERSION.SDK_INT} stage=$i rgb=$maximum alpha=$alphaError zero=$zeroError outside=$outsideError"
            android.util.Log.i("PressIllumination", evidence)
            if (maximum > limit || alphaError > 3f / 255f || zeroError != 0f || outsideError != 0f) failures += evidence
        }
        assertTrue("Static illumination invariants: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun illuminationKeepsTheInverseCoordinateMap() {
        val shapes = listOf(LensGeometry(Rect(0f, 0f, 161f, 161f), 80.5f),
            LensGeometry(Rect(0f, 0f, 241f, 121f), 60.5f), LensGeometry(Rect(0f, 0f, 241f, 181f), 28f), LensGeometry(Rect(17f, 11f, 258f, 192f), 28f))
        val scenes = shapes.flatMap { shape -> listOf(0f, 1f).map { amount ->
            Scene(shape, PressIllumination(amount, shape.bounds.center, 35f), grid = true,
                sourceOffset = if (shape.bounds.left > 0f) Offset(13f, 9f) else Offset.Zero)
        } }
        var index by mutableIntStateOf(0)
        compose.setContent { DrawScene(scenes[index], index + 1) }
        val failures = mutableListOf<String>()
        for (i in scenes.indices) {
            compose.runOnIdle { index = i }
            val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("press-scene"), i + 1) { view }
            val scene = scenes[i]; val shape = scene.shape; val profile = scene.press!!.resolve(shape)
            var maximum = 0f
            for (y in shape.bounds.top.toInt() + 3 until shape.bounds.bottom.toInt() - 3 step 7) for (x in shape.bounds.left.toInt() + 3 until shape.bounds.right.toInt() - 3 step 7) {
                val p = Offset(x + .5f, y + .5f)
                if (shape.field(p).distance < 3f || glyph.inflate(1f).contains(p)) continue
                val expected = p + BoundaryOptics().offset(shape, p) + scene.sourceOffset
                val actual = pixels[x, top + y]; val weight = profile.weight(p)
                val sourceX = (actual.red - weight) / (1f - weight) * (shape.bounds.right + scene.sourceOffset.x * 2f)
                val sourceY = (actual.green - weight) / (1f - weight) * (shape.bounds.bottom + scene.sourceOffset.y * 2f)
                maximum = maxOf(maximum, abs(sourceX - expected.x), abs(sourceY - expected.y))
            }
            android.util.Log.i("PressIllumination", "api=${Build.VERSION.SDK_INT} coordinateStage=$i maxErrorPx=$maximum")
            if (maximum > if (Build.VERSION.SDK_INT >= 33) 1.5f else 2f) failures += "$i:$maximum"
        }
        assertTrue("Unchanged inverse lookup: ${failures.joinToString()}", failures.isEmpty())
    }
}

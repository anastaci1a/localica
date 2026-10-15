package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.system.measureNanoTime

/** Native raster checks, including the direct path's 8-bit accumulation.
 * Declare a 5/255 per-channel quantization budget before device measurement. */
class ShadowUnionRasterTest {
    private val first = RoundRect(40f, 50f, 180f, 130f, CornerRadius(24f))
    private val second = RoundRect(165f, 50f, 305f, 130f, CornerRadius(24f))
    private val extent = Size(360f, 210f)

    private fun render(bodies: List<Pair<RoundRect, Float>>, baseline: Boolean = false,
        retained: RetainedGlassShadow? = null): PixelMap {
        val image = ImageBitmap(extent.width.toInt(), extent.height.toInt())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), extent) {
            drawRect(Color.White)
            if (retained != null) with(retained) { draw(bodies, 1f, .6f) }
            else if (!baseline) glassShadows(bodies, 1f, .6f, GlassShadowGeometry())
            else {
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
        }
        return image.toPixelMap()
    }

    @Test fun equalOpacityKeepsTheExistingRasterExactly() {
        for (opacity in listOf(.05f, .5f, 1f)) {
            val bodies = listOf(first to opacity, second to opacity)
            val old = render(bodies, baseline = true); val actual = render(bodies)
            for (y in 0 until actual.height) for (x in 0 until actual.width)
                assertEquals("opacity=$opacity point=$x,$y", old[x, y], actual[x, y])
        }
    }

    @Test fun overlappingMixedShadowsStayAtTheMaximumIndividualField() {
        val failures = mutableListOf<String>()
        for (opacity in listOf(.05f, .5f, .99999994f)) {
            val a = render(listOf(first to opacity)); val b = render(listOf(second to 1f))
            val actual = render(listOf(first to opacity, second to 1f))
            var maximum = 0f
            // Below both bodies: exclude the independently specified interior cutout.
            for (y in 132 until 175) for (x in 10 until 340) {
                val expected = minOf(a[x, y].red, b[x, y].red)
                maximum = maxOf(maximum, abs(actual[x, y].red - expected))
            }
            Log.i("ShadowUnionRaster", "opacity=$opacity maximumFieldError=$maximum")
            if (maximum > 5f / 255f) failures += "$opacity:$maximum"
        }
        assertTrue("Weighted field raster error: $failures", failures.isEmpty())
    }

    @Test fun retainedAndDirectMixedFieldsKeepTheSameVisibleOpacity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val cache = RetainedGlassShadow(scope)
            val bodies = listOf(first to .5f, second to 1f)
            val direct = withContext(Dispatchers.Main) { render(bodies, retained = cache) }
            withTimeout(5000) {
                while (withContext(Dispatchers.Main) { cache.rasterizations } == 0) delay(10)
            }
            val settled = withContext(Dispatchers.Main) { render(bodies, retained = cache) }
            var maximum = 0f
            for (y in 132 until 175) for (x in 10 until 340)
                maximum = maxOf(maximum, abs(direct[x, y].red - settled[x, y].red))
            Log.i("ShadowUnionRaster", "directRetainedError=$maximum")
            assertTrue("Direct/retained quantization error=$maximum", maximum <= 5f / 255f)
        } finally { scope.cancel() }
    }

    @Test fun retainedGeometryInvalidatesForEveryWeightedFieldInput() {
        val cache = GlassShadowGeometry()
        cache.update(listOf(first, second), 20.8f, 2f)
        val original = cache.weightedRings(listOf(.5f, 1f), 1f)
        assertSame(original, cache.weightedRings(listOf(.5f, 1f), 1f))
        val changedOpacity = cache.weightedRings(listOf(.6f, 1f), 1f)
        assertNotSame(original, changedOpacity)
        val changedStrength = cache.weightedRings(listOf(.6f, 1f), .5f)
        assertNotSame(changedOpacity, changedStrength)
        cache.update(listOf(first, second), 22f, 2f)
        val changedReach = cache.weightedRings(listOf(.6f, 1f), .5f)
        assertNotSame(changedStrength, changedReach)
        cache.update(listOf(first, second), 22f, 3f)
        val changedShift = cache.weightedRings(listOf(.6f, 1f), .5f)
        assertNotSame(changedReach, changedShift)
        cache.update(listOf(second, first), 22f, 3f)
        assertNotSame(changedShift, cache.weightedRings(listOf(.6f, 1f), .5f))
        assertSame(cache.rings, cache.rings)
    }

    @Test fun reportPathWorkForContinuouslyChangingTwoToFourBodyFades() {
        for (count in 2..4) for (mixed in listOf(false, true)) {
            val cache = GlassShadowGeometry()
            var paths = 0; var contours = 0
            var elapsed = 0L
            repeat(120) { frame ->
                val bodies = List(count) { i -> RoundRect(20f + i * 65f, 30f + frame % 4,
                    100f + i * 65f, 90f + frame % 4, CornerRadius(20f)) }
                val weights = List(count) { i -> if (!mixed || i == 0) 1f else .01f + .98f * ((frame + i * 17) % 120) / 119f }
                elapsed += measureNanoTime {
                    cache.update(bodies, 20.8f, 2f)
                    if (mixed) cache.weightedRings(weights, 1f) else cache.rings
                }
                if (mixed) {
                    val bands = weightedShadowBands(weights, 1f)
                    paths += bands.size
                    contours += bands.sumOf { it.ringIndices.count { ring -> ring >= 0 } }
                } else { paths += 16; contours += count * 16 }
            }
            val image = ImageBitmap(360, 210)
            val canvas = Canvas(image)
            val rasterCache = GlassShadowGeometry()
            val rasterElapsed = measureNanoTime {
                repeat(32) { frame ->
                    val bodies = List(count) { i -> RoundRect(20f + i * 65f, 30f + frame % 4,
                        100f + i * 65f, 90f + frame % 4, CornerRadius(20f)) to
                        (if (!mixed || i == 0) 1f else .01f + .98f * ((frame + i * 17) % 32) / 31f) }
                    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, extent) {
                        drawRect(Color.White, blendMode = BlendMode.Src)
                        glassShadows(bodies, 1f, .6f, rasterCache)
                    }
                }
            }
            Log.i("ShadowUnionRaster", "bodies=$count mixed=$mixed frames=120 pathBuildMs=${elapsed / 1e6} paths=$paths contours=$contours softwareRaster32FramesMs=${rasterElapsed / 1e6}")
        }
    }
}

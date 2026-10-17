package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class GlassSilhouetteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sampledNormalsRemainContinuousAcrossTexelBoundaries() {
        val frame = GlassSilhouetteFrame(splitPillGeometry(Size(120f, 60f), .3f))
        val field = LensDistanceMap.create(frame.path)
        for (y in 2 until field.bitmap.height - 2 step 3) for (x in 2 until field.bitmap.width - 2)
            for (fraction in listOf(0f, .5f)) {
            val boundary = field.origin + Offset((x + fraction) / field.scale, (y + .3f) / field.scale)
            val a = field.sample(boundary - Offset(.0001f, 0f))
            val b = field.sample(boundary + Offset(.0001f, 0f))
            assertTrue("A sampling boundary must not jump its refracting normal", (a.inward - b.inward).getDistance() < .001f)
        }
    }

    @Test fun innerNeckUpdatesAndReversesWithoutAnOuterSizeChange() {
        var progress by mutableFloatStateOf(0f)
        compose.setContent { LocalicaTheme {
            val silhouette = rememberSplitPillSilhouette(progress = { progress })
            Box(Modifier.size(120.dp, 60.dp).background(Color.Blue).testTag("silhouette")) {
                LiquidGlassSurface(Modifier.fillMaxSize(), source = null, shadow = false,
                    pressFeedback = false, silhouette = silhouette) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        } }
        fun neckIsRed(): Boolean {
            val image = compose.onNodeWithTag("silhouette").captureToImage().toPixelMap()
            val pixel = image[image.width / 2 - 2, 2]
            return pixel.red > .8f && pixel.blue < .2f
        }
        assertTrue("The joined pill covers the top of its center", neckIsRed())
        compose.runOnIdle { progress = .45f }
        assertFalse("Rounding the inner corners exposes the backing without resizing", neckIsRed())
        compose.runOnIdle { progress = 0f }
        assertTrue("Rejoining restores the center without a layout change", neckIsRed())
    }

    @Test fun endpointOpticsStayAnalyticAndTheModernAdapterAcceptsTheSameFields() {
        val frames = listOf(0f, .15f, .25f, .35f, .5f, 1f).map { progress ->
            GlassSilhouetteFrame(splitPillGeometry(androidx.compose.ui.geometry.Size(
                120f + 10f * ((progress - .5f) * 2f).coerceIn(0f, 1f), 60f), progress))
        }
        assertNull(frames.first().field)
        assertNull(frames.last().field)
        assertTrue("The split control never allocates a sampled field", frames.all { it.field == null })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val renderer = LensRenderer()
            frames.forEach { frame -> renderer.effect(frame.geometry, 0f, 1.3f,
                map = frame.field, fieldWeight = frame.description.fieldWeight, circles = frame.description.circles,
                splitInner = frame.description.splitInner) }
        }
    }

    @Test fun renderedEndpointsAndTheirApproachDoNotReplaceTheOpticalSurface() {
        var phase by mutableIntStateOf(0)
        lateinit var backing: FrostedBackdrop
        compose.setContent { ClearGlassTheme {
            backing = rememberFrostedBackdrop(Unit)
            val progress = when (phase) { 2 -> .0001f; 3 -> .4999f; 4, 5 -> .5f; else -> 0f }
            val silhouette = rememberSplitPillSilhouette(progress = { progress })
            Box(Modifier.size(280.dp, 160.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) {
                    val tile = 13.dp.toPx()
                    val colors = listOf(Color(0xFF8BC7BD), Color(0xFFF0C06F), Color(0xFF7D91C9), Color(0xFFCD83A7))
                    for (y in 0..(size.height / tile).toInt()) for (x in 0..(size.width / tile).toInt()) {
                        drawRect(colors[(x + 3 * y) % colors.size], Offset(x * tile, y * tile), Size(tile, tile))
                    }
                }
                Box(Modifier.align(Alignment.Center).size(120.dp, 60.dp).testTag("optical-case")) {
                    if (phase == 5) Row {
                        repeat(2) { LiquidGlassSurface(Modifier.size(60.dp), backing, shadow = false, pressFeedback = false) }
                    } else LiquidGlassSurface(Modifier.fillMaxSize(), backing, shadow = false, pressFeedback = false,
                        silhouette = if (phase == 0) null else silhouette)
                }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backing.lensSnapshot != null }
        fun capture(index: Int): ImageBitmap {
            compose.runOnIdle { phase = index }
            return compose.onNodeWithTag("optical-case").captureToImage().also { bitmap ->
                val folder = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                java.io.File(folder, "split-optics-$index.png").outputStream().use {
                    bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        val images = (0..5).map(::capture)
        for ((a, b) in listOf(0 to 1, 1 to 2, 3 to 4, 4 to 5)) {
            val first = images[a].toPixelMap(); val second = images[b].toPixelMap()
            var error = 0.0
            for (y in 1 until first.height - 1) for (x in 1 until first.width - 1) {
                val p = first[x, y]; val q = second[x, y]
                error += (abs(p.red - q.red) + abs(p.green - q.green) + abs(p.blue - q.blue)) / 3
            }
            val mean = error / ((first.width - 2) * (first.height - 2))
            android.util.Log.i("GlassSplitOptics", "phases=$a->$b meanChannelDifference=$mean")
            val limit = if (a == 0) .002 else .005
            assertTrue("Optical handoff $a->$b must not replace the surface (mean=$mean, limit=$limit)", mean < limit)
        }
    }
}

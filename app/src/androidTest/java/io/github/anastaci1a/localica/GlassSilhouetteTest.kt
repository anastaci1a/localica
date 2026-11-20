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
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class GlassSilhouetteTest {
    private fun splitOptics(size: Size, p: Float) = splitPillOptics(splitPillBodies(size, p), p)
    @get:Rule val compose = createComposeRule()

    @Test fun curvedNormalsRemainContinuousAcrossFormerTexelBoundaries() {
        val lens = GlassSilhouetteFrame(splitOptics(Size(120f, 60f), .3f)).geometry
        for (step in 1..89) {
            val theta = step * kotlin.math.PI.toFloat() / 180f
            val point = Offset(30f - 26f * kotlin.math.cos(theta), 30f - 26f * kotlin.math.sin(theta))
            val a = lens.field(point - Offset(.0001f, 0f))
            val b = lens.field(point + Offset(.0001f, 0f))
            assertTrue("The exposed arc's normal must be continuous", (a.inward - b.inward).getDistance() < .001f)
        }
    }

    @Test fun innerNeckUpdatesAndReversesWithoutAnOuterSizeChange() {
        var progress by mutableFloatStateOf(0f)
        compose.setContent { EngineTheme {
            val pill = rememberSplitPill { progress }
            Box(Modifier.size(120.dp, 60.dp).then(pill.follow).background(Color.Blue).testTag("silhouette")) {
                LiquidGlassSurface(Modifier.fillMaxSize(), source = null, shadow = false,
                    pressFeedback = false, silhouette = pill.silhouette) {
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
            GlassSilhouetteFrame(splitOptics(androidx.compose.ui.geometry.Size(
                120f + 10f * ((progress - .5f) * 2f).coerceIn(0f, 1f), 60f), progress))
        }
        assertEquals(1, frames.first().geometry.bodies.size)
        assertEquals(2, frames.last().geometry.bodies.size)
        assertTrue(frames.all { frame -> frame.geometry.bodies.all { it.width > 0f && it.height > 0f } })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val renderer = LensRenderer()
            frames.forEach { frame -> renderer.effect(frame.geometry, 0f, 1.3f, 1f) }
        }
    }

    @Test fun renderedEndpointsAndTheirApproachDoNotReplaceTheOpticalSurface() {
        var phase by mutableIntStateOf(0)
        lateinit var backing: FrostedBackdrop
        compose.setContent { EngineTheme(clear = true) {
            backing = rememberFrostedBackdrop(Unit)
            val progress = when (phase) { 2 -> .0001f; 3 -> .4999f; 4, 5 -> .5f; else -> 0f }
            val pill = rememberSplitPill { progress }
            Box(Modifier.size(280.dp, 160.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) {
                    val tile = 13.dp.toPx()
                    // A joined None surface and two separate surfaces have different
                    // averaging footprints. Use one color to isolate the shape/rim handoff.
                    val colors = if (android.os.Build.VERSION.SDK_INT < 33) List(4) { Color(0xFF8BC7BD) }
                        else listOf(Color(0xFF8BC7BD), Color(0xFFF0C06F), Color(0xFF7D91C9), Color(0xFFCD83A7))
                    for (y in 0..(size.height / tile).toInt()) for (x in 0..(size.width / tile).toInt()) {
                        drawRect(colors[(x + 3 * y) % colors.size], Offset(x * tile, y * tile), Size(tile, tile))
                    }
                }
                Box(Modifier.align(Alignment.Center).size(120.dp, 60.dp).then(pill.follow).testTag("optical-case")) {
                    if (phase == 5) Row {
                        repeat(2) { LiquidGlassSurface(Modifier.size(60.dp), backing, shadow = false, pressFeedback = false) }
                    } else LiquidGlassSurface(Modifier.fillMaxSize(), backing, shadow = false, pressFeedback = false,
                        silhouette = if (phase == 0) null else pill.silhouette)
                }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backing.snapshot != null }
        fun capture(index: Int): ImageBitmap {
            compose.runOnIdle { phase = index }
            return compose.onNodeWithTag("optical-case").captureToImage().also { bitmap ->
                val raw = IntArray(bitmap.width * bitmap.height).also { bitmap.asAndroidBitmap().getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                val bytes = java.nio.ByteBuffer.allocate(raw.size * 4).apply { raw.forEach { putInt(it) } }.array()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
                    java.io.File(it, "split-optics-$index.sha256").apply { parentFile?.mkdirs(); writeText("$hash  split-optics-$index\n") }
                }
                android.util.Log.i("GlassSplitOptics", "phase=$index pixelSha256=$hash")
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

    /** Pre-U03.01c oracle: the split silhouette GlassSilhouette built from the size and phase alone (one body at rest). */
    private fun legacyGeometry(size: Size, progress: Float): GlassSilhouetteGeometry {
        val p = progress.coerceIn(0f, 1f)
        val bodies = splitPillBodies(size, p).let { if (p == 0f) it.take(1) else it }
        val rim = (p * 2f).coerceIn(0f, 1f)
        return GlassSilhouetteGeometry(bodies, rim * rim * (3f - 2f * rim))
    }

    @Test fun persistentSplitPillMatchesTheLegacyFramesWithinTheHandoffTolerance() {
        // D17: pixel parity at p ∈ {0, .1, .25, .4, .5, .75, 1} on the real surface, with the
        // handoff test's own mean channel tolerance (.002); the worst pixel is logged.
        var progress by mutableFloatStateOf(0f)
        compose.setContent { EngineTheme(clear = true) {
            val legacy = remember { GlassSilhouette { legacyGeometry(it, progress) } }
            val pill = rememberSplitPill { progress }
            Column {
                for ((tag, silhouette, follow) in listOf(Triple("legacy", legacy, Modifier), Triple("persistent", pill.silhouette, pill.follow)))
                    Box(Modifier.size(120.dp, 60.dp).then(follow).background(Color.Blue).testTag(tag)) {
                        LiquidGlassSurface(Modifier.fillMaxSize(), source = null, shadow = false, pressFeedback = false, silhouette = silhouette) {
                            Box(Modifier.fillMaxSize().background(Color.Red))
                        }
                    }
            }
        } }
        for (p in listOf(0f, .1f, .25f, .4f, .5f, .75f, 1f, .4f, 0f)) {
            compose.runOnIdle { progress = p }
            compose.waitForIdle()
            val a = compose.onNodeWithTag("legacy").captureToImage().toPixelMap()
            val b = compose.onNodeWithTag("persistent").captureToImage().toPixelMap()
            var error = 0.0; var worst = 0f
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val u = a[x, y]; val v = b[x, y]
                val d = (abs(u.red - v.red) + abs(u.green - v.green) + abs(u.blue - v.blue)) / 3
                error += d; worst = maxOf(worst, d)
            }
            val mean = error / (a.width * a.height)
            android.util.Log.i("GlassSplitParity", "p=$p meanChannelDifference=$mean worstPixel=$worst")
            assertTrue("Split pill p=$p differs from the legacy frame (mean=$mean)", mean < .002)
        }
    }
}

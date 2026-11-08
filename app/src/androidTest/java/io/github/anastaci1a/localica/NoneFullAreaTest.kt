package io.github.anastaci1a.localica

import android.os.Build
import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoneFullAreaTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun mixedSettingsRowsHaveTheSameNoneMaterial() = settingsRows(1f)

    @Test fun mixedSettingsRowsHaveTheSameNoneMaterialAtLargeFontScale() = settingsRows(1.5f)

    @Test fun normalSettingsRowsPixelReference() = settingsRows(1f, GlassRendererMode.Normal)

    @Test fun normalSettingsRowsPixelReferenceAtLargeFontScale() = settingsRows(1.5f, GlassRendererMode.Normal)

    private fun settingsRows(fontScale: Float, mode: GlassRendererMode = GlassRendererMode.None) {
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(mode) },
            LocalGlassAppearance provides GlassAppearance(whiteTint = 0f, contrast = 0f)
        ) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(320.dp, 480.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color(0xFFF8F8FA))
                    if (mode == GlassRendererMode.Normal) {
                        for (x in 0 until 32) drawRect(if (x % 2 == 0) Color.Red else Color.Blue,
                            Offset(x * 10f, 0f), Size(10f, size.height))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsLink("Appearance", "Interface gray", AppIcon.TabLibrary) {}
                        SettingsLink("Navigation", "Main tabs, Back history, and exit confirmation", AppIcon.TabHome) {}
                        SettingsLink("Glass", "Blur and tint", AppIcon.ActionSettings) {}
                    }
                }
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val titles = listOf("Appearance", "Navigation", "Glass")
        val captured = titles.map { row(it).captureToImage() }
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val output = File(root, "settings-material-api" + Build.VERSION.SDK_INT).also { it.mkdirs() }
        captured.forEachIndexed { index, image ->
            File(output, "$mode-$fontScale-" + titles[index] + ".png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val images = captured.map { it.toPixelMap() }
        assertTrue("The wrapping subtitle must exercise a taller row at fontScale=$fontScale",
            images[1].height > images[0].height)
        if (mode == GlassRendererMode.Normal) return
        val colors = images.map { it[8, it.height / 2] }
        val reference = colors.first()
        colors.forEachIndexed { index, color ->
            assertTrue("Material is present rather than transparent or unrecorded", color.alpha > .99f && color.red > .9f)
            for ((channel, pair) in listOf("red" to (reference.red to color.red),
                "green" to (reference.green to color.green), "blue" to (reference.blue to color.blue))) {
                assertEquals("Row $index $channel at fontScale=$fontScale: $colors", pair.first, pair.second, 1f / 255f)
            }
        }
    }

    @Test fun noneControlSceneReadsDensityAndAveragesItsFullBounds() {
        var split by mutableFloatStateOf(48f)
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) }
        ) { LocalicaTheme {
            GlassControlScene(Modifier.size(96.dp).testTag("control"), source = null,
                geometry = { LensGeometry(Rect(16f, 16f, 80f, 80f), 32f) }, body = { GlassShape() }) {
                // Density reads inside the nested reduction used to recurse on API 36.
                val inset = 16.dp.toPx()
                drawRect(Color.Blue)
                drawRect(Color.Red, Offset.Zero, Size(split, size.height))
                // Content outside the full thumb bounds must contribute nothing.
                drawRect(Color.Green, Offset.Zero, Size(inset, size.height))
            }
        } } }
        for ((boundary, red) in listOf(48f to .5f, 32f to .25f, 64f to .75f)) {
            compose.runOnIdle { split = boundary }
            compose.waitUntil(15_000) {
                val pixels = compose.onNodeWithTag("control").captureToImage().toPixelMap()
                listOf(42, 48, 54).all { x ->
                    val color = pixels[x, 48]
                    kotlin.math.abs(color.red - red) < .02f && color.green < .02f &&
                        kotlin.math.abs(color.blue - (1f - red)) < .02f
                }
            }
        }
    }

    @Test fun oddFootprintIncludesEveryPixelInsteadOfSixtyFourPointSamples() {
        lateinit var source: FrostedBackdrop
        var whiteWidth by mutableIntStateOf(8)
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassAppearance provides GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
        ) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            // The source contains the whole material footprint.
            Box(Modifier.size(297.dp, 169.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Black)
                    for (x in -20 until 277) if (Math.floorMod(x, 32) < whiteWidth)
                        drawRect(Color.White, Offset(x + 20f, 0f), Size(1f, size.height))
                }
                LiquidGlassSurface(Modifier.offset(20.dp, 20.dp).size(257.dp, 129.dp).testTag("mean"), source,
                    cornerRadius = 0.dp, pressFeedback = false, shadow = false)
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val image = compose.onNodeWithTag("mean").captureToImage().toPixelMap()
        val region = Rect(0f, 0f, 257f, 129f)
        // Independent oracle: the covered fraction of every one-pixel white column, all rows alike.
        fun mean(width: Int) = (-20 until 277).filter { Math.floorMod(it, 32) < width }.sumOf { x ->
            (minOf(region.right, x + 1f) - maxOf(region.left, x.toFloat())).coerceAtLeast(0f).toDouble() }.toFloat() / region.width
        val expected = mean(8)
        for (x in listOf(image.width / 4, image.width / 2, image.width * 3 / 4)) {
            val actual = image[x, image.height / 2]
            assertEquals("Full 257x129 area mean at x=$x", expected, actual.red, .02f)
            assertEquals(expected, actual.green, .02f)
            assertEquals(expected, actual.blue, .02f)
            if (Build.VERSION.SDK_INT < 33) assertLegacyAreaBound(actual.red, expected,
                region.translate(20f, 20f), 512, 256)
        }
        // Keep the same surface mounted while its backdrop changes. A retained
        // reduction must refresh its input rather than freeze the first mean.
        for (width in listOf(16, 24, 8)) {
            compose.runOnIdle { whiteWidth = width }
            val nextExpected = mean(width)
            compose.waitUntil(15_000) {
                val pixels = compose.onNodeWithTag("mean").captureToImage().toPixelMap()
                listOf(pixels.width / 4, pixels.width / 2, pixels.width * 3 / 4).all { x ->
                    val pixel = pixels[x, pixels.height / 2]
                    listOf(pixel.red, pixel.green, pixel.blue).all { kotlin.math.abs(it - nextExpected) < .02f }
                }
            }
        }
    }
}

/** Independent geometric bound: count the covered area of every cut boundary cell.
 * Both native fixtures contain black and white boundary pixels, so contrast is one. */
internal fun assertLegacyAreaBound(actual: Float, expected: Float, footprint: Rect,
    paddedWidth: Int, paddedHeight: Int) {
    val cellWidth = paddedWidth / 32f
    val cellHeight = paddedHeight / 32f
    var boundaryArea = 0f
    for (y in 0 until 32) for (x in 0 until 32) {
        val cell = Rect(x * cellWidth, y * cellHeight, (x + 1) * cellWidth, (y + 1) * cellHeight)
        val overlap = cell.intersect(footprint)
        if (!overlap.isEmpty && overlap.width * overlap.height < cellWidth * cellHeight)
            boundaryArea += overlap.width * overlap.height
    }
    val fraction = boundaryArea / (footprint.width * footprint.height)
    assertTrue("Legacy mean error ${kotlin.math.abs(actual - expected)} exceeds boundary-area fraction $fraction × contrast 1",
        kotlin.math.abs(actual - expected) <= fraction)
}

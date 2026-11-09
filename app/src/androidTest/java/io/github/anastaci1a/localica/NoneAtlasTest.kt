package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.engine.NoneReductionAtlasHost
import io.github.anastaci1a.localica.ui.engine.NoneReductionAtlas
import io.github.anastaci1a.localica.ui.engine.LocalNoneReductionAtlas
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #691 E4: several None materials of different sizes share one reduction atlas per window and each still
 * shows the coverage-weighted mean of the prerasterized scene over ITS OWN bounds (.015 per channel on
 * Android 13+), while members join, leave, move and resize. A single scene with distinct content under
 * every member makes any slot or block mix-up visible. */
class NoneAtlasTest {
    @get:Rule val compose = localicaComposeRule()
    private val appearance = GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
    private val width = 720
    private val height = 480

    private fun scene(phase: Float): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        val c = android.graphics.Canvas(it)
        c.drawColor(android.graphics.Color.WHITE)
        // Distinct regions: a red/blue split, black list rows, a 1 px stripe field, a green gradient block.
        c.drawRect(0f + phase, 0f, 200f + phase, 240f, Paint().apply { color = android.graphics.Color.RED })
        c.drawRect(200f + phase, 0f, 400f + phase, 240f, Paint().apply { color = android.graphics.Color.BLUE })
        val black = Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = true }
        for (y in 240 until height step 16) c.drawRect(0f, y + phase, 360f, y + phase + 8f, black)
        for (x in 360 until width) if (Math.floorMod(x, 32) < 8) c.drawRect(x + phase, 240f, x + phase + 1f, height.toFloat(), black)
        for (x in 400 until width step 2) c.drawRect(x + phase, 0f, x + phase + 2f, 240f,
            Paint().apply { color = android.graphics.Color.rgb(0, (255 * (x - 400) / 320).coerceIn(0, 255), 64) })
    }

    private fun mean(bitmap: Bitmap, region: Rect): Color {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0
        val row = IntArray(region.width.toInt())
        for (y in region.top.toInt() until region.bottom.toInt()) {
            bitmap.getPixels(row, 0, row.size, region.left.toInt(), y, row.size, 1)
            for (c in row) { r += (c ushr 16 and 255) / 255.0; g += (c ushr 8 and 255) / 255.0; b += (c and 255) / 255.0; n++ }
        }
        return Color((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    private fun assertMean(label: String, expected: Color, actual: Color, region: Rect) {
        for ((channel, e, a) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green),
                Triple("blue", expected.blue, actual.blue))) {
            if (Build.VERSION.SDK_INT >= 33) assertEquals("$label $channel over $region", e, a, .015f)
            else assertLegacyAreaBound(a, e, region, 1024, 512)
        }
    }

    private fun <T> eventually(block: () -> T): T {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            try { return block() } catch (failure: AssertionError) {
                if (android.os.SystemClock.uptimeMillis() > deadline) throw failure
                Thread.sleep(100)
            }
        }
    }

    private data class Member(val tag: String, val at: IntOffset, val size: IntSize)

    @Test fun membersOfDifferentSizesEachShowTheirOwnMeanWhileJoiningLeavingAndMoving() {
        lateinit var source: FrostedBackdrop
        var bitmap by mutableStateOf(scene(0f))
        var members by mutableStateOf(listOf(
            Member("split", IntOffset(100, 40), IntSize(257, 129)),     // red/blue split
            Member("rows", IntOffset(20, 250), IntSize(300, 200)),      // list rows (512x256 cell)
            Member("stripes", IntOffset(380, 260), IntSize(320, 180)),  // 1 px stripes
            Member("gradient", IntOffset(410, 30), IntSize(290, 190)),  // gradient (512x256 cell)
            Member("tiny", IntOffset(30, 30), IntSize(60, 60))))        // below the cell minimum: own chain
        var atlas: NoneReductionAtlas? = null
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassAppearance provides appearance) { LocalicaTheme { NoneReductionAtlasHost(GlassColorShader) {
            atlas = LocalNoneReductionAtlas.current
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(width.dp, height.dp).background(Color.Black)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawImage(bitmap.asImageBitmap(), filterQuality = FilterQuality.None)
                }
                for (member in members) key(member.tag) {
                    LiquidGlassSurface(Modifier.offset(member.at.x.dp, member.at.y.dp).size(member.size.width.dp, member.size.height.dp).testTag(member.tag),
                        source, cornerRadius = 0.dp, pressFeedback = false, shadow = false)
                }
            }
        } } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        if (Build.VERSION.SDK_INT >= 33) assertNotNull("The host provides an atlas on Android 13+", atlas)
        fun check(label: String) = eventually {
            for (member in members) {
                val region = Rect(Offset(member.at.x.toFloat(), member.at.y.toFloat()), Size(member.size.width.toFloat(), member.size.height.toFloat()))
                val pixels = compose.onNodeWithTag(member.tag).captureToImage().toPixelMap()
                val expected = mean(bitmap, region)
                for ((x, y) in listOf(pixels.width / 2 to pixels.height / 2, pixels.width / 6 to pixels.height / 4, pixels.width * 5 / 6 to pixels.height * 3 / 4))
                    assertMean("$label ${member.tag} at $x,$y", expected, pixels[x, y], region)
            }
        }
        check("initial")
        // Content changes under every member at once (HWUI re-renders the shared chain; nothing re-records).
        compose.runOnIdle { bitmap = scene(.5f) }
        check("phase .5")
        // A member leaves: the others keep their cells and blocks.
        compose.runOnIdle { members = members.filter { it.tag != "rows" } }
        check("after leaving")
        // A member moves and another resizes: same frame re-registration.
        compose.runOnIdle { members = members.map { when (it.tag) {
            "split" -> it.copy(at = IntOffset(140, 60))
            "gradient" -> it.copy(size = IntSize(200, 120))
            else -> it
        } } }
        check("after moving and resizing")
        // A new member joins and takes a fresh block.
        compose.runOnIdle { members = members + Member("rows2", IntOffset(40, 300), IntSize(280, 160)) }
        check("after joining")
        if (Build.VERSION.SDK_INT >= 33) assertTrue("The shared chain was recorded", (atlas?.recordings ?: 0) > 0)
    }
}

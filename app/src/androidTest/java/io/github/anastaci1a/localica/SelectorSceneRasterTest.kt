package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native pixels from the layered adapter, including software Canvas on API26. */
class SelectorSceneRasterTest {
    @get:Rule val compose = createComposeRule()
    private val shell = LensGeometry(Rect(0f, 0f, 300f, 64f), 32f)
    private val lens = LensGeometry(Rect(4f, 4f, 96f, 60f), 28f)
    private val originalSlot = Rect(0f, 0f, 100f, 64f)

    @Test fun changingTheSlotMovesRefractedInkWithoutAnotherCapture() {
        val backdrop = image(300, 64) { _, _ -> android.graphics.Color.WHITE }
        val atlas = image(300, 64) { x, y ->
            if (x in 40..49 && y in 15..48) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT
        }
        var slot by mutableStateOf(originalSlot)
        val renderer = SelectorSceneMesh()
        show(backdrop, atlas, renderer) { SelectorSceneGeometry(shell, lens,
            listOf(SelectorInkSlot(originalSlot, slot)), .72f, .72f) }
        val before = pixels()
        compose.runOnIdle { slot = Rect(-20f, 0f, 100f, 64f) }
        val moved = pixels()
        assertTrue("Stable atlas ink must follow the new inverse slot mapping immediately",
            redCenter(moved) < redCenter(before) - 7f)
        compose.runOnIdle { slot = originalSlot }
        val restored = pixels()
        assertEquals(redCenter(before), redCenter(restored), .5f)
        assertEquals("Slot motion must reuse the lens topology", 1, renderer.meshBuilds)
        assertEquals("Only the three distinct scenes need rendering", 3, renderer.frameBuilds)
        // Regions outside the rendered selector remain untouched.
        for (y in 0 until before.height) for (x in 110 until before.width)
            assertEquals(before[x, y], moved[x, y])
    }

    @Test fun changingInkPlacementCannotStretchTheBackdrop() {
        val backdrop = image(300, 64) { x, y -> android.graphics.Color.rgb(x * 255 / 299, y * 255 / 63, 96) }
        var slot by mutableStateOf(originalSlot)
        val geometry = { SelectorSceneGeometry(shell, lens,
            listOf(SelectorInkSlot(originalSlot, slot)), .72f, .72f) }
        show(backdrop, null, geometry = geometry)
        val before = pixels()
        compose.runOnIdle { slot = Rect(-20f, 2f, 100f, 62f) }
        val after = pixels()
        for (y in 0 until before.height) for (x in 0 until before.width)
            assertEquals("Ink placement must not alter the parent sample at $x,$y", before[x, y], after[x, y])
        // The samples also need to follow the nested optical path, not a flat source copy.
        for (x in listOf(20, 40, 60, 80)) {
            val sample = geometry().sample(Offset(x + .5f, 32.5f), false)
            val actual = after[x, 32]
            assertEquals(sample.backdrop.x / 299f, actual.red, .055f)
            assertEquals(sample.backdrop.y / 63f, actual.green, .055f)
        }
    }

    private fun show(backdrop: ImageBitmap, ink: ImageBitmap?, renderer: SelectorSceneMesh = SelectorSceneMesh(),
        geometry: () -> SelectorSceneGeometry) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Canvas(Modifier.size(300.dp, 64.dp).testTag("layered-scene")) {
                    drawRect(Color.White)
                    val g = geometry()
                    val clip = Path().apply { addRoundRect(RoundRect(g.selector.bounds, CornerRadius(g.selector.corner))) }
                    clipPath(clip) { with(renderer) {
                        draw(g, backdrop, IntSize(300, 64), Offset.Zero, ink, IntSize(300, 64),
                            android.graphics.Color.GRAY, android.graphics.Color.RED, GlassTone())
                    } }
                }
            }
        }
    }
    private fun pixels() = compose.onNodeWithTag("layered-scene").captureToImage().toPixelMap()
    private fun redCenter(pixels: PixelMap): Float {
        var total = 0f; var count = 0
        for (y in 8 until 56) for (x in 8 until 92) {
            val c = pixels[x, y]
            if (c.red > .7f && c.green < .35f && c.blue < .35f) { total += x; count++ }
        }
        assertTrue("The refracted ink must actually be visible", count > 80)
        return total / count
    }
    private fun image(width: Int, height: Int, color: (Int, Int) -> Int) =
        Bitmap.createBitmap(IntArray(width * height) { color(it % width, it / width) },
            width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

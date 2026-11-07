package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.FrostedBackdrop
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.engine.FocusBackdrop
import io.github.anastaci1a.localica.ui.engine.FocusBlurMode
import io.github.anastaci1a.localica.ui.engine.focusBlurMode
import io.github.anastaci1a.localica.ui.recordFrostedBackdrop
import io.github.anastaci1a.localica.ui.rememberFrostedBackdrop
import io.github.anastaci1a.localica.ui.spec.StockFocusSpec
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * #640 §2 prototype (prototype.yml only, no production caller): the scene is captured once through
 * `FrostedBackdrop.captureCurrent`, never per frame; the composite leaves the lifted item sharp, blurs (or, in the
 * dim-only fallback, only dims) the far scene, and does nothing while the front is still at the corner.
 */
class FocusBackdropPrototypeTest {
    @get:Rule val compose = createComposeRule()

    private class Harness { lateinit var backdrop: FocusBackdrop; lateinit var source: FrostedBackdrop; val front = mutableStateOf(0f); val captureRequest = mutableStateOf(0); var captured: Boolean? = null; var readbacks = 0 }

    private fun itemRect(size: Size, density: Float) = Rect(16f * density, size.height * .4f, size.width - 16f * density, size.height * .4f + 56f * density)

    private fun mount(mode: FocusBlurMode, dark: Boolean): Harness {
        val h = Harness()
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize().testTag("scene")) {
            val density = LocalDensity.current; val graphics = LocalGraphicsContext.current; val direction = LocalLayoutDirection.current
            val source = rememberFrostedBackdrop(Unit, dark = dark); h.source = source
            val backdrop = remember { FocusBackdrop(StockFocusSpec, density, mode) }; h.backdrop = backdrop
            // Vertical stripes with a 16 px period: blur turns them mid-grey, a veil only darkens them.
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(Color.Black)
                var x = 0f
                while (x < size.width) { drawRect(Color.White, Offset(x, 0f), Size(8f, size.height)); x += 16f }
            }
            val request = h.captureRequest.value
            LaunchedEffect(request) {
                if (request > 0) h.captured = backdrop.capture(source.size, graphics, direction, readback = { layer, record ->
                    h.readbacks++
                    source.captureCurrent(graphics, density, direction, valid = { source.recorded && !source.layer.isReleased }, captureLayer = layer, record = record)
                }) { drawLayer(source.layer) }
            }
            Canvas(Modifier.fillMaxSize()) {
                val item = itemRect(size, density.density)
                with(backdrop) { composite(item, 12f * density.density, h.front.value, 1f, dark) }
            }
        } } }
        compose.waitUntil(10_000) { h.source.recorded && h.source.size.width > 0 }
        return h
    }

    private class Sample(val sharp: Float, val white: Float, val black: Float)

    /** Stripe contrast over two periods around x0: max − min red, so the stripe phase and the capture origin do not matter. */
    private fun sample(x0: Int, y: Int): Sample {
        val image = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        val start = (x0 - 16).coerceIn(0, image.width - 33)
        var max = 0f; var min = 1f
        for (x in start until start + 32) { val red = image[x, y.coerceIn(0, image.height - 1)].red; if (red > max) max = red; if (red < min) min = red }
        return Sample(max - min, max, min)
    }

    @Test fun captureOnceThenBlurOutsideTheItemOnly() {
        assumeTrue("needs RenderEffect", Build.VERSION.SDK_INT >= 31)
        val mode = focusBlurMode(Build.VERSION.SDK_INT, glassNone = false)
        assertEquals(if (Build.VERSION.SDK_INT >= 33) FocusBlurMode.Shader else FocusBlurMode.Masked, mode)
        val h = mount(mode, dark = true)
        val density = compose.density.density
        val size = Size(h.source.size.width.toFloat(), h.source.size.height.toFloat())
        val item = itemRect(size, density)
        val farX = (size.width - 40f).toInt(); val farY = (item.bottom + (size.height - item.bottom) * .55f).toInt() // far below the item, clear of the system bars
        assertFalse(h.backdrop.ready)
        compose.runOnIdle { h.captureRequest.value = 1 }
        compose.waitUntil(10_000) { h.captured != null }
        assertEquals(true, h.captured)
        assertTrue(h.backdrop.ready)
        assertEquals(1, h.backdrop.captures); assertEquals(1, h.backdrop.pyramidBuilds); assertEquals(1, h.readbacks)
        // Front still at the corner: the whole scene is sharp.
        compose.waitForIdle()
        assertTrue("sharp at w=0", sample(farX, farY).sharp > .8f)
        for (front in listOf(.3f, .6f, 1f)) { compose.runOnIdle { h.front.value = front }; compose.waitForIdle() }
        val far = sample(farX, farY)
        assertTrue("blurred far from the item: contrast ${far.sharp}", far.sharp < .2f)
        assertTrue("mid grey under a 25 % veil: ${far.white}", far.white in .2f..  .62f)
        val inside = sample(item.center.x.toInt(), item.center.y.toInt())
        assertTrue("the item itself stays sharp: ${inside.sharp}", inside.sharp > .8f)
        assertEquals("one capture for the whole hold", 1, h.backdrop.captures); assertEquals(1, h.backdrop.pyramidBuilds); assertEquals(1, h.readbacks)
        assertTrue("composited per frame", h.backdrop.composites >= 3)
    }

    @Test fun dimOnlyVeilForGlassNoneAndOlderApisNeedsNoCapture() {
        assertEquals(FocusBlurMode.DimOnly, focusBlurMode(36, glassNone = true))
        assertEquals(FocusBlurMode.DimOnly, focusBlurMode(30, glassNone = false))
        val h = mount(FocusBlurMode.DimOnly, dark = true)
        val density = compose.density.density
        val size = Size(h.source.size.width.toFloat(), h.source.size.height.toFloat())
        val item = itemRect(size, density)
        val farX = (size.width - 40f).toInt(); val farY = (item.bottom + (size.height - item.bottom) * .55f).toInt() // far below the item, clear of the system bars
        assertTrue("no capture needed", h.backdrop.ready)
        compose.runOnIdle { h.captureRequest.value = 1 }
        compose.waitUntil(10_000) { h.captured != null }
        assertEquals(0, h.readbacks); assertEquals(0, h.backdrop.captures)
        assertTrue("sharp at w=0", sample(farX, farY).sharp > .8f)
        compose.runOnIdle { h.front.value = 1f }; compose.waitForIdle()
        val far = sample(farX, farY)
        assertTrue("still sharp: ${far.sharp}", far.sharp > .5f)
        assertTrue("white stripe dimmed by about 25 %: ${far.white}", far.white in .6f.. .85f)
        assertTrue("black stays black: ${far.black}", far.black < .05f)
        val inside = sample(item.center.x.toInt(), item.center.y.toInt())
        assertTrue("item untouched: ${inside.white}", inside.white > .95f && inside.sharp > .9f)
    }
}

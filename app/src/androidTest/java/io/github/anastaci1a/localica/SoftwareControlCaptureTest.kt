package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Owned software pixels and real control draws; no deferred-consumer or heap/time claim. */
class SoftwareControlCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureReuseClearsAndExceptionsReleaseTheConsumerBoundary() {
        val owner = SoftwareControlCapture()
        val extent = IntSize(12, 10)
        val density = Density(1f)
        var first: ImageBitmap? = null
        var allocations = 0
        val observe: (LensAllocation) -> Unit = { if (it == LensAllocation.CaptureBitmap) allocations++ }
        owner.withScene(extent, density, LayoutDirection.Ltr, observe,
            record = { drawRect(Color.Red) }, consume = { first = it })
        owner.withScene(extent, density, LayoutDirection.Rtl, observe,
            record = { drawRect(Color.Blue, size = Size(3f, 3f)) }, consume = {
                assertSame(first, it)
                assertEquals(Color.Blue, it.toPixelMap()[1, 1])
                assertEquals(Color.Transparent, it.toPixelMap()[8, 8])
            })
        assertEquals(1, allocations)
        val recording = IllegalStateException("recording sentinel")
        var consumed = false
        try {
            owner.withScene(extent, density, LayoutDirection.Ltr,
                record = { drawRect(Color.Green); throw recording }, consume = { consumed = true })
            fail("Recording exception must propagate")
        } catch (e: IllegalStateException) { assertSame(recording, e) }
        assertFalse(consumed)
        val consuming = IllegalStateException("consumer sentinel")
        try {
            owner.withScene(extent, density, LayoutDirection.Ltr, record = {}, consume = {
                assertEquals(Color.Transparent, it.toPixelMap()[1, 1])
                try {
                    owner.withScene(extent, density, LayoutDirection.Ltr, record = {}, consume = {})
                    fail("A consumer cannot reenter its live input")
                } catch (e: IllegalStateException) {
                    assertEquals("Software control capture cannot be reentered", e.message)
                }
                throw consuming
            })
            fail("Consumer exception must propagate")
        } catch (e: IllegalStateException) { assertSame(consuming, e) }
        owner.withScene(extent, density, LayoutDirection.Ltr, observe, record = {}, consume = {
            assertSame(first, it)
            assertEquals(Color.Transparent, it.toPixelMap()[8, 8])
        })
        owner.withScene(IntSize(13, 10), density, LayoutDirection.Ltr, observe, record = {}, consume = {
            assertNotSame(first, it)
            assertEquals(13, it.width)
        })
        assertEquals(2, allocations)
        // Identity is observed only for this synchronous test; no UI-published input is recycled.
    }

    @Test fun mutableMeshUsesCurrentPixelsAndIndependentOutputsWhileImmutableCachingSurvives() {
        val shape = LensGeometry(Rect(0f, 0f, 32f, 24f), 8f)
        val mutable = ImageBitmap(32, 24)
        val immutable = ImageBitmap(32, 24).also { it.asAndroidBitmap().eraseColor(android.graphics.Color.GREEN) }
        val renderer = LensMeshRenderer()
        val counts = mutableMapOf<LensAllocation, Int>()
        val observe: (LensAllocation) -> Unit = { counts[it] = (counts[it] ?: 0) + 1 }
        fun paint(color: Color) {
            mutable.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(mutable), Size(32f, 24f)) {
                drawRect(color, size = Size(16f, 24f))
            }
        }
        fun render(input: ImageBitmap, changing: Boolean, original: Boolean = false, content: Int = 0): ImageBitmap {
            val output = ImageBitmap(32, 24)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(32f, 24f)) {
                if (original) with(OriginalControlLensMesh()) {
                    draw(shape, input, IntSize(32, 24), Offset.Zero, .8f, lighting = false)
                } else with(renderer) {
                    draw(shape, input, IntSize(32, 24), Offset.Zero, .8f, lighting = false,
                        mutableSoftwareSnapshot = changing, contentGeneration = content, onAllocation = observe)
                }
            }
            return output
        }
        paint(Color.Red)
        if (Build.VERSION.SDK_INT >= 29) {
            try { render(mutable, true); fail("Hardware mesh must reject mutable routing") }
            catch (e: IllegalArgumentException) {
                assertEquals("Mutable snapshots require synchronous software rasterization", e.message)
            }
            assertTrue(counts.isEmpty())
            equalPixels(render(immutable, false), render(immutable, false, original = true), "immutable API>=29")
            return
        }
        val a = render(mutable, true, content = 1)
        val savedA = a.toPixelMap().let { p -> IntArray(p.width * p.height) { i -> p[i % p.width, i / p.width].toArgb() } }
        equalPixels(a, render(mutable, false, original = true), "A fresh reference")
        render(immutable, false)
        val afterImmutable = counts.toMap()
        render(immutable, false)
        assertEquals("Immutable image still uses existing shader/raster caches", afterImmutable, counts)
        paint(Color.Blue)
        val b = render(mutable, true, content = 2)
        equalPixels(b, render(mutable, false, original = true), "B fresh reference")
        assertTrue("Current blue pixels must replace red", b.toPixelMap()[8, 12].blue > .9f)
        assertTrue("New transparent remainder must stay clear", b.toPixelMap()[24, 12].alpha < .01f)
        val old = a.toPixelMap()
        for (i in savedA.indices) assertEquals("Published A stays independent", savedA[i], old[i % old.width, i / old.width].toArgb())
        equalPixels(render(immutable, false), render(immutable, false, original = true), "immutable cache after B")
        // The mutable capture shader is reused across A and B; only the immutable image adds one.
        assertEquals(2, counts[LensAllocation.BitmapShader] ?: 0)
        assertEquals(3, counts[LensAllocation.SoftwareRaster] ?: 0)
        // A reused immutable-cache identity with new content and a new generation never returns the old raster.
        val reused = ImageBitmap(32, 24).also { it.asAndroidBitmap().eraseColor(android.graphics.Color.RED) }
        val staleRed = render(reused, false, content = 1)
        reused.asAndroidBitmap().eraseColor(android.graphics.Color.BLUE)
        val freshBlue = render(reused, false, content = 2)
        assertTrue("Red raster before reuse", staleRed.toPixelMap()[16, 12].red > .9f)
        assertTrue("New generation must not return the red raster", freshBlue.toPixelMap()[16, 12].blue > .9f)
        equalPixels(freshBlue, render(reused, false, original = true), "reused identity new generation")
    }

    @Test fun legacyControlMatchesFrozenSceneAcrossContentExtentFadeRestAndReactivation() {
        exerciseCoincidentControlScenes(compose, frozenActual = false)
    }

    @Test fun realLegacySliderReversalRetainsInputAndStopsAllocatingAtRest() {
        var value by mutableFloatStateOf(.5f)
        var captures = 0
        var draws = 0
        var shaders = 0
        var rasters = 0
        val runtime = GlassRendererRuntime(GlassRendererMode.Legacy)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime, LocalReducedMotion provides true,
                LocalControlLensAllocation provides {
                    when (it) { LensAllocation.CaptureBitmap -> captures++; LensAllocation.BitmapShader -> shaders++
                        LensAllocation.SoftwareRaster -> rasters++ } },
                LocalControlLensDraw provides { draws++ }) {
                Box(Modifier.size(300.dp, 100.dp).background(Color.White)) {
                    InterfaceSlider(value, { value = it }, Modifier.width(280.dp).testTag("actual-slider"))
                }
            }
        } }
        val slider = compose.onNodeWithTag("actual-slider")
        var held = false
        val previousAutoAdvance = compose.mainClock.autoAdvance
        var primary: Throwable? = null
        try {
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            held = true
            slider.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(240)
            for (fraction in listOf(.8f, .25f, .65f)) {
                slider.performTouchInput { moveTo(Offset(width * fraction, centerY), delayMillis = 32) }
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle { android.util.Log.i("ControlCaptureWork", "slider fraction=$fraction value=$value captures=$captures shaders=$shaders rasters=$rasters draws=$draws") }
            }
            slider.performTouchInput { up() }
            held = false
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Real contact drew optics", draws > 0)
                assertTrue("Real contact captured input", captures > 0)
                if (Build.VERSION.SDK_INT < 29) assertTrue("Mutable capture shader is reused: shaders=$shaders captures=$captures", shaders <= captures)
                assertTrue("Reversal ends on the right half", value > .5f)
            }
            val stopped = captures to draws
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { assertEquals("Rest has no new optical draws or capture allocations", stopped, captures to draws) }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { if (held) slider.performTouchInput { cancel() } }
            catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
            finally { compose.mainClock.autoAdvance = previousAutoAdvance }
        }
    }

    private fun equalPixels(actual: ImageBitmap, expected: ImageBitmap, label: String) =
        assertControlCapturePixels(actual, expected, label, "candidate")
}

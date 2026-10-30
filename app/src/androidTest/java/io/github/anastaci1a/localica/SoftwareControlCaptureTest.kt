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
import androidx.compose.ui.test.junit4.createComposeRule
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
        fun render(input: ImageBitmap, changing: Boolean, original: Boolean = false): ImageBitmap {
            val output = ImageBitmap(32, 24)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(32f, 24f)) {
                if (original) with(OriginalControlLensMesh()) {
                    draw(shape, input, IntSize(32, 24), Offset.Zero, .8f, lighting = false)
                } else with(renderer) {
                    draw(shape, input, IntSize(32, 24), Offset.Zero, .8f, lighting = false,
                        mutableSoftwareSnapshot = changing, onAllocation = observe)
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
        val a = render(mutable, true)
        val savedA = a.toPixelMap().let { p -> IntArray(p.width * p.height) { i -> p[i % p.width, i / p.width].toArgb() } }
        equalPixels(a, render(mutable, false, original = true), "A fresh reference")
        render(immutable, false)
        val afterImmutable = counts.toMap()
        render(immutable, false)
        assertEquals("Immutable image still uses existing shader/raster caches", afterImmutable, counts)
        paint(Color.Blue)
        val b = render(mutable, true)
        equalPixels(b, render(mutable, false, original = true), "B fresh reference")
        assertTrue("Current blue pixels must replace red", b.toPixelMap()[8, 12].blue > .9f)
        assertTrue("New transparent remainder must stay clear", b.toPixelMap()[24, 12].alpha < .01f)
        val old = a.toPixelMap()
        for (i in savedA.indices) assertEquals("Published A stays independent", savedA[i], old[i % old.width, i / old.width].toArgb())
        equalPixels(render(immutable, false), render(immutable, false, original = true), "immutable cache after B")
        assertEquals(3, counts[LensAllocation.BitmapShader] ?: 0)
        assertEquals(3, counts[LensAllocation.SoftwareRaster] ?: 0)
    }

    @Test fun legacyControlMatchesFrozenSceneAcrossContentExtentFadeRestAndReactivation() {
        var stage by mutableIntStateOf(0)
        var captures = 0
        var referenceCaptures = 0
        var draws = 0
        val runtime = GlassRendererRuntime(GlassRendererMode.Legacy)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalGlassRendererRuntime provides runtime,
                LocalControlLensAllocation provides { if (it == LensAllocation.CaptureBitmap) captures++ },
                LocalControlLensDraw provides { draws++ }) {
                Column {
                    // The same closures feed real candidate and exact frozen pre-change scene.
                    val geometry: androidx.compose.ui.graphics.drawscope.DrawScope.() -> LensGeometry = {
                        LensGeometry(Rect(24f, 12f, if (stage == 3) 92f else 88f, 52f), 20f)
                    }
                    val active = { when (stage) { 0, 6 -> 0f; 4 -> .4f; else -> 1f } }
                    val body = { GlassShape(depth = if (stage == 4) -.08f else 0f) }
                    val scene: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                        if (stage != 2) drawRect(if (stage % 2 == 0) Color.Red else Color.Blue,
                            topLeft = Offset(0f, 22f), size = Size(120f, 12f))
                    }
                    Box(Modifier.size(120.dp, 64.dp).background(Color.White)) {
                        GlassControlScene(Modifier.fillMaxSize().testTag("candidate"), source = null,
                            geometry = geometry, body = body, active = active, scene = scene)
                    }
                    Box(Modifier.size(120.dp, 64.dp).background(Color.White)) {
                        OriginalControlCaptureScene(Modifier.fillMaxSize().testTag("reference"), source = null,
                            geometry = geometry, body = body, active = active,
                            onCapture = { referenceCaptures++ }, scene = scene)
                    }
                }
            }
        }
        var stableCaptures = 0
        var stableDraws = 0
        var stableReferences = 0
        for (next in 0..7) {
            compose.runOnIdle { stage = next }
            val actual = compose.onNodeWithTag("candidate").captureToImage()
            val reference = compose.onNodeWithTag("reference").captureToImage()
            equalPixels(actual, reference, "stage $next")
            compose.runOnIdle {
                when (next) {
                    0 -> assertEquals(0, captures)
                    1 -> { assertTrue(draws > 0); stableCaptures = captures; stableDraws = draws; stableReferences = referenceCaptures }
                    2 -> {
                        assertTrue(draws > stableDraws)
                        assertTrue(referenceCaptures > stableReferences)
                        if (Build.VERSION.SDK_INT < 29) assertEquals("Same extent reuses capture", stableCaptures, captures)
                        else assertTrue("API>=29 retains fresh capture", captures > stableCaptures)
                    }
                    3 -> assertTrue("Extent change allocates", captures > stableCaptures)
                    6 -> { stableCaptures = captures; stableDraws = draws }
                    7 -> { assertTrue("Reactivation draws", draws > stableDraws); assertTrue("Reactivation owns a fresh capture", captures > stableCaptures) }
                }
                android.util.Log.i("ControlCaptureWork", "stage=$next api=${Build.VERSION.SDK_INT} captures=$captures referenceCaptures=$referenceCaptures draws=$draws")
            }
        }
    }

    @Test fun realLegacySliderReversalRetainsInputAndStopsAllocatingAtRest() {
        var value by mutableFloatStateOf(.5f)
        var captures = 0
        var draws = 0
        val runtime = GlassRendererRuntime(GlassRendererMode.Legacy)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime, LocalReducedMotion provides true,
                LocalControlLensAllocation provides { if (it == LensAllocation.CaptureBitmap) captures++ },
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
                compose.runOnIdle { android.util.Log.i("ControlCaptureWork", "slider fraction=$fraction value=$value captures=$captures draws=$draws") }
            }
            slider.performTouchInput { up() }
            held = false
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Real contact drew optics", draws > 0)
                assertTrue("Real contact captured input", captures > 0)
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

    private fun equalPixels(actual: ImageBitmap, expected: ImageBitmap, label: String) {
        assertEquals(label, expected.width, actual.width)
        assertEquals(label, expected.height, actual.height)
        val a = actual.toPixelMap(); val b = expected.toPixelMap()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            // At most one 8-bit channel quantization step; no geometric or perceptual tolerance.
            val ac = a[x, y]; val bc = b[x, y]
            // Compare ARGB8 units directly: adjacent normalized Float channels can
            // subtract to slightly more than 1f / 255f (for example, 16 and 15).
            val argb = ac.toArgb(); val expectedArgb = bc.toArgb()
            if (!(kotlin.math.abs((argb ushr 24) - (expectedArgb ushr 24)) <= 1 &&
                kotlin.math.abs(((argb ushr 16) and 255) - ((expectedArgb ushr 16) and 255)) <= 1 &&
                kotlin.math.abs(((argb ushr 8) and 255) - ((expectedArgb ushr 8) and 255)) <= 1 &&
                kotlin.math.abs((argb and 255) - (expectedArgb and 255)) <= 1))
                fail("$label at $x,$y: expected $bc, actual $ac")
        }
    }
}

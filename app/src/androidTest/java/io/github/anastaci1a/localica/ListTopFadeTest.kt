package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.graphics.Rect as PixelRect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.LocalPopupScrollEdges
import io.github.anastaci1a.localica.ui.listTopFade
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.roundToInt

/** Leaf-mask control, not BrowserScreen slot wiring or physical compositor acceptance.
 * Exactly five native Window copies: no pixel-dependent retry, redraw, clock or tolerance change.
 * Layout/draw observations qualify the copy but are not atomic compositor frame witnesses. */
class ListTopFadeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val variants = listOf("default", "explicit", "legacy", "popup")
    private val heights = listOf(112, 40)
    private val strength = mutableFloatStateOf(1f)
    private val draws = AtomicInteger()
    private val reads = IntArray(2)
    private val lastRead = FloatArray(2) { Float.NaN }
    private var popupReads = 0
    private var compositions = 0
    private var measurements = 0
    private var density = 0f
    private lateinit var view: View

    @Test fun drawTimeStrengthPreservesDefaultAndReversesWithFixedGeometryAndPopupBypass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val directory = File(base, "list-top-fade-${SystemClock.uptimeMillis()}")
        check(directory.mkdirs())
        val evidence = File(directory, "observations.txt")
        evidence.writeText("Fixed window-buffer captures; API=${android.os.Build.VERSION.SDK_INT}\n" +
            "No BrowserScreen wiring, physical presentation, or moving-slot acceptance claim.\n")
        compose.setContent {
            val currentView = LocalView.current
            val currentDensity = LocalDensity.current.density
            SideEffect { view = currentView; density = currentDensity }
            DisposableEffect(currentView) {
                val observer = currentView.viewTreeObserver
                val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
                observer.addOnDrawListener(listener)
                onDispose { if (observer.isAlive) observer.removeOnDrawListener(listener) }
            }
            Column(Modifier.safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                heights.forEachIndexed { index, height ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        variants.forEach { variant -> Sample(index, height, variant) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5000) {
            compose.runOnUiThread { view.isAttachedToWindow && view.isLaidOut && view.hasWindowFocus() && draws.get() > 0 && reads.all { it > 0 } }
        }
        val originalBounds = windowBounds()
        val originalCounts = compose.runOnUiThread { compositions to measurements }
        val sequence = listOf(1f, .5f, 0f, .5f, 1f)
        sequence.forEachIndexed { index, requested ->
            val beforeDraws = draws.get()
            val beforeReads = compose.runOnUiThread { reads.toList() }
            compose.runOnIdle { strength.floatValue = requested }
            compose.waitForIdle()
            compose.waitUntil(5000) {
                compose.runOnUiThread {
                    lastRead.all { it == requested } && (index == 0 ||
                        draws.get() > beforeDraws && reads.indices.all { reads[it] > beforeReads[it] })
                }
            }
            val bounds = windowBounds()
            assertEquals("Strength must not move or resize the source", originalBounds, bounds)
            compose.runOnUiThread {
                assertEquals("Strength is not a composition input", originalCounts.first, compositions)
                assertEquals("Strength is not a measure input", originalCounts.second, measurements)
                assertEquals("Popup bypass must not invoke the strength reader", 0, popupReads)
            }
            evidence.appendText("case=$index strength=$requested beforeNativeDraws=$beforeDraws nativeDraws=${draws.get()} " +
                "preCopyBounds=$bounds ${nativeFacts()}\n")
            val image = windowPixels()
            try {
                // Retain the ORIGINAL copy before examining any pixel, including passing cases.
                File(directory, "$index-original-window.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val afterBounds = windowBounds()
                evidence.appendText("case=$index copy=${image.width}x${image.height} postCopyBounds=$afterBounds ${nativeFacts()}\n")
                assertEquals("Geometry stays fixed across the single native copy", bounds, afterBounds)
                val samples = StringBuilder("case,strength,height,variant,x,y,expected,r,g,b\n")
                try {
                    heights.forEach { height ->
                        val legacy = bounds.getValue("$height-legacy")
                        val default = bounds.getValue("$height-default")
                        assertSamePixels(image, legacy, default, "Default must exactly retain frozen full fade")
                        if (requested == 1f) assertSamePixels(image, legacy, bounds.getValue("$height-explicit"),
                            "Explicit full strength must exactly retain frozen full fade")
                        if (requested == 0f) assertSamePixels(image, bounds.getValue("$height-popup"), bounds.getValue("$height-explicit"),
                            "Zero strength must exactly retain unmasked content")
                        variants.forEach { variant ->
                            val amount = when (variant) { "explicit" -> requested.toDouble(); "popup" -> 0.0; else -> 1.0 }
                            assertSamples(image, bounds.getValue("$height-$variant"), amount, "$index,$requested,$height,$variant", samples)
                        }
                    }
                } finally { File(directory, "$index-samples.csv").writeText(samples.toString()) }
            } catch (failure: Throwable) {
                evidence.appendText("FAILED case=$index ${failure.javaClass.name}: ${failure.message}\n")
                throw failure
            } finally { image.recycle() }
        }
    }

    @Composable private fun Sample(index: Int, height: Int, variant: String) {
        SideEffect { compositions++ }
        val fade = when (variant) {
            "default" -> Modifier.listTopFade()
            "explicit" -> Modifier.listTopFade {
                strength.floatValue.also { lastRead[index] = it; reads[index]++ }
            }
            "legacy" -> Modifier.frozenFullFade()
            else -> Modifier.listTopFade { popupReads++; strength.floatValue }
        }
        CompositionLocalProvider(LocalPopupScrollEdges provides (variant == "popup")) {
        Box(Modifier.size(48.dp, height.dp).background(Color.Black).testTag("$height-$variant")) {
            Canvas(Modifier.matchParentSize().layout { measurable, constraints ->
                measurements++
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(0, 0) }
            }.then(fade)) {
                drawRect(Color.White)
                drawRect(Color.Green, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
            }
        }
        }
    }

    /** Frozen pre-change operation sequence. No call into the new strength arithmetic. */
    private fun Modifier.frozenFullFade() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val height = 80.dp.toPx().coerceAtMost(size.height)
        drawRect(Brush.verticalGradient(*Array(17) { i -> val t = i / 16f; t to Color.Black.copy(alpha = t * t * (3f - 2f * t)) }, endY = height),
            size = Size(size.width, height), blendMode = BlendMode.DstIn)
    }

    /** The native brush is linear BETWEEN 17 cubic samples, not a continuous cubic shader. */
    private fun expectedAlpha(pixelCenterY: Double, band: Double, amount: Double): Double {
        if (pixelCenterY >= band) return 1.0
        val coordinate = (pixelCenterY / band).coerceIn(0.0, 1.0) * 16.0
        val lower = floor(coordinate).toInt().coerceAtMost(15)
        val fraction = coordinate - lower
        fun stop(index: Int): Double {
            val x = index / 16.0
            return 3.0 * x * x - 2.0 * x * x * x
        }
        val old = stop(lower) * (1.0 - fraction) + stop(lower + 1) * fraction
        return (1.0 - amount) + amount * old
    }

    private fun assertSamples(image: Bitmap, crop: PixelRect, amount: Double, label: String, samples: StringBuilder) {
        assertTrue("Measured crop is wholly inside the copied native window: $crop",
            crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height)
        val band = minOf(80.0 * density, crop.height().toDouble())
        val ys = (listOf(.02, .125, .25, .5, .75, .875, .98).map { (it * band).toInt() } +
            listOf(crop.height() - 2)).distinct()
        for (y in ys) for (right in listOf(false, true)) {
            val x = if (right) crop.width() * 3 / 4 else crop.width() / 4
            val alpha = expectedAlpha(y + .5, band, amount)
            val expected = (alpha * 255.0).roundToInt()
            val pixel = image.getPixel(crop.left + x, crop.top + y)
            val channels = listOf(PixelColor.red(pixel), PixelColor.green(pixel), PixelColor.blue(pixel))
            samples.append("$label,$x,$y,$expected,${channels.joinToString(",")}\n")
            val targets = if (right) listOf(0, expected, 0) else List(3) { expected }
            channels.zip(targets).forEach { (actual, target) ->
                // Two UNORM8 levels cover stop-alpha and final-buffer quantization;
                // this fixed arithmetic allowance is never tuned to captured pixels.
                if (amount == 0.0) assertEquals("Unmasked endpoint: $label at $x,$y", target, actual)
                else assertTrue("$label sample=$x,$y expected=$targets actual=$channels alpha=$alpha",
                    kotlin.math.abs(actual - target) <= 2)
            }
        }
    }

    private fun assertSamePixels(image: Bitmap, first: PixelRect, second: PixelRect, label: String) {
        assertEquals(first.width(), second.width()); assertEquals(first.height(), second.height())
        for (y in 0 until first.height()) for (x in 0 until first.width())
            assertEquals("$label at $x,$y", image.getPixel(first.left + x, first.top + y), image.getPixel(second.left + x, second.top + y))
    }

    private fun windowBounds(): Map<String, PixelRect> = heights.flatMap { height -> variants.map { "$height-$it" } }.associateWith { tag ->
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val root = (node.root as ViewRootForTest).view
        compose.runOnUiThread {
            assertSame("All samples belong to one native source window", view, root)
            assertTrue("Window-local samples require an untransformed source View", root.matrix.isIdentity)
            val origin = IntArray(2).also(root::getLocationInWindow)
            val b = node.boundsInRoot
            listOf(b.left, b.top, b.right, b.bottom).forEach {
                assertEquals("Fixed fixture edges must be pixel-aligned", it.roundToInt().toFloat(), it, .001f)
            }
            PixelRect((origin[0] + b.left).roundToInt(), (origin[1] + b.top).roundToInt(),
                (origin[0] + b.right).roundToInt(), (origin[1] + b.bottom).roundToInt())
        }
    }

    private fun nativeFacts() = compose.runOnUiThread {
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        "nonAtomic view=${System.identityHashCode(view)} token=${view.windowToken} attached=${view.isAttachedToWindow} " +
            "focus=${view.hasWindowFocus()} size=${view.width}x${view.height} screen=${screen.toList()} " +
            "window=${window.toList()} density=$density matrix=${view.matrix}"
    }

    private fun windowPixels(): Bitmap {
        val window = compose.activity.window
        val image = compose.runOnUiThread { Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888) }
        val completed = CountDownLatch(1)
        val ownership = Any()
        var returned = false
        var abandoned = false
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(window, image, { code ->
                synchronized(ownership) { result = code; returned = true; if (abandoned) image.recycle() }
                completed.countDown()
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Throwable) { image.recycle(); throw failure }
        if (!completed.await(5, TimeUnit.SECONDS)) {
            synchronized(ownership) { if (returned) image.recycle() else abandoned = true }
            throw AssertionError("Single native window copy timed out")
        }
        if (result != PixelCopy.SUCCESS) { image.recycle(); throw AssertionError("Single native window copy failed: $result") }
        return image
    }
}

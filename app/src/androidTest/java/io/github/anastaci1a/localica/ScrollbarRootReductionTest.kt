package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsCompat
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.LocalicaUiRoot
import io.github.anastaci1a.localica.ui.LocalicaUiRootState
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** App settings must reach the production root clock, rather than only a fixture local. */
class ScrollbarRootReductionTest {
    @get:Rule val compose = applicationRootComposeRule()
    private data class Frame(val elapsedNanos: Long, val access: String, val clockIdentity: Int,
        val active: Int, val opacity: Float, val release: Float, val grip: Float)
    private data class Rectangle(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class WindowRecord(val density: Float, val fontScale: Float, val resourceDensity: Float,
        val insetLeft: Int, val insetTop: Int, val insetRight: Int, val insetBottom: Int,
        val width: Int, val height: Int, val screenX: Int, val screenY: Int,
        val copySource: Rectangle, val displayBounds: Rectangle)

    @Test fun rootReductionDuringGripAndReleaseKeepsExactBaseCurves() = inspect(64)
    @Test fun rootReductionDuringIdleDelayKeepsRevealAlive() = inspect(800)
    @Test fun rootReductionDuringFadeKeepsVisibleScrollbar() = inspect(1296)

    private fun inspect(elapsedMillis: Long) {
        val root = LocalicaUiRootState(InterfaceSettings())
        lateinit var clock: UiClock
        lateinit var motion: ScrollbarMotion
        var density = 0f
        var fontScale = 0f
        compose.setContent {
            LocalicaUiRoot(root) { LocalicaTheme {
                val owner = ownerClock()
                val rail = remember(owner) { ScrollbarMotion(owner) }
                val reduced = LocalEngineContext.current.access == MotionAccess.Reduced
                val actualDensity = LocalDensity.current
                SideEffect {
                    clock = owner; motion = rail; rail.access(reduced)
                    density = actualDensity.density; fontScale = actualDensity.fontScale
                }
                DisposableEffect(rail) { onDispose { rail.dispose() } }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    Box(Modifier.size(120.dp, 200.dp).background(Color.White).testTag("root-reduction-rail")) {
                        Box(Modifier.scrollbarPresentation(rail, { 40f + rail.releaseOffset() * 100f })
                            .size(8.dp, 40.dp).background(Color.Green))
                    }
                }
            } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            assertEquals(MotionAccess.Standard, clock.context!!.access)
            motion.reveal(); motion.release(.2f); motion.contact(true, 1f)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val origin = compose.runOnIdle { clock.presentedStamp.nanos }
        val initialClock = clock
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(nativeEvidenceDirectory("scrollbar-root-reduction",
            instrumentation.targetContext.getExternalFilesDir(null)), "api${Build.VERSION.SDK_INT}-$elapsedMillis")
        check(output.mkdirs() || output.isDirectory)
        val rows = JSONArray()
        val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
        val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .2f, 0f)
        val grip = TargetBasedAnimation(tween<Float>(240, easing = CubicBezierEasing(0f, 0f, .2f, 1f)),
            Float.VectorConverter, 1f, 1.5f)
        fun sample(label: String, access: MotionAccess) {
            fun snapshot() = compose.runOnIdle {
                Frame(clock.presentedStamp.nanos - origin, clock.context!!.access.name,
                    System.identityHashCode(clock), clock.activeCount, motion.opacity(), motion.releaseOffset(), motion.grip())
            }
            val node = compose.onNodeWithTag("root-reduction-rail").fetchSemanticsNode()
            val context = compose.runOnIdle {
                val view = (node.root as ViewRootForTest).view
                val window = IntArray(2).also(view::getLocationInWindow)
                val screen = IntArray(2).also(view::getLocationOnScreen)
                val insets = WindowInsetsCompat.toWindowInsetsCompat(checkNotNull(view.rootWindowInsets), view)
                    .getInsets(WindowInsetsCompat.Type.systemBars())
                val bounds = node.boundsInRoot
                WindowRecord(density, fontScale, view.resources.displayMetrics.density,
                    insets.left, insets.top, insets.right, insets.bottom, view.rootView.width, view.rootView.height,
                    screen[0], screen[1], Rectangle(bounds.left.roundToInt() + window[0],
                        bounds.top.roundToInt() + window[1], bounds.right.roundToInt() + window[0], bounds.bottom.roundToInt() + window[1]),
                    Rectangle(bounds.left.roundToInt() + screen[0], bounds.top.roundToInt() + screen[1],
                        bounds.right.roundToInt() + screen[0], bounds.bottom.roundToInt() + screen[1]))
            }
            val before = snapshot()
            val image = compose.onNodeWithTag("root-reduction-rail").captureNativeFrame().asAndroidBitmap()
            try {
                val file = File(output, "$label.png")
                check(!file.exists())
                file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                var painted = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val pixel = image.getPixel(x, y)
                    if (android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) &&
                        android.graphics.Color.green(pixel) > android.graphics.Color.blue(pixel)) painted++
                }
                val values = snapshot()
                val row = JSONObject().put("label", label).put("source", BuildConfig.SOURCE_SHA)
                    .put("access", values.access).put("elapsedNanos", values.elapsedNanos)
                    .put("clockIdentity", values.clockIdentity).put("active", values.active)
                    .put("opacity", values.opacity).put("release", values.release).put("grip", values.grip)
                    .put("paintedPixels", painted).put("density", context.density).put("fontScale", context.fontScale)
                    .put("resourceDensity", context.resourceDensity)
                    .put("systemInsets", JSONArray(listOf(context.insetLeft, context.insetTop, context.insetRight, context.insetBottom)))
                    .put("windowSize", JSONArray(listOf(context.width, context.height)))
                    .put("screenOrigin", JSONArray(listOf(context.screenX, context.screenY)))
                    .put("nativeCopySourceBounds", JSONArray(listOf(context.copySource.left, context.copySource.top,
                        context.copySource.right, context.copySource.bottom)))
                    .put("displayBounds", JSONArray(listOf(context.displayBounds.left, context.displayBounds.top,
                        context.displayBounds.right, context.displayBounds.bottom)))
                    .put("captureHelper", "NativeFrameCapture.kt: captureNativeFrame, original node-sized PixelCopy output")
                    .put("bitmapSize", JSONArray(listOf(image.width, image.height)))
                rows.put(row)
                File(output, "frames.json").writeText(JSONObject().put("frames", rows).toString(2))
                assertSame(initialClock, clock)
                assertEquals("Capture must not advance Engine or mutate presentation", before, values)
                assertEquals(context.density, context.resourceDensity, 0f)
                assertEquals(1f, context.fontScale, 0f)
                assertTrue(context.copySource.left >= 0 && context.copySource.top >= 0 &&
                    context.copySource.right <= context.width && context.copySource.bottom <= context.height)
                assertTrue("The app crop excludes system bars", context.displayBounds.left >= context.insetLeft &&
                    context.displayBounds.top >= context.insetTop && context.displayBounds.right <= context.width - context.insetRight &&
                    context.displayBounds.bottom <= context.height - context.insetBottom)
                assertEquals(context.copySource.right - context.copySource.left, image.width)
                assertEquals(context.copySource.bottom - context.copySource.top, image.height)
                assertEquals(access.name, values.access)
                assertEquals("Exact reveal at $label", fade.getValueFromNanos(values.elapsedNanos), values.opacity, 0f)
                assertEquals("Exact release at $label", release.getValueFromNanos(values.elapsedNanos), values.release, 0f)
                assertEquals("Exact in-flight grip at $label", grip.getValueFromNanos(values.elapsedNanos), values.grip, 0f)
                assertTrue("The native rail remains painted at $label", painted > 0)
                assertTrue("The reveal still owns its timeline at $label", values.active > 0)
            } finally { image.recycle() }
        }
        compose.mainClock.advanceTimeBy(elapsedMillis, ignoreFrameDuration = true)
        compose.waitForIdle()
        sample("before-root-reduction", MotionAccess.Standard)
        compose.runOnIdle { root.settings.value = root.settings.value.copy(reducedMotion = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        sample("after-root-reduction", MotionAccess.Reduced)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        sample("next-reduced-frame", MotionAccess.Reduced)
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0f, motion.opacity(), 0f)
            assertEquals(0f, motion.releaseOffset(), 0f)
            assertEquals(0, clock.activeCount)
        }
    }
}

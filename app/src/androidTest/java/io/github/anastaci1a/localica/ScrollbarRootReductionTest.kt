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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
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

/** App settings must reach the production root clock, rather than only a fixture local. */
class ScrollbarRootReductionTest {
    @get:Rule val compose = applicationRootComposeRule()

    @Test fun rootReductionDuringGripAndReleaseKeepsExactBaseCurves() = inspect(64)
    @Test fun rootReductionDuringIdleDelayKeepsRevealAlive() = inspect(800)
    @Test fun rootReductionDuringFadeKeepsVisibleScrollbar() = inspect(1296)

    private fun inspect(elapsedMillis: Long) {
        val root = LocalicaUiRootState(InterfaceSettings())
        lateinit var clock: UiClock
        lateinit var motion: ScrollbarMotion
        compose.setContent {
            LocalicaUiRoot(root) { LocalicaTheme {
                val owner = ownerClock()
                val rail = remember(owner) { ScrollbarMotion(owner) }
                val reduced = LocalEngineContext.current.access == MotionAccess.Reduced
                SideEffect { clock = owner; motion = rail; rail.access(reduced) }
                DisposableEffect(rail) { onDispose { rail.dispose() } }
                Box(Modifier.size(120.dp, 200.dp).background(Color.White).testTag("root-reduction-rail")) {
                    Box(Modifier.scrollbarPresentation(rail, { 40f + rail.releaseOffset() * 100f }, true)
                        .size(8.dp, 40.dp).background(Color.Green))
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
                val values = compose.runOnIdle {
                    val nanos = clock.presentedStamp.nanos - origin
                    val row = JSONObject().put("label", label).put("source", BuildConfig.SOURCE_SHA)
                        .put("access", clock.context!!.access.name).put("elapsedNanos", nanos)
                        .put("clockIdentity", System.identityHashCode(clock)).put("active", clock.activeCount)
                        .put("opacity", motion.opacity()).put("release", motion.releaseOffset())
                        .put("grip", motion.scale()).put("paintedPixels", painted)
                    Triple(row, nanos, listOf(motion.opacity(), motion.releaseOffset(), motion.scale()))
                }
                rows.put(values.first)
                File(output, "frames.json").writeText(JSONObject().put("frames", rows).toString(2))
                assertEquals(access.name, values.first.getString("access"))
                assertEquals("Exact reveal at $label", fade.getValueFromNanos(values.second), values.third[0], 0f)
                assertEquals("Exact release at $label", release.getValueFromNanos(values.second), values.third[1], 0f)
                assertEquals("Exact in-flight grip at $label", grip.getValueFromNanos(values.second), values.third[2], 0f)
                assertTrue("The native rail remains painted at $label", painted > 0)
                assertTrue("The reveal still owns its timeline at $label", values.first.getInt("active") > 0)
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

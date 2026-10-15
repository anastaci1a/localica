package io.github.anastaci1a.localica

import android.animation.ValueAnimator
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated diagnostic: system inheritance is not yet an app contract. */
class SystemMotionDiagnosticTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compareSystemDisabledAndAppReducedMotionWithTheSameContact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getString(resolver, key)
        val previouslyEnabled = ValueAnimator.areAnimatorsEnabled()
        var touching = false
        fun writeScale(value: String?) {
            val command = if (value == null) "settings delete global $key"
                else "settings put global $key '${value.replace("'", "'\\''")}'"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
            compose.waitUntil(5000) { Settings.Global.getString(resolver, key) == value }
        }
        try {
            writeScale("1")
            compose.waitUntil(5000) { ValueAnimator.areAnimatorsEnabled() }
            var reduced by mutableStateOf(false)
            val raster = GlassChoiceRaster(compose)
            raster.setContent(listOf("First", "Second", "Third"), reducedMotion = { reduced })
            compose.mainClock.autoAdvance = false
            fun contact(label: String): Pair<Rect, Rect> {
                val rest = raster.bounds()
                touching = true
                compose.onNodeWithText("First").performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(300)
                val held = raster.bounds()
                compose.onNodeWithText("First").performTouchInput { up() }
                touching = false
                compose.mainClock.advanceTimeBy(2000)
                val settled = raster.bounds()
                android.util.Log.i("SystemMotionDiagnostic", "$label systemEnabled=${ValueAnimator.areAnimatorsEnabled()} appReduced=$reduced rest=$rest held=$held settled=$settled")
                assertEquals(label, rest.width, settled.width, 1f)
                assertEquals(label, rest.height, settled.height, 1f)
                return rest to held
            }
            val baseline = contact("enabled")
            assertTrue("Enabled baseline must visibly deform", baseline.second.width > baseline.first.width && baseline.second.height > baseline.first.height)
            writeScale("0")
            compose.waitUntil(5000) { !ValueAnimator.areAnimatorsEnabled() }
            contact("system-disabled") // Measure; do not invent a system-inheritance contract.
            writeScale("1")
            compose.waitUntil(5000) { ValueAnimator.areAnimatorsEnabled() }
            compose.runOnIdle { reduced = true }
            compose.mainClock.advanceTimeByFrame()
            val appReduced = contact("app-reduced")
            assertEquals("App reduced motion removes width deformation", appReduced.first.width, appReduced.second.width, 1f)
            assertEquals("App reduced motion removes height deformation", appReduced.first.height, appReduced.second.height, 1f)
        } finally {
            if (touching) runCatching { compose.onNodeWithText("First").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
            writeScale(previous)
            compose.waitUntil(5000) { ValueAnimator.areAnimatorsEnabled() == previouslyEnabled }
        }
    }
}

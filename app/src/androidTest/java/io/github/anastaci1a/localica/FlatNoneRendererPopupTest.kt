package io.github.anastaci1a.localica

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** #775: switch the actual preference through its retained native renderer popup. */
class FlatNoneRendererPopupTest {
    @Test fun layerlessParentContributesNoSampledSceneBounds() {
        val structure = mutableListOf<io.github.anastaci1a.localica.ui.SampledSceneDependency>()
        val stale = io.github.anastaci1a.localica.ui.FrostedBackdrop(null, false)
        val identity = io.github.anastaci1a.localica.ui.BackdropRegistration.Identity
        assertNull(io.github.anastaci1a.localica.ui.sampledSceneBounds(stale, identity, { identity }, structure))
        assertFalse(structure.single().recorded)
        assertTrue(structure.single().released)
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test fun rendererPopupCanLeaveFlatNoneForSingleToneAndNormal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        var step = "settings-ingress"
        fun visible(selector: BySelector): androidx.test.uiautomator.UiObject2 {
            val found = device.findFresh(selector, 15_000)
            if (found != null) return found
            val failure = IllegalArgumentException("Missing $selector")
            runCatching {
                val output = java.io.File(context.getExternalFilesDir(null), "renderer-popup-775").apply { mkdirs() }
                device.dumpWindowHierarchy(java.io.File(output, "$step.xml"))
                device.takeScreenshot(java.io.File(output, "$step.png"))
                java.io.File(output, "$step.json").writeText(org.json.JSONObject()
                    .put("missingSelector", selector.toString()).put("step", step)
                    .put("persistedRenderer", preferences.read().glass.renderer.name).toString(2))
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs, MainTab.Albums),
                reducedMotion = true, glass = GlassAppearance(renderer = GlassRendererMode.FlatNone)))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var originalActivity: MainActivity
                scenario.onActivity { originalActivity = it }
                visible(By.desc("Library")).click()
                visible(By.desc("More")).click()
                visible(By.text("Settings")).click()
                visible(By.text("Interface")).click()
                visible(By.text("Glass")).click()
                for ((index, target) in listOf(GlassRendererMode.SingleToneBlur, GlassRendererMode.FlatNone,
                    GlassRendererMode.Normal, GlassRendererMode.FlatNone, GlassRendererMode.SingleToneBlur).withIndex()) {
                    step = "$index-${target.name}"
                    visible(By.text("Glass renderer")).click()
                    visible(By.text(target.label)).click()
                    visible(By.text("Select")).click()
                    await(15_000) { preferences.read().glass.renderer == target }
                    // The departing dialog has the same title as its underlying
                    // settings row. Require its actual dismissal before the next
                    // iteration can use that row to open a new renderer popup.
                    await(15_000) { device.findFresh(By.text("Select"), 0) == null }
                    visible(By.text("Glass renderer"))
                    scenario.onActivity { assertSame("Switching retains the Activity and settings page", originalActivity, it) }
                    assertNull(device.findObject(By.textContains("keeps stopping")))
                }
            }
        } finally { preferences.write(original) }
    }
}

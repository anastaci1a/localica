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
        fun visible(selector: BySelector) = requireNotNull(device.findFresh(selector, 15_000)) { "Missing $selector" }
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
                for (target in listOf(GlassRendererMode.SingleToneBlur, GlassRendererMode.FlatNone,
                    GlassRendererMode.Normal, GlassRendererMode.FlatNone, GlassRendererMode.SingleToneBlur)) {
                    visible(By.text("Glass renderer")).click()
                    visible(By.text(target.label)).click()
                    visible(By.text("Select")).click()
                    visible(By.text("Glass renderer"))
                    await(15_000) { preferences.read().glass.renderer == target }
                    scenario.onActivity { assertSame("Switching retains the Activity and settings page", originalActivity, it) }
                    assertNull(device.findObject(By.textContains("keeps stopping")))
                }
            }
        } finally { preferences.write(original) }
    }
}

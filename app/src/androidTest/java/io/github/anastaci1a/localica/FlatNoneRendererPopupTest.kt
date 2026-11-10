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

/** #775: the actual preference must switch through real touch and accessibility routes. */
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
    @Test fun rendererPopupCanLeaveFlatNoneForSingleToneAndNormal() = switchFromPopup(false)

    @SdkSuppress(minSdkVersion = 33)
    @Test fun rendererPopupAccessibilityCanLeaveFlatNoneForSingleToneAndNormal() = switchFromPopup(true)

    private fun switchFromPopup(accessibility: Boolean) {
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
        fun activate(selector: BySelector) {
            if (accessibility) assertTrue("The real control must accept its framework accessibility action: $selector",
                visible(selector).clickSystemAction())
            else visible(selector).click()
        }
        try {
            // An earlier real-app walk can have left an asynchronous clear behind
            // its deleted cache MP3. Retire only that known test-owned playback;
            // an unrelated error is a failed fixture precondition, not a missed tap.
            lateinit var playback: io.github.anastaci1a.localica.playback.PlaybackConnection
            instrumentation.runOnMainSync { playback = io.github.anastaci1a.localica.playback.PlaybackConnection(context) }
            try {
                await(15_000) { playback.state.value.connected && !playback.queues.state.value.loading }
                val active = playback.queues.state.value.library.active
                if (active?.entries?.all { it.track.id == "flat-none-walk-740" } == true) {
                    instrumentation.runOnMainSync { playback.clear() }
                    await(15_000) {
                        var nativeEmpty = false
                        instrumentation.runOnMainSync { nativeEmpty = playback.queues.playbackSample() == null }
                        playback.queues.state.value.library.activeId == null && nativeEmpty
                    }
                } else assertNull("The touch fixture must not start behind an unrelated playback failure", playback.state.value.problem)
            } finally { instrumentation.runOnMainSync { playback.close() } }
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs, MainTab.Albums),
                reducedMotion = true, glass = GlassAppearance(renderer = GlassRendererMode.FlatNone)))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var originalActivity: MainActivity
                scenario.onActivity { originalActivity = it }
                activate(By.desc("Library"))
                activate(By.desc("More"))
                activate(By.text("Settings"))
                activate(By.text("Interface"))
                activate(By.text("Glass"))
                for ((index, target) in listOf(GlassRendererMode.SingleToneBlur, GlassRendererMode.FlatNone,
                    GlassRendererMode.Normal, GlassRendererMode.FlatNone, GlassRendererMode.SingleToneBlur).withIndex()) {
                    step = "$index-${target.name}"
                    activate(By.text("Glass renderer"))
                    activate(By.text(target.label))
                    activate(By.text("Select"))
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

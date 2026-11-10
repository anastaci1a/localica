package io.github.anastaci1a.localica

import android.content.Intent
import android.view.MotionEvent
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** #775: preserve the real touch route and bind selected bounds to delivered input. */
@SdkSuppress(minSdkVersion = 33)
class FlatNoneRendererTouchDiagnosticTest {
    @Test fun originalUiObjectClicks() = runTouchRoute(false)
    @Test fun explicitDeviceCoordinates() = runTouchRoute(true)

    private fun runTouchRoute(explicit: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        val route = if (explicit) "explicit-device" else "original-uiobject"
        val output = File(context.getExternalFilesDir(null), "renderer-touch-775/$route").apply { mkdirs() }
        val events = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        fun record(type: String, data: JSONObject = JSONObject()) {
            data.put("type", type).put("uptimeMs", android.os.SystemClock.uptimeMillis())
            events.add(data)
        }
        var step = "settings-ingress"
        fun visible(selector: BySelector) = requireNotNull(device.findFresh(selector, 15_000)) { "Missing $selector" }
        fun tap(selector: BySelector) {
            val node = visible(selector)
            val bounds = node.visibleBounds
            record("requestedTap", JSONObject().put("step", step).put("selector", selector.toString())
                .put("bounds", bounds.toShortString()).put("x", bounds.centerX()).put("y", bounds.centerY())
                .put("route", route))
            if (explicit) assertTrue("The device must inject the requested touch", device.click(bounds.centerX(), bounds.centerY()))
            else node.click()
        }
        record("display", JSONObject().put("width", device.displayWidth).put("height", device.displayHeight)
            .put("rotation", device.displayRotation))
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs, MainTab.Albums),
                reducedMotion = true, glass = GlassAppearance(renderer = GlassRendererMode.FlatNone)))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var activity: MainActivity
                lateinit var callback: Window.Callback
                scenario.onActivity {
                    activity = it
                    callback = it.window.callback
                    it.window.callback = object : Window.Callback by callback {
                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            val data = JSONObject().put("action", event.actionMasked).put("pointerCount", event.pointerCount)
                                .put("x", event.x).put("y", event.y).put("rawX", event.rawX).put("rawY", event.rawY)
                                .put("downTime", event.downTime).put("eventTime", event.eventTime)
                            record("activityTouch", data)
                            val consumed = callback.dispatchTouchEvent(event)
                            record("activityTouchResult", JSONObject().put("action", event.actionMasked).put("consumed", consumed))
                            return consumed
                        }
                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                            record("activityFocus", JSONObject().put("focused", hasFocus))
                            callback.onWindowFocusChanged(hasFocus)
                        }
                    }
                }
                try {
                    tap(By.desc("Library"))
                    tap(By.desc("More"))
                    tap(By.text("Settings"))
                    tap(By.text("Interface"))
                    tap(By.text("Glass"))
                    for ((index, target) in listOf(GlassRendererMode.SingleToneBlur, GlassRendererMode.FlatNone,
                        GlassRendererMode.Normal, GlassRendererMode.FlatNone, GlassRendererMode.SingleToneBlur).withIndex()) {
                        step = "$index-${target.name}"
                        tap(By.text("Glass renderer"))
                        tap(By.text(target.label))
                        tap(By.text("Select"))
                        await(15_000) { preferences.read().glass.renderer == target }
                        await(15_000) { device.findFresh(By.text("Select"), 0) == null }
                        visible(By.text("Glass renderer"))
                        scenario.onActivity { assertSame("Touch switching retains the Activity", activity, it) }
                        assertNull(device.findObject(By.textContains("keeps stopping")))
                        record("committedRenderer", JSONObject().put("step", step).put("renderer", target.name))
                    }
                } catch (failure: Throwable) {
                    runCatching {
                        device.dumpWindowHierarchy(File(output, "failure-$step.xml"))
                        device.takeScreenshot(File(output, "failure-$step.png"))
                        val windows = JSONArray()
                        instrumentation.uiAutomation.windows.forEach { window ->
                            val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
                            windows.put(JSONObject().put("id", window.id).put("focused", window.isFocused)
                                .put("active", window.isActive).put("bounds", bounds.toShortString())
                                .put("title", window.title?.toString()).put("package", window.root?.packageName?.toString()))
                        }
                        File(output, "failure-$step.json").writeText(JSONObject().put("error", failure.toString())
                            .put("step", step).put("renderer", preferences.read().glass.renderer.name)
                            .put("windows", windows).toString(2))
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                } finally {
                    runCatching { scenario.onActivity { it.window.callback = callback } }
                }
            }
        } finally {
            preferences.write(original)
            val snapshot = synchronized(events) { events.toList() }
            File(output, "events.json").writeText(JSONArray(snapshot).toString(2))
        }
    }
}

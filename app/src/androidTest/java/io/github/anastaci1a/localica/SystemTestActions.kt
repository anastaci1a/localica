package io.github.anastaci1a.localica

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt

internal fun ComposeContentTestRule.nativePointer(node: SemanticsNodeInteraction, holdMillis: Int = 0) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.waitForIdle()
    awaitExpectedWindowFocus(node)
    val semantic = node.fetchSemanticsNode()
    val origin = IntArray(2)
    runOnUiThread { (semantic.root as ViewRootForTest).view.getLocationOnScreen(origin) }
    val center = semantic.boundsInRoot.center
    val x = origin[0] + center.x.roundToInt()
    val y = origin[1] + center.y.roundToInt()
    if (holdMillis == 0) assertTrue(device.click(x, y))
    else device.executeShellCommand("input swipe $x $y $x $y $holdMillis")
    waitForIdle()
}

/** Send Back through Android itself without racing a transitioning window's input target. */
internal fun pressSystemBack() {
    assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
        .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
}

/** DocumentsUI is a system window: invoke its real action, not a stale screen coordinate. */
internal fun UiObject2.clickSystemAction(): Boolean {
    val expectedText = text
    val expectedDescription = contentDescription
    val expectedResource = resourceName
    val expectedPackage = applicationPackage
    fun visit(node: AccessibilityNodeInfo): Boolean {
        if (node.isVisibleToUser && node.isEnabled && node.packageName?.toString() == expectedPackage &&
            node.text?.toString() == expectedText && node.contentDescription?.toString() == expectedDescription &&
            node.viewIdResourceName == expectedResource) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (visit(child)) return true
        }
        return false
    }
    return InstrumentationRegistry.getInstrumentation().uiAutomation.windows.any { window -> window.root?.let(::visit) == true }
}

/** Semantics can remain queryable without native input focus. Check the actual
 * expected app window before native input; external picker actions use their own host. */
internal fun androidx.compose.ui.test.junit4.ComposeContentTestRule.awaitExpectedWindowFocus(
    node: androidx.compose.ui.test.SemanticsNodeInteraction,
) {
    // Initialize interactive-window retrieval before waiting, not only after a failure.
    // This makes diagnostics useful; it does not substitute for real native input focus.
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    UiDevice.getInstance(instrumentation)
    val view = (node.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
    fun windows() = runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.windows.joinToString { window ->
        "id=${window.id} type=${window.type} focused=${window.isFocused} active=${window.isActive} package=${window.root?.packageName}"
    } }.getOrElse { "window diagnostics unavailable: ${it.javaClass.simpleName}" }
    try {
        waitUntil(5000) { runOnUiThread { view.isAttachedToWindow && view.hasWindowFocus() } }
        val state = runOnUiThread { "attached=${view.isAttachedToWindow} focus=${view.hasWindowFocus()}" }
        android.util.Log.i("NativeBackTest", "expected window ready: $state; ${windows()}")
    } catch (failure: Throwable) {
        runCatching {
            val state = runOnUiThread { "attached=${view.isAttachedToWindow} focus=${view.hasWindowFocus()}" }
            android.util.Log.e("NativeBackTest", "expected window precondition failed: $state; ${windows()}")
        }.exceptionOrNull()?.let(failure::addSuppressed)
        for (command in listOf("dumpsys window windows", "dumpsys activity activities")) {
            runCatching {
                val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                ).bufferedReader().use { it.readText() }
                android.util.Log.e("NativeBackTest", "Failure diagnostics: $command")
                output.chunked(3000).forEach { android.util.Log.e("NativeBackTest", it) }
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}

/**
 * Find [selector] from the app's current accessibility tree, not UiAutomation's cached copy (#620).
 *
 * Compose sends semantics events only while an accessibility service is enabled, and UiAutomation
 * is not one, so under test nothing invalidates the cache after an in-place Compose change. On
 * API 34+ (seen on API 36) uiautomator then returns the old node for seconds, or until an unrelated
 * system event. Use this for any uiautomator text/description lookup that follows a Compose change
 * in the same window.
 */
internal fun UiDevice.findFresh(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long): UiObject2? {
    val automation = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags)
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        findObject(selector)?.let { return it }
        if (android.os.SystemClock.uptimeMillis() >= deadline) return null
        android.os.SystemClock.sleep(100)
    }
}

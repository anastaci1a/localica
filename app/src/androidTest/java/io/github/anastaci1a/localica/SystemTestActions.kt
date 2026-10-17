package io.github.anastaci1a.localica

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue

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

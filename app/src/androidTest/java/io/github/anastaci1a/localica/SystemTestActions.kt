package io.github.anastaci1a.localica

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiObject2
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

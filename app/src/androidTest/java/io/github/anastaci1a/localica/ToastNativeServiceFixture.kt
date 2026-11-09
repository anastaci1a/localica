package io.github.anastaci1a.localica

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.*

/** Real Android service admission. No Compose accessibility reflection or fabricated event. */
internal class ToastNativeServiceFixture(private val compose: ComposeContentTestRule) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
    private val component = "io.github.anastaci1a.localica.debug.test/io.github.anastaci1a.localica.ToastFocusFixtureService"
    private val priorComponents = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { it.id }.toSet()
    private val priorServices = shell("settings get secure enabled_accessibility_services")
    private val priorEnabled = shell("settings get secure accessibility_enabled")
    private val flags = automation.serviceInfo.flags
    private fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
    }
    private fun restore(name: String, value: String) {
        if (value == "null") shell("settings delete secure $name")
        else if (value.isEmpty()) shell("content insert --uri content://settings/secure --bind name:s:$name --bind value:s:")
        else shell("settings put secure $name $value")
    }
    init {
        try {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            val requested = listOfNotNull(priorServices.takeUnless { it == "null" || it.isEmpty() }, component).distinct().joinToString(":")
            shell("settings put secure enabled_accessibility_services $requested")
            assertEquals(requested, shell("settings get secure enabled_accessibility_services"))
            shell("settings put secure accessibility_enabled 1")
            compose.waitUntil(10000) { manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.name == "io.github.anastaci1a.localica.ToastFocusFixtureService" } && manager.isTouchExplorationEnabled }
        } catch (failure: Throwable) { close(); throw failure }
    }
    private fun find(node: AccessibilityNodeInfo, tag: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == tag) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { find(child, tag)?.let { return it } } finally { child.recycle() }
        }
        return null
    }
    fun node(tag: String): AccessibilityNodeInfo? = automation.rootInActiveWindow?.let { root ->
        try { find(root, tag) } finally { root.recycle() }
    }
    fun act(tag: String, action: Int) {
        var found: AccessibilityNodeInfo? = null
        compose.waitUntil(5000) { found = node(tag); found != null }
        try { assertTrue("Native action $action on $tag", found!!.performAction(action)) } finally { found?.recycle() }
        compose.waitForIdle()
    }
    override fun close() {
        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        restore("enabled_accessibility_services", priorServices)
        try {
            compose.waitUntil(10000) { manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .map { it.id }.toSet() == priorComponents }
        } finally { restore("accessibility_enabled", priorEnabled) }
        assertEquals(priorServices, shell("settings get secure enabled_accessibility_services"))
        assertEquals(priorEnabled, shell("settings get secure accessibility_enabled"))
    }
}

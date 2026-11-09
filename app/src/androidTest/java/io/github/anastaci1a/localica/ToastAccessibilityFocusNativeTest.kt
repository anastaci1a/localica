package io.github.anastaci1a.localica

import android.app.UiAutomation
import java.io.FileInputStream
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.toast.*
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.components.ObserveToastAccessibilityFocus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToastAccessibilityFocusNativeTest {
    @get:Rule val compose = localicaComposeRule()

    // Compose exports virtual resource tags but does not implement the provider's by-id search.
    // Traverse the actual native accessibility tree, just as a service discovers virtual children.
    private fun findTagged(node: AccessibilityNodeInfo, tag: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == tag) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findTagged(child, tag)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    @Test fun realVirtualFocusPausesAndClearOrOtherFocusResumesWithoutReplacingProvider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
        val serviceComponent = "io.github.anastaci1a.localica.debug.test/io.github.anastaci1a.localica.ToastFocusFixtureService"
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        val priorComponents = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { it.id }.toSet()
        val priorServices = shell("settings get secure enabled_accessibility_services")
        val priorEnabled = shell("settings get secure accessibility_enabled")
        fun restoreSetting(name: String, value: String) {
            if (value == "null") shell("settings delete secure $name")
            else if (value.isEmpty()) shell("content insert --uri content://settings/secure --bind name:s:$name --bind value:s:")
            else shell("settings put secure $name $value")
        }
        val original = automation.serviceInfo
        val flags = original.flags
        automation.serviceInfo = original.apply { this.flags = flags or
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        var observe by mutableStateOf(false)
        var parent: ViewGroup? = null
        var forwarded = 0
        val queue = ToastQueue(clockMillis = { 0L })
        val lease = queue.attachPresenter()
        queue.showToast(ToastMessage("Reading"))
        val item = queue.state.value.current!!
        lease.presented(item.id, item.generation, 3000)
        lease.marqueeCompleted(item.id, item.generation)
        lease.pause(ToastPause.Background, false)
        try {
            val enabled = priorServices.takeUnless { it == "null" || it.isEmpty() }
            val requested = listOfNotNull(enabled, serviceComponent).joinToString(":")
            shell("settings put secure enabled_accessibility_services $requested")
            assertEquals(requested, shell("settings get secure enabled_accessibility_services"))
            shell("settings put secure accessibility_enabled 1")
            compose.waitUntil(10000) {
                val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                    it.resolveInfo.serviceInfo.name == "io.github.anastaci1a.localica.ToastFocusFixtureService"
                } && manager.isTouchExplorationEnabled
            }
            compose.setContent { LocalicaTheme {
                parent = LocalView.current.parent as ViewGroup
                if (observe) ObserveToastAccessibilityFocus(setOf("toast713-body", "toast713-action")) {
                    lease.pause(ToastPause.Accessibility, it)
                }
                Column(Modifier.semantics { testTagsAsResourceId = true }) {
                    Text("Reading", Modifier.testTag("toast713-body"))
                    Text("Open", Modifier.testTag("toast713-action"))
                    Text("Elsewhere", Modifier.testTag("toast713-other"))
                }
            } }
            compose.waitUntil(5000) {
                instrumentation.targetContext.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
            }
            val originalDelegate = compose.runOnIdle { ViewCompat.getAccessibilityDelegate(parent!!) }
            val sentinel = object : AccessibilityDelegateCompat() {
                override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: android.view.View,
                    event: android.view.accessibility.AccessibilityEvent): Boolean {
                    forwarded++
                    return originalDelegate?.onRequestSendAccessibilityEvent(host, child, event)
                        ?: super.onRequestSendAccessibilityEvent(host, child, event)
                }
            }
            compose.runOnIdle { ViewCompat.setAccessibilityDelegate(parent!!, sentinel); observe = true }
            compose.waitForIdle()
            fun act(tag: String, action: Int) {
                var node: AccessibilityNodeInfo? = null
                compose.waitUntil(5000) {
                    node = automation.rootInActiveWindow?.let { root ->
                        try { findTagged(root, tag) } finally { root.recycle() }
                    }
                    node != null
                }
                try { assertTrue("Native action $action on $tag", node!!.performAction(action)) }
                finally { node?.recycle() }
                compose.waitForIdle()
            }
            fun paused() = ToastPause.Accessibility in queue.state.value.pauses
            assertFalse(paused())
            act("toast713-body", AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            assertTrue("Actual accessibility focus pauses dwell", paused())
            assertNull(queue.state.value.deadline)
            act("toast713-body", AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            assertFalse(paused())
            assertNotNull(queue.state.value.deadline)
            act("toast713-action", AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            assertTrue(paused())
            act("toast713-other", AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            assertFalse("Another virtual focus releases only this toast pause", paused())
            assertTrue("Existing parent delegate still receives native events", forwarded > 0)
            compose.runOnIdle { observe = false }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame("Last subscriber restores existing delegate", sentinel, ViewCompat.getAccessibilityDelegate(parent!!))
                ViewCompat.setAccessibilityDelegate(parent!!, originalDelegate)
            }
        } finally {
            lease.release()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            restoreSetting("enabled_accessibility_services", priorServices)
            try {
                compose.waitUntil(10000) {
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                        .map { it.id }.toSet() == priorComponents
                }
            } finally { restoreSetting("accessibility_enabled", priorEnabled) }
            assertEquals(priorServices, shell("settings get secure enabled_accessibility_services"))
            assertEquals(priorEnabled, shell("settings get secure accessibility_enabled"))
        }
    }
}

package io.github.anastaci1a.localica.ui.components

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Observe real virtual accessibility focus without replacing Compose's provider or input focus.
 * Outgoing events are unsealed: event.source cannot be queried here. The existing provider exposes
 * its current accessibility focus synchronously, including the resource tag of that virtual node. */
@Composable internal fun ObserveToastAccessibilityFocus(tags: Set<String>, focused: (Boolean) -> Unit) {
    val view = LocalView.current
    val callback = rememberUpdatedState(focused)
    DisposableEffect(view, tags) {
        val parent = requireNotNull(view.parent as? ViewGroup)
        val release = ToastFocusParents.attach(parent, view, tags) { callback.value(it) }
        onDispose { release() }
    }
}

private object ToastFocusParents {
    // The delegate installed on the native parent owns the live host. Neither side of this lookup
    // keeps a retired native window alive, even if its composition missed normal disposal.
    private val parents = WeakHashMap<ViewGroup, WeakReference<ToastFocusParent>>()
    fun attach(parent: ViewGroup, view: View, tags: Set<String>, changed: (Boolean) -> Unit): () -> Unit {
        val prior = ViewCompat.getAccessibilityDelegate(parent)
        val host = parents[parent]?.get()?.takeIf { it === prior }
            ?: ToastFocusParent(parent, prior).also {
                parents[parent] = WeakReference(it)
                ViewCompat.setAccessibilityDelegate(parent, it)
            }
        val key = Any()
        host.listeners[key] = ToastFocusListener(view, tags, changed)
        host.readFocus(view)
        return {
            host.listeners.remove(key)
            changed(false)
            if (host.listeners.isEmpty()) {
                if (ViewCompat.getAccessibilityDelegate(parent) === host)
                    ViewCompat.setAccessibilityDelegate(parent, host.prior)
                if (parents[parent]?.get() === host) parents.remove(parent)
            }
        }
    }
}

private data class ToastFocusListener(val view: View, val tags: Set<String>, val changed: (Boolean) -> Unit)
private class ToastFocusParent(private val parent: ViewGroup, val prior: AccessibilityDelegateCompat?) : AccessibilityDelegateCompat() {
    val listeners = linkedMapOf<Any, ToastFocusListener>()
    fun readFocus(child: View) {
        // ViewCompat wraps a native provider but its compat findFocus default does not forward.
        // Query the existing native provider directly; Compose still owns that provider.
        val info = child.accessibilityNodeProvider?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        try {
            val tag = info?.takeIf { it.isAccessibilityFocused }?.viewIdResourceName
            listeners.values.toList().forEach { it.changed(it.view === child && tag in it.tags) }
        } finally { info?.recycle() }
    }
    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) readFocus(child)
        return prior?.onRequestSendAccessibilityEvent(host, child, event)
            ?: super.onRequestSendAccessibilityEvent(host, child, event)
    }
    override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
        prior?.dispatchPopulateAccessibilityEvent(host, event) ?: super.dispatchPopulateAccessibilityEvent(host, event)
    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
        if (prior != null) prior.onInitializeAccessibilityEvent(host, event) else super.onInitializeAccessibilityEvent(host, event)
    }
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        if (prior != null) prior.onInitializeAccessibilityNodeInfo(host, info) else super.onInitializeAccessibilityNodeInfo(host, info)
    }
    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
        if (prior != null) prior.onPopulateAccessibilityEvent(host, event) else super.onPopulateAccessibilityEvent(host, event)
    }
    override fun sendAccessibilityEvent(host: View, eventType: Int) {
        if (prior != null) prior.sendAccessibilityEvent(host, eventType) else super.sendAccessibilityEvent(host, eventType)
    }
    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
        if (prior != null) prior.sendAccessibilityEventUnchecked(host, event) else super.sendAccessibilityEventUnchecked(host, event)
    }
    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? =
        prior?.getAccessibilityNodeProvider(host) ?: super.getAccessibilityNodeProvider(host)
    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
        prior?.performAccessibilityAction(host, action, args) ?: super.performAccessibilityAction(host, action, args)
}

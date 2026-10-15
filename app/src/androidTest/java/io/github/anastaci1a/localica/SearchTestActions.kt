package io.github.anastaci1a.localica

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/** End editing, await the popup window's IME handoff, then dismiss that window. */
internal fun ComposeContentTestRule.dismissSearchWithSystemBack() {
    val popup = onNodeWithTag("list-search-popup").fetchSemanticsNode()
    val view = (popup.root as ViewRootForTest).view
    val decor = view.rootView as ViewGroup
    lateinit var observer: View
    val animations = mutableSetOf<WindowInsetsAnimationCompat>()
    var bottom: Int? = null
    var visible: Boolean? = null
    fun record(insets: WindowInsetsCompat) {
        bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        visible = insets.isVisible(WindowInsetsCompat.Type.ime())
    }
    fun state(): String = "focus=${view.hasWindowFocus()} imeVisible=$visible imeBottom=$bottom animations=${animations.size}"
    runOnUiThread {
        // A separate zero-sized child observes the native window without replacing
        // Compose's own insets listener or animation callback.
        observer = View(view.context)
        ViewCompat.setOnApplyWindowInsetsListener(observer) { _, insets -> record(insets); insets }
        ViewCompat.setWindowInsetsAnimationCallback(observer, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animations += animation
            }
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                record(insets)
                animations.clear()
                animations.addAll(runningAnimations.filter { it.typeMask and WindowInsetsCompat.Type.ime() != 0 })
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimationCompat) { animations -= animation }
        })
        // Observe before the content can consume legacy inset dispatch; return
        // the unchanged insets so the app still receives exactly that dispatch.
        decor.addView(observer, 0, ViewGroup.LayoutParams(0, 0))
        ViewCompat.requestApplyInsets(observer)
    }
    try {
        waitUntil(5000) { runOnUiThread { bottom != null && view.hasWindowFocus() } }
        if (onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()) {
            runOnUiThread { Log.i("SearchBackTest", "before editing Back: ${state()}") }
            pressSystemBack()
            waitUntil(5000) { onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty() }
            runOnUiThread { Log.i("SearchBackTest", "editor focus cleared: ${state()}") }
        }
        waitUntil(5000) {
            runOnUiThread {
                val native = ViewCompat.getRootWindowInsets(view)
                view.hasWindowFocus() && visible == false && bottom == 0 && animations.isEmpty() &&
                    native != null && !native.isVisible(WindowInsetsCompat.Type.ime()) && native.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
            }
        }
        runOnUiThread { Log.i("SearchBackTest", "before popup Back: ${state()}") }
        pressSystemBack()
        waitUntil(5000) { onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty() }
    } catch (failure: Throwable) {
        runCatching {
            runOnUiThread { Log.e("SearchBackTest", "Back sequence failed: ${state()}") }
            Log.e("SearchBackTest", onAllNodes(isRoot()).printToString())
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    } finally {
        runOnUiThread {
            ViewCompat.setWindowInsetsAnimationCallback(observer, null)
            ViewCompat.setOnApplyWindowInsetsListener(observer, null)
            decor.removeView(observer)
        }
    }
}

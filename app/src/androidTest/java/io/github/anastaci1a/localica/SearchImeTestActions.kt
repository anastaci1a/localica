package io.github.anastaci1a.localica

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/** Observe the expected popup's native handoff without replacing application callbacks. */
internal fun ComposeContentTestRule.awaitSearchImeHandoff(action: () -> Unit) {
    val editor = onNode(hasSetTextAction())
    val view = (editor.fetchSemanticsNode().root as ViewRootForTest).view
    val decor = view.rootView as ViewGroup
    lateinit var observer: View
    val animations = mutableSetOf<WindowInsetsAnimationCompat>()
    var observed: WindowInsetsCompat? = null
    runOnUiThread {
        // Independent first child observes legacy dispatch before content consumes it.
        // It never replaces Compose callbacks and returns insets unchanged.
        observer = View(view.context)
        ViewCompat.setOnApplyWindowInsetsListener(observer) { _, insets -> observed = insets; insets }
        ViewCompat.setWindowInsetsAnimationCallback(observer, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animations += animation
            }
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                observed = insets
                animations.clear()
                animations.addAll(runningAnimations.filter { it.typeMask and WindowInsetsCompat.Type.ime() != 0 })
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimationCompat) { animations -= animation }
        })
        decor.addView(observer, 0, ViewGroup.LayoutParams(0, 0))
        ViewCompat.requestApplyInsets(observer)
    }
    fun phase(name: String) = runOnUiThread {
        android.util.Log.i("SearchImeHandoff", "${android.os.SystemClock.uptimeMillis()} phase=$name focus=${view.hasWindowFocus()} ime=${observed?.isVisible(WindowInsetsCompat.Type.ime())} bottom=${observed?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} animations=${animations.size}")
    }
    try {
        waitUntil(5000) { runOnUiThread { observed != null && view.hasWindowFocus() } }
        phase("before editor action")
        action()
        waitUntil(5000) { onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty() }
        phase("editor focus ended")
        waitUntil(5000) { runOnUiThread {
            val native = ViewCompat.getRootWindowInsets(view)
            fun WindowInsetsCompat.hidden() = !isVisible(WindowInsetsCompat.Type.ime()) && getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
            view.hasWindowFocus() && observed?.hidden() == true && native?.hidden() == true && animations.isEmpty()
        } }
        phase("native IME handoff complete")
    } catch (failure: Throwable) {
        runCatching {
            runOnUiThread { android.util.Log.e("SearchImeHandoff", "focus=${view.hasWindowFocus()} ime=${observed?.isVisible(WindowInsetsCompat.Type.ime())} animations=${animations.size}") }
            android.util.Log.e("SearchImeHandoff", onAllNodes(isRoot()).printToString())
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


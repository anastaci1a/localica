package io.github.anastaci1a.localica

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/** Applied native state and animated samples have different lifetimes on legacy Android. */
internal class SearchImeHandoffObservation {
    var applied: WindowInsetsCompat? = null
        private set
    var animated: WindowInsetsCompat? = null
        private set
    private val animations = mutableSetOf<WindowInsetsAnimationCompat>()
    val animationCount: Int get() = animations.size

    fun apply(insets: WindowInsetsCompat) { applied = insets }
    fun prepare(animation: WindowInsetsAnimationCompat) {
        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animations += animation
    }
    fun progress(insets: WindowInsetsCompat, running: List<WindowInsetsAnimationCompat>) {
        animated = insets
        // API26 compat animators dispatch independent singleton lists. An old show's
        // final frame must not erase a newer hide, or overwrite its applied insets.
        running.forEach(::prepare)
    }
    fun end(animation: WindowInsetsAnimationCompat) { animations -= animation }

    fun complete(windowFocused: Boolean, native: WindowInsetsCompat?): Boolean =
        windowFocused && applied?.imeHidden() == true && native?.imeHidden() == true && animations.isEmpty()
}

private fun WindowInsetsCompat.imeHidden() =
    !isVisible(WindowInsetsCompat.Type.ime()) && getInsets(WindowInsetsCompat.Type.ime()).bottom == 0

private fun WindowInsetsCompat?.imeDescription(): String =
    if (this == null) "unobserved" else "visible=${isVisible(WindowInsetsCompat.Type.ime())},bottom=${getInsets(WindowInsetsCompat.Type.ime()).bottom}"

/** Attach this independent child before content; never replace the application's listeners. */
internal fun createSearchImeObserver(context: Context, observation: SearchImeHandoffObservation): View = View(context).also { observer ->
    ViewCompat.setOnApplyWindowInsetsListener(observer) { _, insets -> observation.apply(insets); insets }
    ViewCompat.setWindowInsetsAnimationCallback(observer, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) { observation.prepare(animation) }
        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
            observation.progress(insets, runningAnimations)
            return insets
        }
        override fun onEnd(animation: WindowInsetsAnimationCompat) { observation.end(animation) }
    })
}

internal fun disposeSearchImeObserver(observer: View) {
    ViewCompat.setWindowInsetsAnimationCallback(observer, null)
    ViewCompat.setOnApplyWindowInsetsListener(observer, null)
}

/** Observe the expected popup's native handoff without replacing application callbacks. */
internal fun ComposeContentTestRule.awaitSearchImeHandoff(action: () -> Unit) {
    val editor = onNode(hasSetTextAction())
    val view = (editor.fetchSemanticsNode().root as ViewRootForTest).view
    val decor = view.rootView as ViewGroup
    lateinit var observer: View
    val observation = SearchImeHandoffObservation()
    runOnUiThread {
        // Independent first child observes legacy dispatch before content consumes it.
        // It never replaces Compose callbacks and returns insets unchanged.
        observer = createSearchImeObserver(view.context, observation)
        decor.addView(observer, 0, ViewGroup.LayoutParams(0, 0))
        ViewCompat.requestApplyInsets(observer)
    }
    fun state() = "focus=${view.hasWindowFocus()} native=[${ViewCompat.getRootWindowInsets(view).imeDescription()}] " +
        "applied=[${observation.applied.imeDescription()}] animated=[${observation.animated.imeDescription()}] animations=${observation.animationCount}"
    fun phase(name: String) = runOnUiThread {
        android.util.Log.i("SearchImeHandoff", "${android.os.SystemClock.uptimeMillis()} phase=$name ${state()}")
    }
    try {
        waitUntil(5000) { runOnUiThread { observation.applied != null && view.hasWindowFocus() } }
        phase("before editor action")
        action()
        waitUntil(5000) { onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty() }
        phase("editor focus ended")
        waitUntil(5000) { runOnUiThread {
            observation.complete(view.hasWindowFocus(), ViewCompat.getRootWindowInsets(view))
        } }
        phase("native IME handoff complete")
    } catch (failure: Throwable) {
        runCatching {
            runOnUiThread { android.util.Log.e("SearchImeHandoff", state()) }
            val roots = onAllNodes(isRoot())
            roots.fetchSemanticsNodes().indices.forEach { index ->
                android.util.Log.e("SearchImeHandoff", roots[index].printToString(maxDepth = 12))
            }
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    } finally {
        runOnUiThread {
            disposeSearchImeObserver(observer)
            decor.removeView(observer)
        }
    }
}

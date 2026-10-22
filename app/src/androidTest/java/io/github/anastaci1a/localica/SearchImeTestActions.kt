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
    private data class AnimationState(
        val sourceBottom: Int,
        var started: Boolean = false,
        var targetHidden: Boolean = false,
        var progressed: Boolean = false,
        var fraction: Float = 0f,
        var reachedHiddenEnd: Boolean = false,
    )
    private val animations = mutableMapOf<WindowInsetsAnimationCompat, AnimationState>()
    val animationCount: Int get() = animations.size
    var completedHideCount: Int = 0
        private set
    val progressingHide: Boolean get() = animations.values.any {
        it.sourceBottom > 0 && it.started && it.targetHidden && it.progressed && it.fraction > 0f && it.fraction < 1f
    }

    fun apply(insets: WindowInsetsCompat) { applied = insets }
    fun prepare(animation: WindowInsetsAnimationCompat) {
        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0)
            animations.getOrPut(animation) { AnimationState(applied?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) }
    }
    fun start(animation: WindowInsetsAnimationCompat) {
        prepare(animation)
        animations[animation]?.let {
            it.started = true
            // onApply precedes onStart. Capture this animation's destination now;
            // a newer hide must not relabel an older show/resize as another hide.
            it.targetHidden = applied?.imeHidden() == true
        }
    }
    fun progress(insets: WindowInsetsCompat, running: List<WindowInsetsAnimationCompat>) {
        animated = insets
        // API26 compat animators dispatch independent singleton lists. An old show's
        // final frame must not erase a newer hide, or overwrite its applied insets.
        running.forEach { animation ->
            prepare(animation)
            animations[animation]?.let {
                it.progressed = true
                it.fraction = animation.fraction
                it.reachedHiddenEnd = it.sourceBottom > 0 && it.started && it.targetHidden &&
                    insets.imeHidden() && animation.fraction == 1f
            }
        }
    }
    fun end(animation: WindowInsetsAnimationCompat) {
        // onEnd also runs when a window loses IME control. Only completed hidden
        // progress, never cancellation alone, proves the keyboard finished hiding.
        if (animations.remove(animation)?.reachedHiddenEnd == true) completedHideCount++
    }

    fun complete(windowFocused: Boolean, native: WindowInsetsCompat?): Boolean =
        windowFocused && applied?.imeHidden() == true && native?.imeHidden() == true && animations.isEmpty()
}

private fun WindowInsetsCompat.imeHidden() =
    !isVisible(WindowInsetsCompat.Type.ime()) && getInsets(WindowInsetsCompat.Type.ime()).bottom == 0

private fun WindowInsetsCompat.imeShown() =
    isVisible(WindowInsetsCompat.Type.ime()) && getInsets(WindowInsetsCompat.Type.ime()).bottom > 0

private fun WindowInsetsCompat?.imeDescription(): String =
    if (this == null) "unobserved" else "visible=${isVisible(WindowInsetsCompat.Type.ime())},bottom=${getInsets(WindowInsetsCompat.Type.ime()).bottom}"

/** Attach this independent child before content; never replace the application's listeners. */
internal fun createSearchImeObserver(context: Context, observation: SearchImeHandoffObservation): View = View(context).also { observer ->
    ViewCompat.setOnApplyWindowInsetsListener(observer) { _, insets -> observation.apply(insets); insets }
    ViewCompat.setWindowInsetsAnimationCallback(observer, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) { observation.prepare(animation) }
        override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
            observation.start(animation)
            return bounds
        }
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

/** Observe the editor's native window handoff without replacing application callbacks. */
internal fun ComposeContentTestRule.awaitSearchImeHandoff(requireShownBeforeAction: Boolean = false, action: () -> Unit) {
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
        waitUntil(5000) { runOnUiThread {
            observation.applied != null && view.hasWindowFocus() &&
                (!requireShownBeforeAction || observation.applied?.imeShown() == true &&
                    ViewCompat.getRootWindowInsets(view)?.imeShown() == true)
        } }
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

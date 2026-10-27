package io.github.anastaci1a.localica

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Diagnostic controls only. Defaults retain the original fixture and input interceptor. */
internal class SearchImeRetirementControls {
    private val arguments = InstrumentationRegistry.getArguments()
    private fun boolean(name: String, default: Boolean): Boolean =
        arguments.getString(name)?.let { value ->
            require(value == "true" || value == "false") { "$name must be true or false" }
            value.toBoolean()
        } ?: default

    val enabled = boolean("searchImeRetirementDiagnostics", false)
    val nativeTrace = boolean("searchImeNativeTrace", false)
    val nativeWarmup = boolean("searchImeNativeWarmup", true)
    val inputObservation = boolean("searchImeInputObservation", true)

    init {
        require(enabled || (!nativeTrace && nativeWarmup && inputObservation)) {
            "Set searchImeRetirementDiagnostics=true before changing a diagnostic control"
        }
    }

    fun description() = "diagnostics=$enabled nativeTrace=$nativeTrace nativeWarmup=$nativeWarmup " +
        "inputObservation=$inputObservation fingerprint=${Build.FINGERPRINT} " +
        "wallMillis=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()}"
}

/** Independent zero-size decor sibling; never replaces Compose's insets or input listeners.
 * No show/hide, restartInput, focus request, isActive/isAcceptingText, or animation control calls.
 * The observer itself can affect native dispatch timing; compare trace-on and trace-off controls. */
internal class SearchImeRetirementProbe(
    private val owner: View,
    private val window: Window,
    private val label: String,
    private val record: (String) -> Unit,
) : AutoCloseable {
    private val decor = window.decorView as ViewGroup
    private var closed = false
    private val animations = mutableSetOf<WindowInsetsAnimationCompat>()
    private val observer = object : View(owner.context) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            emit("windowFocus value=$hasWindowFocus")
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); bindController(); emit("attached") }
        override fun onDetachedFromWindow() { emit("detached"); super.onDetachedFromWindow() }
    }
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
        emit("nativeFocus old=${identity(old)} new=${identity(new)}")
    }
    private var controller: WindowInsetsController? = null
    private val controlsListener = if (Build.VERSION.SDK_INT >= 30)
        WindowInsetsController.OnControllableInsetsChangedListener { _, types -> emit("controllable types=$types") }
    else null

    private fun bindController() {
        if (Build.VERSION.SDK_INT < 30 || controlsListener == null) return
        val current = decor.windowInsetsController
        if (current === controller) return
        controller?.removeOnControllableInsetsChangedListener(controlsListener)
        controller = current
        current?.addOnControllableInsetsChangedListener(controlsListener)
    }

    private fun identity(view: View?) = view?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" }
    private fun WindowInsetsCompat?.description() = if (this == null) "unobserved" else
        "visible=${isVisible(WindowInsetsCompat.Type.ime())},bottom=${getInsets(WindowInsetsCompat.Type.ime()).bottom}"
    private fun WindowInsetsAnimationCompat.description() =
        "id=${System.identityHashCode(this)},types=$typeMask,duration=$durationMillis,fraction=$fraction"
    private fun emit(event: String) {
        if (closed) return
        // Consumer failures cannot interrupt real native callbacks.
        runCatching {
            record("${SystemClock.uptimeMillis()} nativeProbe window=$label event=[$event] " +
                "owner=${identity(owner)} token=${owner.windowToken?.let(System::identityHashCode)} " +
                "attached=${owner.isAttachedToWindow} windowFocused=${owner.hasWindowFocus()} " +
                "softInputMode=${window.attributes.softInputMode} flags=${window.attributes.flags} " +
                "nativeFocus=${identity(decor.findFocus())} animations=${animations.size} " +
                "root=[${ViewCompat.getRootWindowInsets(owner).description()}]")
        }
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(observer) { _, insets ->
            emit("apply insets=[${insets.description()}]")
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(observer,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animations += animation
                    emit("prepare animation=[${animation.description()}]")
                }
                override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                    emit("start animation=[${animation.description()}] bounds=$bounds")
                    return bounds
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    runningAnimations.filter { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }.forEach { animations += it }
                    emit("progress insets=[${insets.description()}] running=[${runningAnimations.joinToString { it.description() }}]")
                    return insets
                }
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    animations -= animation
                    emit("end animation=[${animation.description()}]")
                }
            })
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        // Observe legacy dispatch before content consumes it, as in SearchImeTestActions.
        decor.addView(observer, 0, ViewGroup.LayoutParams(0, 0))
        bindController()
        ViewCompat.requestApplyInsets(observer)
        emit("installed")
    }

    override fun close() {
        if (closed) return
        emit("disposing")
        closed = true
        if (Build.VERSION.SDK_INT >= 30 && controlsListener != null)
            controller?.removeOnControllableInsetsChangedListener(controlsListener)
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        ViewCompat.setWindowInsetsAnimationCallback(observer, null)
        ViewCompat.setOnApplyWindowInsetsListener(observer, null)
        decor.removeView(observer)
    }
}

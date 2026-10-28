package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController

/** Isolated API31+ diagnostic. Never alters insets or replaces Compose listeners. */
internal class NativeImeOwnershipWitness(
    private val host: View,
    private val record: (String) -> Unit,
    private val onPrepareIme: ((WindowInsetsAnimation) -> Unit)? = null,
) {
    private val root = host.rootView as ViewGroup
    private val type = WindowInsets.Type.ime()
    val shadow = ImeExposureShadow { record("${SystemClock.uptimeMillis()} $it") }
    fun inputEvent(event: String) = shadow.input(event, SystemClock.uptimeMillis())
    val prepared = mutableSetOf<Int>()
    val started = mutableSetOf<Int>()
    val ended = mutableSetOf<Int>()
    private fun emit(phase: String, animation: WindowInsetsAnimation? = null, insets: WindowInsets? = null) {
        val native = host.rootWindowInsets
        record("${SystemClock.uptimeMillis()} ownership phase=$phase view=${System.identityHashCode(host)} " +
            "root=${System.identityHashCode(root)} focused=${host.hasWindowFocus()} attached=${host.isAttachedToWindow} " +
            "nativeVisible=${native?.isVisible(type)} nativeHeight=${native?.getInsets(type)?.bottom} " +
            "eventVisible=${insets?.isVisible(type)} eventHeight=${insets?.getInsets(type)?.bottom} " +
            "animation=${animation?.let(System::identityHashCode)} fraction=${animation?.fraction} duration=${animation?.durationMillis}")
    }
    private val probe = object : View(host.context) {
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            emit("apply", insets = insets); shadow.insets(insets.isVisible(type)); return insets
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus); emit("focus=$hasWindowFocus")
            shadow.availability(hasWindowFocus)
        }
    }.apply {
        isFocusable = false; isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and type == 0) return
                prepared += System.identityHashCode(animation)
                emit("prepare", animation)
                shadow.prepare(System.identityHashCode(animation), host.rootWindowInsets?.isVisible(type), SystemClock.uptimeMillis())
                onPrepareIme?.invoke(animation)
            }
            override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
                if (animation.typeMask and type != 0) { started += System.identityHashCode(animation); emit("start", animation)
                    shadow.start(System.identityHashCode(animation), host.rootWindowInsets?.isVisible(type)) }
                return bounds
            }
            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (runningAnimations.any { it.typeMask and type != 0 }) emit("progress", insets = insets)
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimation) {
                if (animation.typeMask and type != 0) { ended += System.identityHashCode(animation); emit("end", animation)
                    shadow.end(System.identityHashCode(animation), animation.fraction, host.rootWindowInsets?.isVisible(type), SystemClock.uptimeMillis()) }
            }
        })
    }
    private val controller = root.windowInsetsController
    private val control = WindowInsetsController.OnControllableInsetsChangedListener { _, types ->
        emit("control=$types"); shadow.availability(host.hasWindowFocus(), types and type != 0)
    }
    init {
        check(android.os.Build.VERSION.SDK_INT >= 31)
        shadow.insets(host.rootWindowInsets?.isVisible(type))
        shadow.availability(host.hasWindowFocus())
        root.addView(probe, 0, ViewGroup.LayoutParams(0, 0))
        controller?.addOnControllableInsetsChangedListener(control)
        emit("attached")
        // Do not requestApplyInsets: record only normal platform dispatch.
    }
    fun snapshot(label: String) { emit("snapshot-$label"); shadow.snapshot(label) }
    fun dispose() {
        controller?.removeOnControllableInsetsChangedListener(control)
        probe.setWindowInsetsAnimationCallback(null); root.removeView(probe)
    }
}

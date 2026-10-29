package io.github.anastaci1a.localica.ui

import android.os.Build
import android.view.View
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.runtime.*

/** Same-snapshot visible-zero-height evidence; visibility alone may describe a docked opening. */
internal fun nativeZeroHeightImeExposure(insets: WindowInsets?): Boolean? =
    if (Build.VERSION.SDK_INT >= 30) insets?.let {
        it.isVisible(WindowInsets.Type.ime()) && it.getInsets(WindowInsets.Type.ime()).bottom == 0
    } else null

/** Test seam returning zero-height corroboration, not visibility alone. */
internal val LocalNativeZeroHeightImeReader = staticCompositionLocalOf<(View) -> Boolean?> {
    { view ->
        if (Build.VERSION.SDK_INT >= 30 && view.isAttachedToWindow && view.hasWindowFocus())
            nativeZeroHeightImeExposure(view.rootWindowInsets)
        else null
    }
}

internal data class NativeImeHostState(val ready: Boolean, val generation: Long = 0L)

/** Native focus/attachment events also stop sampling when Compose has not caught up. */
@Composable
internal fun rememberNativeImeHostReady(view: View, invalidate: () -> Unit): NativeImeHostState {
    var state by remember(view) {
        mutableStateOf(NativeImeHostState(view.isAttachedToWindow && view.hasWindowFocus()))
    }
    val invalidateExposure by rememberUpdatedState(invalidate)
    DisposableEffect(view) {
        var observer: ViewTreeObserver? = null
        fun update(ready: Boolean) {
            if (!ready) {
                invalidateExposure()
                state = NativeImeHostState(false, state.generation + 1)
            } else state = state.copy(ready = true)
        }
        fun refresh() { update(view.isAttachedToWindow && view.hasWindowFocus()) }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { update(view.isAttachedToWindow && it) }
        fun unbind() {
            observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
            observer = null
        }
        fun bind() {
            unbind()
            observer = view.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focus) }
            refresh()
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = bind()
            override fun onViewDetachedFromWindow(v: View) { unbind(); update(false) }
        }
        view.addOnAttachStateChangeListener(attachment)
        if (view.isAttachedToWindow) bind() else update(false)
        onDispose { unbind(); view.removeOnAttachStateChangeListener(attachment) }
    }
    return state
}

/**
 * Native-only exposure changes may not redraw or change Compose's zero insets.
 * A scoped native Choreographer callback observes those changes without requesting layout/draw or
 * taking over Compose's native listeners. It runs only for a visible zero-height
 * focused editor; a floating IME keeps this small polling cost until that scope ends.
 * The revision is merely a wakeup. Callers must query native visibility fresh when
 * deciding, never use a previous frame's true observation as confirmation. A native
 * docked-to-floating height change also changes this reader's Boolean result.
 */
@Composable
internal fun rememberNativeImeRevision(view: View, active: Boolean, hostGeneration: Long,
    read: (View) -> Boolean?): Long {
    var revision by remember(view) { mutableLongStateOf(0L) }
    val reader by rememberUpdatedState(read)
    DisposableEffect(view, active, hostGeneration) {
        if (!active) onDispose { } else {
            val clock = Choreographer.getInstance()
            var running = true
            var previous = if (view.isAttachedToWindow && view.hasWindowFocus()) reader(view) else null
            // Initial activation is also a wakeup: the earlier decision may have
            // observed false just before this first sample observed true.
            revision++
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!running) return
                    val current = if (view.isAttachedToWindow && view.hasWindowFocus()) reader(view) else null
                    if (current != previous) { previous = current; revision++ }
                    if (running) clock.postFrameCallback(this)
                }
            }
            clock.postFrameCallback(callback)
            onDispose { running = false; clock.removeFrameCallback(callback) }
        }
    }
    return revision
}

internal fun confirmedNativeZeroHeightIme(view: View, read: (View) -> Boolean?): Boolean =
    view.isAttachedToWindow && view.hasWindowFocus() && read(view) == true

package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import java.util.concurrent.atomic.AtomicLong

/** Scoped empty search may dismiss itself when editing ends through Back. */
internal val LocalEmptySearchBack = compositionLocalOf<(() -> Unit)?> { null }

private val LocalRestingTextFocus = staticCompositionLocalOf<FocusRequester> { error("Text fields require a window focus host") }

/** A non-editing focus destination per window, outside field autofocus modifiers. */
@Composable internal fun TextEditingFocusHost(content: @Composable () -> Unit) {
    val target = remember { FocusRequester() }
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    CompositionLocalProvider(LocalRestingTextFocus provides target) { ObserveTextEditingInput {
        // Legacy Android delivers Back as a key. Route unhandled keys before
        // Compose interprets Back as exiting this non-editing focus group.
        Box(Modifier.onKeyEvent {
            if (android.os.Build.VERSION.SDK_INT < 33 && it.key == Key.Back && back != null) {
                if (it.type == KeyEventType.KeyUp) back.onBackPressed()
                true
            } else false
        }.focusRequester(target).focusTarget(), propagateMinConstraints = true) { content() }
    } }
}

/** Distinguish a settled editing IME from animated insets inherited from another window. */
internal class TextEditingImeSession {
    private var shown = false
    private var docked = false

    /** Native focus loss must invalidate floating history without changing docked policy. */
    fun invalidateZeroHeightExposure() { if (!docked) shown = false }

    fun shouldEnd(focused: Boolean, windowFocused: Boolean, visible: Boolean,
        current: Int, source: Int, target: Int, endAtHideStart: Boolean,
        zeroHeightConfirmed: Boolean = true): Boolean {
        if (!focused || !windowFocused) shown = false
        // Native window/input handoff can briefly clear visibility/current
        // while the animation target still requests the shown keyboard.
        else if (shown && target == 0 && (!visible || (endAtHideStart && docked))) {
            shown = false
            return true
        } else if (visible && current == target && source == target &&
            (target != 0 || zeroHeightConfirmed)) {
            // API30+ confirms zero-height exposure from this same host/sample.
            // Never latch native visibility from a preceding observation. The
            // default retains legacy behavior where that evidence is unavailable.
            shown = true
            docked = target > 0
        }
        return false
    }
}

internal val LocalTextEditingObservation = staticCompositionLocalOf<((String) -> Unit)?> { null }

private val textEditingInputIds = AtomicLong()

/** Diagnostic coroutine identity only: connection creation does not certify inset ownership. */
internal class TextEditingInputTrace(private val observe: (String) -> Unit) {
    private fun emit(phase: String, generation: Long) {
        // A diagnostic consumer must never interrupt the real input request or its cleanup.
        runCatching { observe("inputSession phase=$phase generation=$generation") }
    }
    fun start(): Long = textEditingInputIds.incrementAndGet().also { emit("start", it) }
    fun created(generation: Long) = emit("create", generation)
    fun retire(generation: Long) = emit("retire", generation)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ObserveTextEditingInput(content: @Composable () -> Unit) {
    val observation = LocalTextEditingObservation.current
    if (observation == null) { content(); return }
    val observe by rememberUpdatedState(observation)
    val view = androidx.compose.ui.platform.LocalView.current
    val trace = remember(view) {
        val main = android.os.Looper.getMainLooper()
        val handler = android.os.Handler(main)
        TextEditingInputTrace { event ->
            val uptime = android.os.SystemClock.uptimeMillis()
            val onUi = android.os.Looper.myLooper() === main
            val facts = if (onUi) " attached=${view.isAttachedToWindow} nativeWindowFocused=${view.hasWindowFocus()}" else ""
            val deliver = Runnable {
                runCatching { observe("$event uptime=$uptime uiThread=$onUi$facts") }
                    .onFailure { android.util.Log.w("TextEditingObservation", "Observer callback failed") }
            }
            if (onUi) deliver.run() else handler.post(deliver)
        }
    }
    // Changing the interceptor instance restarts an active platform input session.
    val interceptor = remember(view, trace) {
        androidx.compose.ui.platform.PlatformTextInputInterceptor { request, next ->
            // Interceptors also chain through nested Dialog compositions.
            if (next.view !== view) next.startInputMethod(request)
            val generation = trace.start()
            try {
                next.startInputMethod(androidx.compose.ui.platform.PlatformTextInputMethodRequest { attributes ->
                    request.createInputConnection(attributes).also { trace.created(generation) }
                })
            } finally { trace.retire(generation) }
        }
    }
    androidx.compose.ui.platform.InterceptPlatformTextInput(interceptor, content)
}

/** Back dismisses editing before dismissing its page/dialog, including IME-owned Back. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun Modifier.clearTextFocusOnBack(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val imeSession = remember(view) { TextEditingImeSession() }
    val keyboardVisible = WindowInsets.isImeVisible
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    val keyboardSource = WindowInsets.imeAnimationSource.getBottom(density)
    val keyboardTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val nativeCorroboration = android.os.Build.VERSION.SDK_INT >= 30
    val nativeReader = LocalNativeZeroHeightImeReader.current
    val nativeHost = if (nativeCorroboration)
        rememberNativeImeHostReady(view, imeSession::invalidateZeroHeightExposure) else NativeImeHostState(false)
    val zeroHeightCandidate = focused && windowFocused && keyboardVisible &&
        keyboardHeight == 0 && keyboardSource == 0 && keyboardTarget == 0
    val nativeRevision = if (nativeCorroboration)
        rememberNativeImeRevision(view, zeroHeightCandidate && nativeHost.ready, nativeHost.generation, nativeReader) else 0L
    // Keep native window focus, but move Compose focus out of the editor. Clearing
    // the window owner can immediately focus its first editor again in key mode.
    val restingFocus = LocalRestingTextFocus.current
    val keyboard = LocalSoftwareKeyboardController.current
    val emptySearchBack by rememberUpdatedState(LocalEmptySearchBack.current)
    val beginSearchReturn by rememberUpdatedState(LocalSearchReturnStart.current)
    val observe by rememberUpdatedState(LocalTextEditingObservation.current)
    fun endEditing(reason: String) {
        observe?.invoke("endEditing reason=$reason focused=$focused window=$windowFocused visible=$keyboardVisible current=$keyboardHeight source=$keyboardSource target=$keyboardTarget")
        if (emptySearchBack != null) beginSearchReturn?.invoke()
        restingFocus.requestFocus(); keyboard?.hide(); emptySearchBack?.invoke()
    }
    BackHandler(focused) { endEditing("backDispatcher") }
    LaunchedEffect(view, focused, windowFocused, keyboardVisible, keyboardHeight, keyboardSource,
        keyboardTarget, nativeHost, nativeRevision, nativeReader) {
        val zeroHeightConfirmed = !nativeCorroboration || !zeroHeightCandidate ||
            (nativeHost.ready && confirmedNativeZeroHeightIme(view, nativeReader))
        val end = imeSession.shouldEnd(focused, windowFocused, keyboardVisible, keyboardHeight,
            keyboardSource, keyboardTarget, endAtHideStart = emptySearchBack != null,
            zeroHeightConfirmed = zeroHeightConfirmed)
        observe?.invoke("imeDecision end=$end zeroHeightConfirmed=$zeroHeightConfirmed nativeRevision=$nativeRevision focused=$focused window=$windowFocused visible=$keyboardVisible current=$keyboardHeight source=$keyboardSource target=$keyboardTarget")
        if (end) {
            // IME-owned Back announces its zero target before its last visible
            // frame. Empty search returns home immediately, independently of it.
            endEditing("imeObservation")
        }
    }
    return onFocusChanged { focused = it.isFocused }.onPreInterceptKeyBeforeSoftKeyboard { event ->
        if (focused && event.key == Key.Back) {
            if (event.type == KeyEventType.KeyUp) endEditing("backKey")
            true
        } else false
    }
}

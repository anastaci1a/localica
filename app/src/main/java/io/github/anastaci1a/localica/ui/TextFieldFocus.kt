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

/** Scoped empty search may dismiss itself when editing ends through Back. */
internal val LocalEmptySearchBack = compositionLocalOf<(() -> Unit)?> { null }

private val LocalRestingTextFocus = staticCompositionLocalOf<FocusRequester> { error("Text fields require a window focus host") }

/** A non-editing focus destination per window, outside field autofocus modifiers. */
@Composable internal fun TextEditingFocusHost(content: @Composable () -> Unit) {
    val target = remember { FocusRequester() }
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    CompositionLocalProvider(LocalRestingTextFocus provides target) {
        // Legacy Android delivers Back as a key. Route unhandled keys before
        // Compose interprets Back as exiting this non-editing focus group.
        Box(Modifier.onKeyEvent {
            if (android.os.Build.VERSION.SDK_INT < 33 && it.key == Key.Back && back != null) {
                if (it.type == KeyEventType.KeyUp) back.onBackPressed()
                true
            } else false
        }.focusRequester(target).focusTarget(), propagateMinConstraints = true) { content() }
    }
}

/** Distinguish a settled editing IME from animated insets inherited from another window. */
internal class TextEditingImeSession {
    private var shown = false
    private var docked = false

    fun shouldEnd(focused: Boolean, windowFocused: Boolean, visible: Boolean,
        current: Int, source: Int, target: Int, endAtHideStart: Boolean): Boolean {
        if (!focused || !windowFocused) shown = false
        // Native window/input handoff can briefly clear visibility/current
        // while the animation target still requests the shown keyboard.
        else if (shown && target == 0 && (!visible || (endAtHideStart && docked))) {
            shown = false
            return true
        } else if (visible && current == target && source == target) {
            shown = true
            docked = target > 0
        }
        return false
    }
}

/** Back dismisses editing before dismissing its page/dialog, including IME-owned Back. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun Modifier.clearTextFocusOnBack(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val imeSession = remember { TextEditingImeSession() }
    val keyboardVisible = WindowInsets.isImeVisible
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    val keyboardSource = WindowInsets.imeAnimationSource.getBottom(density)
    val keyboardTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    // Keep native window focus, but move Compose focus out of the editor. Clearing
    // the window owner can immediately focus its first editor again in key mode.
    val restingFocus = LocalRestingTextFocus.current
    val keyboard = LocalSoftwareKeyboardController.current
    val emptySearchBack by rememberUpdatedState(LocalEmptySearchBack.current)
    val beginSearchReturn by rememberUpdatedState(LocalSearchReturnStart.current)
    fun endEditing() {
        if (emptySearchBack != null) beginSearchReturn?.invoke()
        restingFocus.requestFocus(); keyboard?.hide(); emptySearchBack?.invoke()
    }
    BackHandler(focused) { endEditing() }
    LaunchedEffect(focused, windowFocused, keyboardVisible, keyboardHeight, keyboardSource, keyboardTarget) {
        if (imeSession.shouldEnd(focused, windowFocused, keyboardVisible, keyboardHeight,
                keyboardSource, keyboardTarget, endAtHideStart = emptySearchBack != null)) {
            // IME-owned Back announces its zero target before its last visible
            // frame. Empty search returns home immediately, independently of it.
            endEditing()
        }
    }
    return onFocusChanged { focused = it.isFocused }.onPreInterceptKeyBeforeSoftKeyboard { event ->
        if (focused && event.key == Key.Back) {
            if (event.type == KeyEventType.KeyUp) endEditing()
            true
        } else false
    }
}

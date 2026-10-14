package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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

/** Back dismisses editing before dismissing its page/dialog, including IME-owned Back. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun Modifier.clearTextFocusOnBack(): Modifier {
    var focused by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardSettled = WindowInsets.imeAnimationSource.getBottom(density) == WindowInsets.imeAnimationTarget.getBottom(density)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    // Keep native window focus, but move Compose focus out of the editor. Clearing
    // the window owner can immediately focus its first editor again in key mode.
    val restingFocus = LocalRestingTextFocus.current
    val keyboard = LocalSoftwareKeyboardController.current
    val emptySearchBack by rememberUpdatedState(LocalEmptySearchBack.current)
    fun endEditing() { restingFocus.requestFocus(); keyboard?.hide(); emptySearchBack?.invoke() }
    BackHandler(focused) { endEditing() }
    LaunchedEffect(focused, keyboardVisible, keyboardSettled, windowFocused) {
        if (!focused || !windowFocused) sawKeyboard = false
        // A newly focused popup may inherit the previous window's closing IME.
        // Only a settled keyboard belongs to this editing session.
        else if (keyboardVisible && keyboardSettled) sawKeyboard = true
        else if (!keyboardVisible && sawKeyboard) { sawKeyboard = false; endEditing() }
    }
    return onFocusChanged { focused = it.isFocused }.onPreInterceptKeyBeforeSoftKeyboard { event ->
        if (focused && event.key == Key.Back) {
            if (event.type == KeyEventType.KeyUp) endEditing()
            true
        } else false
    }
}

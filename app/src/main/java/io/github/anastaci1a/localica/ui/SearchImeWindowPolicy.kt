package io.github.anastaci1a.localica.ui

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Keep adjustment/navigation bits; the editor, rather than window focus, owns IME visibility. */
internal fun searchWindowSoftInputMode(mode: Int): Int =
    (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED

/** Search owns this Dialog window for its entire lifetime. Never force its keyboard to show. */
@Composable
internal fun PreserveSearchImeState(window: Window?) {
    val observe by rememberUpdatedState(LocalTextEditingObservation.current)
    DisposableEffect(window) {
        if (window == null) onDispose { } else {
            val original = window.callback
            var active = true
            fun record(requested: Int, applied: Int) {
                runCatching { observe?.invoke("imeWindowPolicy requested=$requested applied=$applied") }
            }
            val callback = original?.let { delegate ->
                object : Window.Callback by delegate {
                    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                        if (active) {
                            val requested = attrs.softInputMode
                            attrs.softInputMode = searchWindowSoftInputMode(requested)
                            if (requested != attrs.softInputMode) record(requested, attrs.softInputMode)
                        }
                        delegate.onWindowAttributesChanged(attrs)
                    }
                }
            }
            if (callback != null) window.callback = callback
            val requested = window.attributes.softInputMode
            val applied = searchWindowSoftInputMode(requested)
            if (requested != applied) window.setSoftInputMode(applied)
            record(requested, applied)
            onDispose {
                active = false
                if (callback != null && window.callback === callback) window.callback = original
                // This owned Search window is retiring. Restoring UNSPECIFIED here
                // could enqueue another hide while a successor editor is opening.
            }
        }
    }
}

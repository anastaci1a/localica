package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import io.github.anastaci1a.localica.navigation.NavigationCodec
import io.github.anastaci1a.localica.navigation.NavigationState

/** Reject the whole restored state, not just its value, so rememberSaveable runs the owner initializer. */
internal fun navigationStateSaver(admit: (NavigationState) -> NavigationState? = { it }):
    Saver<MutableState<NavigationState>, Any> = Saver(
    save = { NavigationCodec.encode(it.value) },
    restore = { saved ->
        // The former stateSaver encoded a MutableState-wrapped String. Accept one legacy layer;
        // generated saveable-key continuity across builds is not assumed or required here.
        val text = when (saved) {
            is String -> saved
            is MutableState<*> -> saved.value as? String
            else -> null
        }
        text?.let(NavigationCodec::decodeOrNull)?.let(admit)?.let { mutableStateOf(it) }
    },
)

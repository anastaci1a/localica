package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex

/** Presentation ownership only: gestures, focus traversal and hit bounds stay with the control. */
@Stable
internal class SelectorTrackActivity {
    private var moving by mutableStateOf(false)
    private val focusedOptions = mutableStateMapOf<Int, Boolean>()
    val raised: Boolean get() = moving || focusedOptions.isNotEmpty()

    fun update(moving: Boolean) { this.moving = moving }
    fun focus(option: Int, focused: Boolean) {
        if (focused) focusedOptions[option] = true else focusedOptions.remove(option)
    }
    fun clear() { moving = false; focusedOptions.clear() }
}

@Composable internal fun rememberSelectorTrackActivity() = remember { SelectorTrackActivity() }

/** Apply at the track's sibling boundary, above neighboring utility controls. */
internal fun Modifier.selectorTrackStacking(activity: SelectorTrackActivity) = zIndex(if (activity.raised) 1f else 0f)

package io.github.anastaci1a.localica.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

internal enum class ControlHaptic { Weak, Strong }

/** UI interaction feedback only: state synchronization and animation never own a pulse. */
internal fun interface ControlHaptics {
    fun emit(feedback: ControlHaptic)
}

/** Tests can record semantic pulses without depending on a device's vibration hardware. */
internal val LocalControlHaptics = staticCompositionLocalOf<ControlHaptics?> { null }

@Composable
internal fun rememberControlHaptics(): ControlHaptics {
    val override = LocalControlHaptics.current
    val view = LocalView.current
    return override ?: remember(view) {
        ControlHaptics { feedback ->
            // No override flags or vibrator fallback: retain the platform's system-setting gate.
            view.performHapticFeedback(when (feedback) {
                ControlHaptic.Weak -> HapticFeedbackConstants.CLOCK_TICK
                ControlHaptic.Strong -> HapticFeedbackConstants.LONG_PRESS
            })
        }
    }
}

/** One owned drag, independent of recomposition and of the number of active pointers. */
internal class ControlDragHaptics(private val haptics: ControlHaptics) {
    private var option: Int? = null

    fun engage(initialOption: Int) {
        if (option != null) return
        option = initialOption
        haptics.emit(ControlHaptic.Weak)
    }

    fun move(nextOption: Int) {
        val previous = option ?: return
        if (nextOption == previous) return
        option = nextOption
        haptics.emit(ControlHaptic.Weak)
    }

    fun finish(released: Boolean) {
        if (option == null) return
        option = null
        if (released) haptics.emit(ControlHaptic.Strong)
    }
}

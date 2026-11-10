package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import io.github.anastaci1a.localica.ui.engine.rememberQuickInsetProgress

/** The component owns its Engine clock; hosts consume only the shared displayed fraction. */
@Composable
internal fun rememberSelectionPanelProgress(visible: Boolean, measured: Boolean, reduced: Boolean, speed: Float): State<Float> =
    rememberQuickInsetProgress(visible, measured, reduced, speed)

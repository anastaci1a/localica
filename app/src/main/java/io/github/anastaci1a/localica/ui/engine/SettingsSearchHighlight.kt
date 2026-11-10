package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/** Owns the incoming settings pulse, including its cancellable hold and immediate reset.
 * Faster deliberately retains the incoming pulse timing; reduced motion retains its longer hold.
 */
@Composable
internal fun rememberSettingsSearchHighlight(
    highlighted: Boolean,
    reduced: Boolean,
    onHighlight: () -> Unit,
): State<Float> {
    val alpha = remember { Animatable(0f) }
    val currentOnHighlight = rememberUpdatedState(onHighlight)
    LaunchedEffect(highlighted) {
        if (highlighted) {
            currentOnHighlight.value()
            if (reduced) alpha.snapTo(.18f) else alpha.animateTo(.18f, tween(150))
            delay(if (reduced) 1200 else 450)
            if (reduced) alpha.snapTo(0f) else alpha.animateTo(0f, tween(600))
        } else alpha.snapTo(0f)
    }
    return alpha.asState()
}

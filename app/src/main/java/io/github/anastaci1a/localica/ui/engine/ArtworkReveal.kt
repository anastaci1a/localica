package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot

/** Exact ownership move of the existing reveal: no new clock, scheduling policy,
 * target, easing or completion gate. Already-settled owners mount no coroutine. */
@Composable
internal fun rememberArtworkRevealState(present: Boolean, reducedMotion: Boolean,
    onPresentSettled: () -> Unit): State<Float> {
    val reveal = remember { Animatable(if (present) 1f else 0f) }
    val completion by rememberUpdatedState(onPresentSettled)
    val settled = Snapshot.withoutReadObservation {
        reveal.value == (if (present) 1f else 0f) && !reveal.isRunning
    }
    if (!settled) LaunchedEffect(present, reducedMotion) {
        reveal.animateTo(if (present) 1f else 0f,
            tween(if (reducedMotion) 0 else 300, easing = FastOutSlowInEasing))
        if (present) completion()
    }
    return reveal.asState()
}

package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot

/**
 * [androidx.compose.animation.core.animateFloatAsState] for values that are usually at rest.
 *
 * The stock version launches a collecting coroutine and a channel for every call site, so a lazy
 * row with several animated floats pays for them on every entry. This keeps the same
 * [Animatable], the same spec and the same interruption behaviour (a new target animates from the
 * current value and velocity), but owns a coroutine only while the value differs from its target.
 * The settledness check reads the animatable without observation, so callers are not recomposed
 * per animation frame unless they read the returned state themselves.
 */
@Composable
internal fun animateSettledFloat(target: Float, spec: AnimationSpec<Float>, label: String): State<Float> {
    val animatable = remember { Animatable(target) }
    val moving = Snapshot.withoutReadObservation { animatable.value != target || animatable.isRunning }
    if (moving) LaunchedEffect(target) { animatable.animateTo(target, spec) }
    return animatable.asState()
}

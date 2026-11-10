package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.ui.spec.ListMotionSpec
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import io.github.anastaci1a.localica.ui.spec.MotionProfileSpec
import kotlin.math.roundToInt

/** One clock for the pill and its admitted scroll room, using validated selection time and list easing. */
@Composable
internal fun rememberQuickInsetProgress(visible: Boolean, measured: Boolean, reduced: Boolean, speed: Float): State<Float> {
    val spec = LocalUiSpec.current
    val duration = (spec.motion.getValue(MotionProfile.Selection) as MotionProfileSpec).durationMs
    val easing = (spec.motion.getValue(MotionProfile.Scroll) as ListMotionSpec).easing
    val animation = remember(duration, easing, reduced, speed) {
        if (reduced) snap<Float>() else tween((duration / speed).roundToInt().coerceAtLeast(1),
            easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2))
    }
    return animateFloatAsState(if (visible && measured) 1f else 0f, animation, label = "Selection panel occupancy")
}

/** A measured occupancy destination is applied only within its owner's Foundation mutation. */
internal fun ScrollScope.consumeInsetDestination(destination: Float, admitted: Float, consumed: Float): Float =
    scrollBy(minOf(destination, admitted) - consumed)

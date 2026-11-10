package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Retains the incoming three motion channels and their independent cancellation domains.
 * Activity restarts the idle fade, contact retargets the grip, and release bridges list settling.
 */
@Stable
internal class ScrollbarMotion(private val scope: CoroutineScope, private val grip: State<Float>) {
    private val release = Animatable(0f)
    private val opacity = Animatable(1f)
    fun scale() = grip.value
    fun releaseOffset() = release.value
    internal fun opacity() = opacity.value
    suspend fun reveal() {
        opacity.snapTo(1f)
        opacity.animateTo(0f, tween(durationMillis = ScrollbarSpec.fadeMillis, delayMillis = ScrollbarSpec.idleMillis))
    }
    fun release(gap: Float) {
        scope.launch { release.snapTo(gap); release.animateTo(0f, tween(ScrollbarSpec.releaseMillis)) }
    }
}

@Composable
internal fun rememberScrollbarMotion(key: Any, movement: () -> Pair<Int, Int>, activity: () -> Int,
    held: Boolean, speed: Float, reduced: Boolean): ScrollbarMotion {
    val scope = rememberCoroutineScope()
    val grip = animateFloatAsState(if (held) ScrollbarSpec.heldScale else 1f,
        tween(if (reduced) 0 else (ScrollbarSpec.gripMillis / speed).toInt(), easing = CubicBezierEasing(0f, 0f, .2f, 1f)),
        label = "Scrollbar grip")
    val owner = remember { ScrollbarMotion(scope, grip) }
    val latestMovement by rememberUpdatedState(movement)
    val latestActivity by rememberUpdatedState(activity)
    LaunchedEffect(key) {
        snapshotFlow { latestMovement() to latestActivity() }.collectLatest { owner.reveal() }
    }
    return owner
}

/** Both drawings sample one reveal; only the thumb scales about its outer top edge. */
internal fun Modifier.scrollbarPresentation(motion: ScrollbarMotion, position: () -> Float,
    scaleThumb: Boolean): Modifier = graphicsLayer {
    alpha = motion.opacity()
    translationY = position()
    if (scaleThumb) {
        scaleX = motion.scale()
        scaleY = motion.scale()
        transformOrigin = TransformOrigin(1f, 0f)
    }
}

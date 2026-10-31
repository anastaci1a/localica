package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyLayoutScrollScope as listScrollScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyLayoutScrollScope as gridScrollScope
import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import io.github.anastaci1a.localica.navigation.LongScrollStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign

/** Process-wide choice read by easeToItem/easeToTop, set from the interface settings. */
internal object LongScroll {
    @Volatile var style = LongScrollStyle.FlyThrough
    /** Fly-through lands a fresh region only every this many frames. */
    @Volatile var flyJumpEvery = 3
}

@Stable internal class LongScrollFade {
    var alpha by mutableFloatStateOf(1f)
    var fading by mutableStateOf(false)
}
private val fadeHolders = java.util.WeakHashMap<Any, LongScrollFade>()
internal fun fadeFor(state: Any) = synchronized(fadeHolders) { fadeHolders.getOrPut(state) { LongScrollFade() } }

/** Apply to the scrolling list so the Fade style can dim it with graphicsLayer alpha; the layer
 * exists only while a fade runs. Without it Fade behaves as Jump, then glide. */
internal fun Modifier.longScrollFade(state: Any): Modifier = composed {
    val fade = remember(state) { fadeFor(state) }
    if (fade.fading) Modifier.graphicsLayer { alpha = fade.alpha } else Modifier
}

/** One cold jump to about 1.5 viewports before a distant target. False when the target is near. */
private suspend fun LazyLayoutScrollScope.jumpNear(index: Int, offset: Int, lead: Int, columns: () -> Int): Boolean {
    val distance = calculateDistanceTo(index, offset).toFloat()
    val dir = sign(distance).toInt()
    if (dir == 0 || abs(distance) <= lead * 1.5f || index in firstVisibleItemIndex..lastVisibleItemIndex) return false
    val slots = columns().coerceAtLeast(1)
    val rows = (index - firstVisibleItemIndex).toFloat() / slots
    val stride = if (rows == 0f) 0f else abs((distance + firstVisibleItemScrollOffset - offset) / rows)
    if (stride <= .5f) return false
    val near = (index - dir * ceil(lead / stride).toInt().coerceAtLeast(1) * slots).coerceIn(0, itemCount - 1)
    // Jump only when it stays on the near side of the target and actually advances.
    if ((near - index) * dir >= 0 || (near - firstVisibleItemIndex) * dir <= 0) return false
    snapToItem(near, offset)
    return true
}

/** The shared time curve: scrollBy toward the target, finishing exactly on it, over [duration]. */
private suspend fun LazyLayoutScrollScope.glideToItem(index: Int, offset: Int, duration: Int, onTime: (Float) -> Unit = {}) {
    val direction = sign(calculateDistanceTo(index, offset).toFloat())
    if (direction == 0f) return
    val start = withFrameNanos { it }
    var previous = 0f
    while (true) {
        val frame = withFrameNanos { it }
        val time = ((frame - start) / (duration.coerceIn(1, 500) * 1_000_000f)).coerceIn(0f, 1f)
        onTime((frame - start) / 1_000_000f)
        val progress = FastOutSlowInEasing.transform(time)
        val remaining = calculateDistanceTo(index, offset).toFloat()
        if (abs(remaining) < .5f) return
        if (time >= 1f) { snapToItem(index, offset); return }
        val fraction = ((progress - previous) / (1f - previous)).coerceIn(0f, 1f)
        previous = progress
        val step = remaining * fraction
        if (abs(scrollBy(step)) < .1f && abs(step) > .5f) return
        if (index in firstVisibleItemIndex..lastVisibleItemIndex) {
            val measured = calculateDistanceTo(index, offset).toFloat()
            if (abs(measured) < .5f) return
            if (sign(measured) != direction) { scrollBy(measured); return }
        }
    }
}

/** Jump, then glide: about two fresh viewports in total. */
private suspend fun LazyLayoutScrollScope.jumpGlide(index: Int, offset: Int, duration: Int, viewport: () -> Int, columns: () -> Int) {
    jumpNear(index, offset, (viewport() * 1.5f).toInt().coerceAtLeast(1), columns)
    glideToItem(index, offset, duration)
}

private const val FadeMillis = 120

/** Fade the list out, jump while it is hidden, fade back in during the glide of the remaining time. */
private suspend fun LazyLayoutScrollScope.fadeGlide(index: Int, offset: Int, duration: Int, viewport: () -> Int,
    columns: () -> Int, fade: LongScrollFade?) {
    val lead = (viewport() * 1.5f).toInt().coerceAtLeast(1)
    val distance = calculateDistanceTo(index, offset).toFloat()
    val far = abs(distance) > lead * 1.5f && index !in firstVisibleItemIndex..lastVisibleItemIndex
    if (fade == null || !far) return jumpGlide(index, offset, duration, viewport, columns)
    fade.fading = true
    try {
        val start = withFrameNanos { it }
        while (true) {
            val elapsed = (withFrameNanos { it } - start) / 1_000_000f
            fade.alpha = (1f - elapsed / FadeMillis).coerceIn(0f, 1f)
            if (elapsed >= FadeMillis) break
        }
        jumpNear(index, offset, lead, columns)
        glideToItem(index, offset, (duration - FadeMillis).coerceAtLeast(1)) { fade.alpha = (it / FadeMillis).coerceIn(0f, 1f) }
    } finally { fade.alpha = 1f; fade.fading = false }
}

/** Fly-through: keep the visible rush on the same curve, but land a fresh region only every
 * [LongScroll.flyJumpEvery] frames. Frames between scroll at most half a viewport, so motion stays
 * continuous while the owed distance carries into the next landing. Rows should render lightly
 * while LocalArtworkTransit is active. */
private suspend fun LazyLayoutScrollScope.flyThrough(index: Int, offset: Int, duration: Int,
    viewport: () -> Int, columns: () -> Int) {
    if (index !in 0 until itemCount) return
    val direction = sign(calculateDistanceTo(index, offset).toFloat())
    if (direction == 0f) return
    val every = LongScroll.flyJumpEvery.coerceAtLeast(1)
    val start = withFrameNanos { it }
    var previous = 0f
    var frames = 0
    while (true) {
        val frame = withFrameNanos { it }
        frames++
        val time = ((frame - start) / (duration.coerceIn(1, 500) * 1_000_000f)).coerceIn(0f, 1f)
        val progress = FastOutSlowInEasing.transform(time)
        val remaining = calculateDistanceTo(index, offset).toFloat()
        if (abs(remaining) < .5f) return
        if (time >= 1f) { snapToItem(index, offset); return }
        val fraction = ((progress - previous) / (1f - previous)).coerceIn(0f, 1f)
        val step = remaining * fraction
        val visible = index in firstVisibleItemIndex..lastVisibleItemIndex
        val far = abs(step) > viewport() * .75f && !visible
        if (far && frames % every != 0) {
            // Smooth between landings; only the scrolled share of this frame's progress is consumed.
            val part = step.coerceIn(-viewport() * .5f, viewport() * .5f)
            previous += (progress - previous) * (part / step)
            if (abs(scrollBy(part)) < .1f && abs(part) > .5f) return
        } else {
            previous = progress
            if (far) {
                val slots = columns().coerceAtLeast(1)
                val rows = (index - firstVisibleItemIndex).toFloat() / slots
                val advance = (rows * fraction).toInt()
                if (advance != 0) {
                    val stride = (remaining + firstVisibleItemScrollOffset - offset) / rows
                    val target = (firstVisibleItemIndex + advance * slots).coerceIn(0, itemCount - 1)
                    snapToItem(target, (firstVisibleItemScrollOffset + step - advance * stride).roundToInt())
                } else if (abs(scrollBy(step)) < .1f && abs(step) > .5f) return
            } else if (abs(scrollBy(step)) < .1f && abs(step) > .5f) return
        }
        if (index in firstVisibleItemIndex..lastVisibleItemIndex) {
            val measured = calculateDistanceTo(index, offset).toFloat()
            if (abs(measured) < .5f) return
            if (sign(measured) != direction) { scrollBy(measured); return }
        }
    }
}

private suspend fun LazyLayoutScrollScope.approachItem(index: Int, offset: Int, duration: Int, style: LongScrollStyle,
    fade: LongScrollFade?, viewport: () -> Int, columns: () -> Int) {
    if (index !in 0 until itemCount) return
    when (style) {
        LongScrollStyle.FlyThrough -> flyThrough(index, offset, duration, viewport, columns)
        LongScrollStyle.JumpGlide -> jumpGlide(index, offset, duration, viewport, columns)
        LongScrollStyle.Fade -> fadeGlide(index, offset, duration, viewport, columns, fade)
    }
}

internal suspend fun LazyGridState.easeToTop(reducedMotion: Boolean, style: LongScrollStyle = LongScroll.style) =
    easeToItem(0, reducedMotion, 0f, style)

internal suspend fun LazyListState.easeToItem(index: Int, reducedMotion: Boolean, duration: Int = 350,
    style: LongScrollStyle = LongScroll.style) {
    if (index < 0) return
    // scroll waits for the first layout. Before that, a populated list reports
    // zero items and would silently discard an initial follow-current request.
    scroll {
        val scope = listScrollScope(this@easeToItem, this)
        if (index < scope.itemCount) {
            if (reducedMotion) scope.snapToItem(index, 0)
            else scope.approachItem(index, 0, duration, style, synchronized(fadeHolders) { fadeHolders[this@easeToItem] },
                { layoutInfo.viewportSize.height }, { 1 })
        }
    }
}

internal suspend fun LazyGridState.easeToItem(index: Int, reducedMotion: Boolean, topInset: Float = 0f,
    style: LongScrollStyle = LongScroll.style) {
    if (index < 0) return
    scroll {
        val scope = gridScrollScope(this@easeToItem, this)
        if (index < scope.itemCount) {
            if (reducedMotion) scope.snapToItem(index, -topInset.roundToInt())
            else scope.approachItem(index, -topInset.roundToInt(), 500, style, synchronized(fadeHolders) { fadeHolders[this@easeToItem] },
                { layoutInfo.viewportSize.height }, { (layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1 })
        }
    }
}

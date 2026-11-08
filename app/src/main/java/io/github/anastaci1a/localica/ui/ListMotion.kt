package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.engine.ScrollExecution
import io.github.anastaci1a.localica.ui.engine.withScrollExecution
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

/** Foundation targeting/consumption policy, sampled on the root Engine timeline. */
private suspend fun LazyLayoutScrollScope.glideToItem(initialIndex: Int, initialOffset: Int, duration: Int,
    execution: ScrollExecution, flyEvery: Int = 0, viewport: () -> Int, columns: () -> Int,
    target: (() -> ListScrollTarget)? = null, onTime: (Float) -> Unit = {}) {
    var index = initialIndex
    var offset = initialOffset
    val direction = sign(calculateDistanceTo(index, offset).toFloat())
    if (direction == 0f) return
    var previous = 0f
    execution.animate(duration) { progress, elapsed, frame, finished ->
        onTime(elapsed)
        target?.invoke()?.let { index = it.index; offset = it.offset }
        if (index !in 0 until itemCount) return@animate false
        val remaining = calculateDistanceTo(index, offset).toFloat()
        if (abs(remaining) < .5f) return@animate false
        if (finished) { snapToItem(index, offset); return@animate false }
        val fraction = ((progress - previous) / (1f - previous)).coerceIn(0f, 1f)
        val step = remaining * fraction
        val far = flyEvery > 0 && abs(step) > viewport() * .75f && index !in firstVisibleItemIndex..lastVisibleItemIndex
        if (far && frame % flyEvery != 0) {
            val part = step.coerceIn(-viewport() * .5f, viewport() * .5f)
            previous += (progress - previous) * (part / step)
            if (abs(execution.consume(this@glideToItem, part)) < .1f && abs(part) > .5f) return@animate false
        } else {
            previous = progress
            val slots = columns().coerceAtLeast(1)
            val rows = (index - firstVisibleItemIndex).toFloat() / slots
            val advance = if (far) (rows * fraction).toInt() else 0
            if (advance != 0) {
                val stride = (remaining + firstVisibleItemScrollOffset - offset) / rows
                val landing = (firstVisibleItemIndex + advance * slots).coerceIn(0, itemCount - 1)
                snapToItem(landing, (firstVisibleItemScrollOffset + step - advance * stride).roundToInt())
            } else if (abs(execution.consume(this@glideToItem, step)) < .1f && abs(step) > .5f) return@animate false
        }
        if (index in firstVisibleItemIndex..lastVisibleItemIndex) {
            val measured = calculateDistanceTo(index, offset).toFloat()
            if (abs(measured) < .5f) return@animate false
            if (sign(measured) != direction) { execution.consume(this@glideToItem, measured); return@animate false }
        }
        true
    }
}

private suspend fun LazyLayoutScrollScope.approachItem(index: Int, offset: Int, duration: Int, style: LongScrollStyle,
    fade: LongScrollFade?, viewport: () -> Int, columns: () -> Int, target: (() -> ListScrollTarget)? = null, grid: Boolean = false) {
    if (index !in 0 until itemCount) return
    withScrollExecution {
        val duration = if (grid) spec.gridMs else if (duration == 350) spec.listMs else duration
        val lead = (viewport() * 1.5f).toInt().coerceAtLeast(1)
        val far = abs(calculateDistanceTo(index, offset).toFloat()) > lead * 1.5f && index !in firstVisibleItemIndex..lastVisibleItemIndex
        val fading = style == LongScrollStyle.Fade && fade != null && far
        if (fading) fade!!.fading = true
        try {
            if (fading) animate(spec.fadeMs, fade = true) { p, _, _, _ -> fade!!.alpha = 1f - p; true }
            if (style != LongScrollStyle.FlyThrough) jumpNear(index, offset, lead, columns)
            glideToItem(index, offset, if (fading) (duration - spec.fadeMs).coerceAtLeast(1) else duration,
                this, if (style == LongScrollStyle.FlyThrough) LongScroll.flyJumpEvery.coerceAtLeast(1) else 0,
                viewport, columns, target) { if (fading) fade!!.alpha = fadeProgress(it) }
        } finally { if (fading) { fade!!.alpha = 1f; fade.fading = false } }
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
    style: LongScrollStyle = LongScroll.style, target: (() -> ListScrollTarget)? = null) {
    if (index < 0) return
    scroll {
        val scope = gridScrollScope(this@easeToItem, this)
        if (index < scope.itemCount) {
            if (reducedMotion) scope.snapToItem(index, -topInset.roundToInt())
            else scope.approachItem(index, -topInset.roundToInt(), 500, style, synchronized(fadeHolders) { fadeHolders[this@easeToItem] },
                { layoutInfo.viewportSize.height }, { (layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1 }, target, grid = true)
        }
    }
}

/** Resolve retargeting on the existing time curve, without a new mutation or readiness gap. */
internal suspend fun LazyGridState.easeToTarget(target: () -> ListScrollTarget, reducedMotion: Boolean) {
    val initial = target()
    easeToItem(initial.index, reducedMotion, -initial.offset.toFloat(), target = target)
}

package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/** The existing glass spring and edge-pressure recipes have one implementation, unchanged. */
internal fun glassMorphAnimationSpec(speed: Float) = spring<Float>(1f, 90f * speed * speed, visibilityThreshold = .0001f)
internal fun glassContactPressure(excess: Float) = -.22f * (1f - exp(-abs(excess) * 2.4f))

@Stable internal class PreviewContact internal constructor(private val owner: BrowsePinchProgressOwner,
    private val scope: CoroutineScope, private val limit: () -> Float, private val reduced: () -> Boolean) {
    var travel by mutableFloatStateOf(0f)
        private set
    private var raw by mutableFloatStateOf(0f)
    var pressed by mutableStateOf(false)
        private set
    private var settling: Job? = null
    val position get() = travel / limit()
    val pressure get() = glassContactPressure((raw - raw.coerceIn(-limit(), limit())) / limit())
    fun contact(value: Boolean) { pressed = value }
    fun start() { settling?.cancel(); raw = travel }
    fun dragBy(delta: Float) {
        raw += delta
        val boundary = limit()
        scope.launch { travel = raw.coerceIn(-boundary, boundary) }
    }
    fun release() {
        settling?.cancel()
        val boundary = limit()
        val reduceAtRelease = reduced()
        raw = raw.coerceIn(-boundary, boundary)
        settling = scope.launch {
            if (reduceAtRelease) travel = 0f else owner.animate(travel, 0f, glassMorphAnimationSpec(1f), budgeted = false) { travel = it }
            raw = 0f
        }
    }
}

@Composable internal fun rememberPreviewContactOwner(limit: () -> Float, reduced: Boolean): PreviewContact {
    val owner = remember { BrowsePinchProgressOwner() }
    BindBrowsePinchProgressClock(owner)
    val scope = rememberCoroutineScope()
    val currentLimit = rememberUpdatedState(limit)
    val currentReduced = rememberUpdatedState(reduced)
    return remember(owner, scope) { PreviewContact(owner, scope, { currentLimit.value() }, { currentReduced.value }) }
}

internal fun Modifier.previewContactLayer(contact: PreviewContact, scaleX: () -> Float, scaleY: () -> Float): Modifier =
    offset { IntOffset(contact.travel.toInt(), 0) }.presentedLayer({ 1f }) {
        this.scaleX = scaleX(); this.scaleY = scaleY()
        translationX = (1f - this.scaleX) * size.width / 2f * contact.position.coerceIn(-1f, 1f)
    }

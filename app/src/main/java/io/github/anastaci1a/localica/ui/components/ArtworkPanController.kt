package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.engine.BrowsePinchProgressOwner
import io.github.anastaci1a.localica.ui.engine.BindBrowsePinchProgressClock
import io.github.anastaci1a.localica.ui.engine.integratePan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ArtworkPanController internal constructor(
    private val owner: BrowsePinchProgressOwner, private val scope: CoroutineScope,
    private val transform: () -> ArtworkTransform, private val bounds: () -> ArtworkPanBounds,
    private val present: (ArtworkTransform) -> Unit,
) {
    private var job: Job? = null
    fun stop() { job?.cancel(); job = null }
    fun release(velocity: Offset, reduced: Boolean, stopSpeed: Float) {
        stop()
        if (reduced || transform().scale == 1f) return
        var pan = advanceArtworkPan(ArtworkPanMotion(transform().offset, velocity), bounds(), 0f, stopSpeed)
        if (!pan.moving) return
        job = scope.launch {
            owner.integratePan { seconds ->
                pan = advanceArtworkPan(pan, bounds(), seconds, stopSpeed)
                present(transform().copy(offset = pan.offset))
                pan.moving
            }
        }
    }
}

@Composable internal fun rememberArtworkPan(transform: () -> ArtworkTransform, bounds: () -> ArtworkPanBounds,
    present: (ArtworkTransform) -> Unit): ArtworkPanController {
    val owner = remember { BrowsePinchProgressOwner() }
    BindBrowsePinchProgressClock(owner)
    val scope = rememberCoroutineScope()
    val currentTransform = rememberUpdatedState(transform)
    val currentBounds = rememberUpdatedState(bounds)
    val currentPresent = rememberUpdatedState(present)
    return remember(owner, scope) { ArtworkPanController(owner, scope,
        { currentTransform.value() }, { currentBounds.value() }, { currentPresent.value(it) }) }
}

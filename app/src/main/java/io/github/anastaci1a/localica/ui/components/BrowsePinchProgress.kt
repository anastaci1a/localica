package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import io.github.anastaci1a.localica.ui.engine.BindBrowsePinchProgressClock
import io.github.anastaci1a.localica.ui.engine.BrowsePinchProgressOwner as EnginePinchProgressOwner

/** A family of retained transaction values driven by one bound root clock. */
internal class BrowsePinchProgressOwner {
    internal val motion = EnginePinchProgressOwner()
    fun create(initial: Float) = BrowsePinchProgress(initial, motion)
}

/** Transaction-local input and presented state; animation sampling belongs to Engine. */
@Stable
internal class BrowsePinchProgress(initial: Float,
    internal val motion: EnginePinchProgressOwner = EnginePinchProgressOwner()) {
    var value by mutableFloatStateOf(initial)
        private set
    var isRunning by mutableStateOf(false)
        private set
    private val mutations = MutatorMutex()
    private var fingerTarget = initial

    fun follow(target: Float) { fingerTarget = target }

    internal suspend fun mutateScene(block: suspend (Float, (Float) -> Unit) -> Unit) = animateState { block(value) { value = it } }

    private suspend fun <T> animateState(block: suspend () -> T): T = mutations.mutate {
        isRunning = true
        try { block() } finally { isRunning = false }
    }

    suspend fun snapTo(target: Float) = mutations.mutate { value = target }

    suspend fun animateTo(target: Float) = animateState {
        motion.settle(value, target) { value = it }
    }

    suspend fun catchUp(onCaughtUp: () -> Unit) = animateState {
        val from = value
        motion.catchUp(100) { fraction, terminal ->
            if (terminal) onCaughtUp()
            // The callback may change the target or cancel this transaction. Preserve the
            // same-frame write and its position after that callback, including cancellation.
            value = from + (fingerTarget - from) * fraction
        }
    }
}

@Composable
internal fun BindBrowsePinchProgress(owner: BrowsePinchProgressOwner) = BindBrowsePinchProgressClock(owner.motion)

package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tests explicitly bind the real Engine clock to their existing controlled frame source. */
internal fun CoroutineScope.bindPinchClock(owner: BrowsePinchProgressOwner): Job {
    val base = FrameBase()
    val clock = UiClock(object : FrameSource {
        override suspend fun awaitFrame() = awaitFrame { it }
        override suspend fun <R> awaitFrame(onFrame: (Long) -> R): R = withFrameNanos {
            base.frame(it)
            onFrame(it)
        }
    }, base::now, {})
    owner.bind(clock)
    return launch(start = CoroutineStart.UNDISPATCHED) { clock.run() }
}

package io.github.anastaci1a.localica.playback

import kotlinx.coroutines.*

/** Own native cleanup until durable detach completes; never block Main or wait indefinitely. */
internal fun finishPlaybackShutdown(checkpoint: Deferred<Unit>, release: () -> Unit): Job {
    val owner = SupervisorJob()
    val job = CoroutineScope(owner + Dispatchers.Main.immediate).launch {
        try {
            withTimeout(2000) { checkpoint.await() }
        } catch (failure: Exception) {
            android.util.Log.w("PlaybackShutdown", "Final cursor checkpoint unavailable before shutdown", failure)
        } finally {
            release()
        }
    }
    job.invokeOnCompletion { owner.cancel() }
    return job
}

package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Activity-only display admission. No controller, decoder, playback intent or saved library. */
internal class QueueDisplayLease(restoredQueueId: String? = null) {
    internal class Request internal constructor(internal val owner: QueueDisplayLease,
        internal val epoch: Long, val queueId: String, internal val route: Any)

    var queueId: String? by mutableStateOf(restoredQueueId)
        private set
    private var epoch = 0L
    private var disposed = false

    fun request(queueId: String, route: Any): Request? {
        if (disposed) return null
        return Request(this, ++epoch, queueId, route)
    }

    /** Invoke only after serialized browse succeeds, while the initiating presenter is current. */
    fun admit(request: Request, route: Any, exists: Boolean, presenterCurrent: Boolean): Boolean {
        if (disposed || request.owner !== this || request.epoch != epoch) return false
        ++epoch // Consume even rejection; a repeated callback cannot reopen a dismissed scene.
        if (request.route != route || !exists || !presenterCurrent) return false
        queueId = request.queueId
        return true
    }

    /** Route/dismissal cancellation fences pending callbacks but retains real collapse drawing. */
    fun invalidate() { ++epoch }

    /** Closed means a real settled endpoint with no held contact, not a zero phase sample. */
    fun reconcile(playbackPresent: Boolean, playerRequested: Boolean, queueRequested: Boolean,
        playerClosed: Boolean, queueClosed: Boolean) {
        if (playbackPresent || (!playerRequested && !queueRequested && playerClosed && queueClosed)) {
            queueId = null
        }
    }

    /** Loading is not evidence of deletion. An absent loaded destination requests real collapse;
     * the display remains admitted until reconcile observes both actual closed endpoints. */
    fun destinationMissing(loading: Boolean, exists: Boolean): Boolean {
        if (disposed || queueId == null || loading || exists) return false
        invalidate()
        return true
    }

    fun dispose() { disposed = true; ++epoch }
}

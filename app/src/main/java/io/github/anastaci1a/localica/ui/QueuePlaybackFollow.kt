package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anastaci1a.localica.browse.BrowserItem
import kotlinx.coroutines.Job

/** One exact occurrence, never a track-id or top-of-list fallback. */
internal class QueuePlaybackFollowRequest(val occurrenceId: String, val owner: Any, val modalGeneration: Long?) {
    fun targetIndex(items: List<BrowserItem>): Int? =
        items.indexOfFirst { it.row?.id == occurrenceId }.takeIf { it >= 0 }?.plus(1)
}

/** Route-local UI state. Only occurrence changes and explicit Return admit new motion;
 * publishing rows, playback checkpoints and releasing another owner do not replay it. */
internal class QueuePlaybackFollowCoordinator(initiallyAttached: Boolean = true) {
    var attached by mutableStateOf(initiallyAttached)
        private set
    var request by mutableStateOf<QueuePlaybackFollowRequest?>(null)
        private set
    var moving by mutableStateOf(false)
        private set
    private var entered = false
    private var occurrence: String? = null
    private var owner: Any? = null
    private var motion: Job? = null

    fun observe(target: String?, currentOwner: Any, eligible: Boolean, modalGeneration: Long?, automatic: Boolean = true) {
        if (owner !== currentOwner) { interrupt(); owner = currentOwner }
        val changed = occurrence != target
        occurrence = target
        if (!eligible) { interrupt(); return }
        val initial = !entered
        entered = true
        if ((initial || changed) && automatic && attached && target != null) submit(target, currentOwner, modalGeneration)
        else if (changed) cancel()
    }

    fun returnToCurrent(target: String?, currentOwner: Any, eligible: Boolean, modalGeneration: Long?): Boolean {
        if (!eligible || target == null) return false
        attached = true
        entered = true
        occurrence = target
        owner = currentOwner
        submit(target, currentOwner, modalGeneration)
        return true
    }

    private fun submit(target: String, currentOwner: Any, generation: Long?) {
        cancel()
        request = QueuePlaybackFollowRequest(target, currentOwner, generation)
    }

    fun bind(value: QueuePlaybackFollowRequest, job: Job): Boolean {
        if (request !== value) return false
        motion = job
        return true
    }

    fun beginMotion(value: QueuePlaybackFollowRequest): Boolean {
        if (request !== value) return false
        moving = true
        return true
    }

    /** Resting taps keep attachment; interrupting admitted navigation needs Return. */
    fun pointerDown() { if (request != null) attached = false; cancel() }
    fun userScroll() { attached = false; cancel() }

    /** Interrupted navigation must leave an explicit way to return after takeover. */
    fun interrupt() { if (request != null) attached = false; cancel() }

    fun cancel() {
        val oldMotion = motion
        motion = null
        request = null
        moving = false
        oldMotion?.cancel()
    }

    fun finish(value: QueuePlaybackFollowRequest) {
        if (request !== value) return
        motion = null
        request = null
        moving = false
    }
}

/** Account for every app-owned scroll. Unclaimed lazy scrolling includes accessibility
 * and keyboard actions, not only pointer drags; isScrollInProgress alone cannot tell. */
internal class BrowseScrollOwnership {
    var depth by mutableIntStateOf(0)
        private set
    val internal get() = depth > 0
    suspend fun <T> during(block: suspend () -> T): T {
        depth++
        try { return block() } finally { depth-- }
    }
}

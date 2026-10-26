package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anastaci1a.localica.browse.BrowserItem
import kotlinx.coroutines.Job

/** One exact occurrence, never a track-id or top-of-list fallback. */
internal class QueuePlaybackFollowRequest(val occurrenceId: String, val owner: Any, val modalGeneration: Long?,
    val initialPosition: Boolean = false) {
    fun targetIndex(items: List<BrowserItem>): Int? =
        items.indexOfFirst { it.row?.id == occurrenceId }.takeIf { it >= 0 }?.plus(1)
}

/** Route-local UI state. Only occurrence changes and explicit Return admit new motion;
 * publishing rows, playback checkpoints and releasing another owner do not replay it. */
internal class QueuePlaybackFollowCoordinator(initiallyAttached: Boolean = true, openingRequest: Long = 0,
    restored: Boolean = false) {
    companion object {
        fun restoreState(value: Any): QueuePlaybackFollowCoordinator? = when (value) {
            is Boolean -> QueuePlaybackFollowCoordinator(value, restored = true)
            is List<*> -> {
                val attached = value.getOrNull(0) as? Boolean
                val opening = value.getOrNull(1) as? Long
                if (attached != null && opening != null && opening >= 0)
                    QueuePlaybackFollowCoordinator(attached, opening, restored = true) else null
            }
            else -> null
        }
    }
    var consumedOpeningRequest = openingRequest
        private set
    var attached by mutableStateOf(initiallyAttached)
        private set
    var request by mutableStateOf<QueuePlaybackFollowRequest?>(null)
        private set
    var moving by mutableStateOf(false)
        private set
    private var entered = restored
    private var restoredObservation = restored
    private var occurrence: String? = null
    private var owner: Any? = null
    private var motion: Job? = null

    fun initialTarget(target: String?, currentOwner: Any, eligible: Boolean, openingRequest: Long): String? {
        if (!eligible || target == null) return null
        return target.takeIf { openingRequest > consumedOpeningRequest || !entered && attached ||
            request?.let { it.initialPosition && (it.owner === currentOwner ||
                openingRequest > 0 && openingRequest == consumedOpeningRequest) } == true }
    }

    /** First measurement/opening placement is independent of ongoing follow intent. */
    fun positionedInitially(target: String, currentOwner: Any, openingRequest: Long) {
        cancel()
        entered = true
        restoredObservation = false
        occurrence = target
        owner = currentOwner
        consumedOpeningRequest = maxOf(consumedOpeningRequest, openingRequest)
    }

    fun consumeOpening(openingRequest: Long) {
        consumedOpeningRequest = maxOf(consumedOpeningRequest, openingRequest)
    }

    fun observe(target: String?, currentOwner: Any, eligible: Boolean, modalGeneration: Long?, automatic: Boolean = true,
        openingRequest: Long = 0, initialEligible: Boolean = eligible) {
        if (owner !== currentOwner) { interrupt(); owner = currentOwner }
        val changed = !restoredObservation && occurrence != target
        restoredObservation = false
        occurrence = target
        if (!automatic) consumeOpening(openingRequest)
        if (automatic && initialEligible && target != null && openingRequest > consumedOpeningRequest) {
            consumedOpeningRequest = openingRequest
            entered = true
            submit(target, currentOwner, modalGeneration, initialPosition = true)
            return
        }
        if (automatic && initialEligible && target != null && request?.initialPosition == true) {
            if (request?.occurrenceId != target) submit(target, currentOwner, modalGeneration, initialPosition = true)
            return
        }
        if (!eligible) { interrupt(); return }
        val initial = !entered || request?.initialPosition == true
        entered = true
        if ((initial || changed) && automatic && attached && target != null) {
            if (request?.let { it.initialPosition == initial && it.occurrenceId == target && it.owner === currentOwner } != true)
                submit(target, currentOwner, modalGeneration, initialPosition = initial && initialEligible)
        }
        else if (changed) cancel()
    }

    fun returnToCurrent(target: String?, currentOwner: Any, eligible: Boolean, modalGeneration: Long?): Boolean {
        if (!eligible || target == null) return false
        attached = true
        entered = true
        restoredObservation = false
        occurrence = target
        owner = currentOwner
        submit(target, currentOwner, modalGeneration)
        return true
    }

    private fun submit(target: String, currentOwner: Any, generation: Long?, initialPosition: Boolean = false) {
        cancel()
        request = QueuePlaybackFollowRequest(target, currentOwner, generation, initialPosition)
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

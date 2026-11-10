package io.github.anastaci1a.localica.queue

/** Small immutable metadata for the complete durable sequence. It never contains occurrence-sized arrays. */
data class QueueStorageState(val entryCount: Int, val durationMs: Long, val knownDurations: Int,
    val currentIndex: Int, val revision: Long)

/**
 * A bounded playback projection, distinct from durable actual history and shuffle plans.
 * The positions refer to physical queue order even when visits follow a shuffled traversal.
 */
data class QueuePlaybackWindow(val visits: List<QueueVisit>, val currentToken: String?,
    val positions: Map<String, Int>, val modes: JourneyModes) {
    init {
        require(visits.size <= MaximumVisits)
        require(if (visits.isEmpty()) currentToken == null else visits.any { it.token == currentToken })
        require(visits.all { it.entryId in positions })
        require(positions.size <= MaximumVisits)
    }
    private val index get() = visits.indexOfFirst { it.token == currentToken }
    val current get() = visits.getOrNull(index)
    val previous get() = visits.getOrNull(index - 1)
    val next get() = visits.getOrNull(index + 1)

    fun artwork(radius: Int = 4): List<QueueVisit> {
        require(radius in 0..4)
        val center = index
        return (1..radius).flatMap { distance ->
            listOfNotNull(visits.getOrNull(center + distance), visits.getOrNull(center - distance))
        }
    }

    companion object {
        const val Radius = 64
        const val MaximumVisits = Radius * 2 + 1
    }
}

internal val ListeningQueue.currentVisit: QueueVisit? get() = if (playbackWindow != null) playbackWindow.current else journey?.current
internal val ListeningQueue.previousVisit: QueueVisit? get() = if (playbackWindow != null) playbackWindow.previous else journey?.previous
internal val ListeningQueue.nextVisit: QueueVisit? get() = if (playbackWindow != null) playbackWindow.next else journey?.next
internal fun ListeningQueue.playbackVisits(): List<QueueVisit> = playbackWindow?.visits
    ?: requireNotNull(journey) { "Playback requires a prepared journey or durable window" }.window()

package io.github.anastaci1a.localica.queue

/** Capture is actor-owned; membership/recovery admission and persistence remain at the caller. */
internal inline fun ListeningQueue.captureVisit(token: String, position: () -> Long): ListeningQueue? {
    val previous = journey ?: return null
    // arrive already returns this for its current token; avoid preparing its unused IDs.
    val arrived = if (previous.current?.token == token) previous else previous.arrive(token, entries.map { it.id })
    if (arrived.current?.token != token) return null
    val entryId = arrived.current?.entryId
    val capturedPosition = position()
    if (arrived === previous && currentEntryId == entryId && positionMs == capturedPosition) return this
    return copy(journey = arrived, currentEntryId = entryId, positionMs = capturedPosition)
}

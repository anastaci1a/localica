package io.github.anastaci1a.localica.queue

/** Clip the native view at unsafe visits without skipping or editing the durable journey. */
internal fun recoveryWindow(queue: ListeningQueue, blocked: (String) -> Boolean): List<QueueVisit> {
    val journey = queue.journey ?: return emptyList()
    val window = journey.window()
    val current = window.indexOfFirst { it.token == journey.current?.token }
    val blockedEntries = queue.entries.filter { blocked(it.track.id) }.mapTo(hashSetOf()) { it.id }
    if (current < 0 || window[current].entryId in blockedEntries) return emptyList()
    val start = (0 until current).lastOrNull { window[it].entryId in blockedEntries }?.plus(1) ?: 0
    val end = (current + 1 until window.size).firstOrNull { window[it].entryId in blockedEntries } ?: window.size
    return window.subList(start, end)
}

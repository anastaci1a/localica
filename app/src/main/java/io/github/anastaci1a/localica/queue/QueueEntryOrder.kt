package io.github.anastaci1a.localica.queue

/** True only when order is the sole possible entry-list change; occurrence IDs must be unique. */
internal fun queueEntryOrderEligible(before: List<QueueEntry>, after: List<QueueEntry>): Boolean {
    if (before.size != after.size) return false
    val remaining = HashMap<String, QueueEntry>()
    for (entry in before) if (remaining.put(entry.id, entry) != null) return false
    for (entry in after) if (remaining.remove(entry.id) != entry) return false
    return remaining.isEmpty()
}

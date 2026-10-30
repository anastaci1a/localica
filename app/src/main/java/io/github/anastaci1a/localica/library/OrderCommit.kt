package io.github.anastaci1a.localica.library

/** An order command's committed result, captured before the next serialized command runs. */
data class OrderCommit(
    /** Opaque identity of the state-published immutable entries list; compare with ===. */
    val revision: Any,
    val order: SavedOrder,
    /** Only physical occurrence order or saved ordering changed, not metadata or playback. */
    val changed: Boolean,
    /** Present only when the command's target is the active queue at completion. */
    val currentEntryId: String? = null,
) {
    companion object {
        /** Compare ordering only; unchanged published identity needs no occurrence traversal. */
        internal inline fun <T> hasChanged(
            beforeOrder: SavedOrder,
            afterOrder: SavedOrder,
            beforeEntries: List<T>,
            afterEntries: List<T>,
            occurrenceId: (T) -> String,
        ): Boolean = beforeOrder != afterOrder || beforeEntries !== afterEntries &&
            (beforeEntries.size != afterEntries.size || beforeEntries.indices.any {
                occurrenceId(beforeEntries[it]) != occurrenceId(afterEntries[it])
            })
    }
}

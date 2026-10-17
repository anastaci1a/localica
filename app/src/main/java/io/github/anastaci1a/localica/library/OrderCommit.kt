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
)

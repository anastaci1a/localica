package io.github.anastaci1a.localica.library

/** A popup-session checkpoint owns order only, never collection contents or playback state. */
data class OrderCheckpoint(val physical: List<String>, val saved: SavedOrder) {
    /** Reconcile both independent orders against actor-current membership. */
    fun reconcile(ids: List<String>) = OrderCheckpoint(
        SavedOrder(physical).reconcile(ids).manual, saved.reconcile(ids))

    /** Membership changes alone do not offer a restore that would leave the order unchanged. */
    fun differsFrom(ids: List<String>): Boolean = SavedOrder(physical).reconcile(ids).manual != ids

    companion object {
        fun capture(ids: List<String>, saved: SavedOrder) =
            OrderCheckpoint(ids.toList(), saved.copy(manual = saved.manual.toList()))
    }
}

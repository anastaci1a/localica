package io.github.anastaci1a.localica.ui

/** Geometry only: the caller owns pixels, lazy-item retention and the existing pinch clock.
 * All rectangles use untransformed pixels in one viewport, including offscreen coordinates. */
internal class CollectionHeaderPinch {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        init {
            require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
            require(right >= left && bottom >= top)
        }
    }
    data class Offset(val x: Float, val y: Float)
    data class Frame(val presented: Rect, val liveOffset: Offset?, val destinationReady: Boolean)
    enum class Endpoint { Source, Destination }

    private class Transaction(val generation: Long, val source: Rect) {
        var destination: Rect? = null
        var handoff: Endpoint? = null
    }
    private var newestGeneration: Long? = null
    private var transaction: Transaction? = null

    /** Capture once, before preview reflow. A retired generation cannot begin again. */
    fun begin(generation: Long, source: Rect): Boolean {
        if (newestGeneration?.let { generation <= it } == true) return false
        newestGeneration = generation
        transaction = Transaction(generation, source)
        return true
    }

    /** Supply measured destination bounds after anchor correction, never inferred row pitches.
     * The caller must publish these before advancing displayed pinch progress: late geometry
     * is used at the supplied fraction immediately, without a second clock or hidden retiming.
     * Handoff freezes these bounds so a rollback remount cannot replace the destination. */
    fun updateDestination(generation: Long, destination: Rect): Boolean {
        val current = owned(generation) ?: return false
        if (current.handoff != null) return false
        current.destination = destination
        return true
    }

    /** Missing destination geometry holds the known source; missing live geometry stays unknown.
     * The offset moves only the live origin: this helper neither scales nor draws its contents. */
    fun frame(generation: Long, progress: Float, live: Rect?): Frame? {
        val current = owned(generation) ?: return null
        require(progress.isFinite())
        val source = current.source
        val destination = current.destination
        val p = progress.coerceIn(0f, 1f)
        val presented = when {
            destination == null || destination == source || p == 0f -> source
            p == 1f -> destination
            else -> {
                fun mix(a: Float, b: Float) = a * (1f - p) + b * p
                Rect(mix(source.left, destination.left), mix(source.top, destination.top),
                    mix(source.right, destination.right), mix(source.bottom, destination.bottom))
            }
        }
        return Frame(presented, live?.let { Offset(presented.left - it.left, presented.top - it.top) },
            destination != null)
    }

    /** Call when requesting the final committed/rollback layout, not merely on finger-up. */
    fun beginHandoff(generation: Long, endpoint: Endpoint): Boolean {
        val current = owned(generation) ?: return false
        if (endpoint == Endpoint.Destination && current.destination == null) return false
        if (current.handoff != null && current.handoff != endpoint) return false
        current.handoff = endpoint
        return true
    }

    /** The caller must observe the chosen layout mounted and anchor-corrected before supplying
     * its bounds. Exact endpoint agreement makes clearing ownership an identity operation. */
    fun retireAfterHandoff(generation: Long, progress: Float, laidOut: Rect?): Boolean {
        val current = owned(generation) ?: return false
        require(progress.isFinite())
        val endpoint = current.handoff ?: return false
        val destination = endpoint == Endpoint.Destination
        val expected = if (destination) current.destination else current.source
        if (progress.coerceIn(0f, 1f) != (if (destination) 1f else 0f) || laidOut != expected) return false
        transaction = null
        return true
    }

    /** Route disposal needs no drawing handoff; delayed callbacks still cannot resurrect it. */
    fun discard(generation: Long): Boolean {
        owned(generation) ?: return false
        transaction = null
        return true
    }

    private fun owned(generation: Long): Transaction? = transaction?.takeIf { it.generation == generation }
}

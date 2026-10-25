package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.CompletableDeferred

/** Immutable draw evidence, kept with the bitmap rather than inferred from later draws. */
internal data class BackdropProducerDraws(val outer: Int, val mask: Int?)
internal data class BackdropReadbackDraws(val request: Int,
    val producers: Map<Any, BackdropProducerDraws>, val composite: Int)
internal data class BackdropReadbackProducer(val geometry: Any?, val masked: Boolean)
internal data class BackdropReadbackScene(val geometry: Any?,
    val producers: Map<Any, BackdropReadbackProducer>)

/** Main-thread, non-observable handshake for one of the adapter's existing capture attempts. */
internal class BackdropReadback(
    private val scene: () -> BackdropReadbackScene,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    var invalidateComposite: (() -> Unit)? = null
    private var pending: Ticket? = null
    val isPending: Boolean get() = pending?.result?.isCompleted == false

    inner class Ticket internal constructor(val request: Int,
        internal val expected: BackdropReadbackScene) {
        internal val result = CompletableDeferred<BackdropReadbackDraws?>()
        internal val producers = mutableMapOf<Any, BackdropProducerDraws>()
        internal var composite: BackdropReadbackDraws? = null
        internal var redrawRequested = false
        internal var cancelRedraw: (() -> Unit)? = null
        internal var cancelPost: (() -> Unit)? = null
        internal var cancelTimeout: (() -> Unit)? = null
        var abortReason: String? = null
            private set

        suspend fun awaitDraws(): BackdropReadbackDraws? = result.await()
        fun isCurrent(): Boolean = pending === this && abortReason == null && scene() == expected
        fun close() = abort("closed")
        // Build only for an aborted capture, never on idle/modern draws.
        fun progress(): String = "request=$request producers=$producers " +
            "expectedMasks=${expected.producers.mapValues { it.value.masked }} " +
            "composite=${composite?.composite} redrawRequested=$redrawRequested"
        internal fun abort(reason: String) {
            if (abortReason == null) abortReason = reason
            cancelRedraw?.invoke(); cancelRedraw = null
            cancelPost?.invoke(); cancelPost = null
            cancelTimeout?.invoke(); cancelTimeout = null
            result.complete(null)
            if (pending === this) pending = null
        }
    }

    fun begin(request: Int): Ticket {
        pending?.abort("replaced")
        val ticket = Ticket(request, scene())
        pending = ticket
        // Native uptime, supplied by Handler rather than Compose's virtual frame clock.
        // A missing draw fails closed; it never creates a third capture or a refresh loop.
        ticket.cancelTimeout = schedule(500) {
            if (pending === ticket && !ticket.result.isCompleted) ticket.abort("draw-timeout")
        }
        return ticket
    }

    fun sceneChanged() {
        pending?.takeUnless { it.isCurrent() }?.abort("scene-changed")
    }

    fun dispose() { pending?.abort("disposed") }

    fun producerRecorded(producer: Any, request: Int) {
        val ticket = current(request) ?: return
        if (producer !in ticket.expected.producers) return
        val previous = ticket.producers[producer]
        ticket.producers[producer] = BackdropProducerDraws(request, previous?.mask)
        requestComposite(ticket)
    }

    fun producerMasked(producer: Any, request: Int) {
        val ticket = current(request) ?: return
        if (ticket.expected.producers[producer]?.masked != true) return
        val previous = ticket.producers[producer]
        ticket.producers[producer] = BackdropProducerDraws(previous?.outer ?: -1, request)
        requestComposite(ticket)
    }

    private fun requestComposite(ticket: Ticket) {
        val ready = ticket.expected.producers.all { (producer, expected) ->
            val drawn = ticket.producers[producer]
            drawn?.outer == ticket.request && drawn.mask == ticket.request.takeIf { expected.masked }
        }
        if (ready && !ticket.redrawRequested) {
            // Outer and mask records may finish in either order across retained layers.
            // Post once: inline invalidation can be cleared by an enclosing record().
            ticket.redrawRequested = true
            ticket.cancelRedraw = schedule(0) {
                if (ticket.isCurrent() && !ticket.result.isCompleted) invalidateComposite?.invoke()
            }
        }
    }

    fun compositeRecorded(request: Int, included: Map<Any, BackdropProducerDraws>) {
        val ticket = current(request) ?: return
        val expected = ticket.expected.producers.mapValues { (_, producer) ->
            BackdropProducerDraws(request, request.takeIf { producer.masked })
        }
        if (included != expected || ticket.producers != expected) return
        ticket.composite = BackdropReadbackDraws(request, included.toMap(), request)
        if (ticket.cancelPost != null) return
        // Completing inline can resume Main.immediate inside an enclosing record().
        ticket.cancelPost = schedule(0) {
            if (ticket.isCurrent()) {
                ticket.cancelTimeout?.invoke(); ticket.cancelTimeout = null
                ticket.result.complete(ticket.composite)
            } else ticket.abort("scene-changed")
        }
    }

    private fun current(request: Int): Ticket? {
        val ticket = pending?.takeIf { it.request == request && !it.result.isCompleted } ?: return null
        if (!ticket.isCurrent()) { ticket.abort("scene-changed"); return null }
        return ticket
    }
}

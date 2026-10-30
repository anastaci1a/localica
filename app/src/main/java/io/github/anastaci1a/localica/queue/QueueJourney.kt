package io.github.anastaci1a.localica.queue

import java.util.UUID
import kotlin.random.Random

/** A visit is distinct from its queue occurrence: repeat cycles may visit the same occurrence again. */
data class QueueVisit(val entryId: String, val cycle: Long, val token: String = UUID.randomUUID().toString())

data class JourneyModes(val shuffle: Boolean = false, val loop: Boolean = false, val reshuffle: Boolean = false) {
    companion object {
        fun from(settings: QueueSettings) = JourneyModes(settings.shuffle,
            settings.repeat == QueueRepeat.All || settings.advancedRepeat?.queueEnd == QueueEnd.Repeat,
            settings.advancedRepeat?.reshuffle == true)
    }
}

/** Actual visits plus mode-owned traversal shared by transport, preloading and artwork. */
data class QueueJourney(
    val history: List<QueueVisit> = emptyList(),
    val cursor: Int = -1,
    val future: List<QueueVisit> = emptyList(),
    val cycle: Long = 0,
    val visited: Set<String> = emptySet(),
    val plans: Map<Long, List<String>> = emptyMap(),
    val modes: JourneyModes = JourneyModes(),
    val orderedTraversalId: String? = null,
    val orderedAnchorToken: String? = null,
) {
    val current: QueueVisit? get() = history.getOrNull(cursor)
    val previous: QueueVisit? get() = if (modes.shuffle || plans.isEmpty()) history.getOrNull(cursor - 1)
        else ordered()?.at(-1)
    val next: QueueVisit? get() = if (modes.shuffle || plans.isEmpty()) history.getOrNull(cursor + 1) ?: future.firstOrNull()
        else ordered()?.at(1)

    /** Ordered transport projects neighbors; an unplayed neighbor is never inserted into actual history. */
    private class Ordered(val order: List<String>, val index: Int, val current: QueueVisit,
        val namespace: String, val anchor: QueueVisit?, val loop: Boolean) {
        fun at(offset: Int): QueueVisit? {
            if (offset == 0) return current
            val position = index.toLong() + offset
            val shift = Math.floorDiv(position, order.size.toLong())
            if (!loop && shift != 0L) return null
            if (shift > 0 && current.cycle > Long.MAX_VALUE - shift ||
                shift < 0 && current.cycle < Long.MIN_VALUE - shift) return null
            val cycle = current.cycle + shift
            val entry = order[Math.floorMod(position, order.size.toLong()).toInt()]
            if (anchor?.entryId == entry && anchor.cycle == cycle) return anchor
            // Length framing keeps arbitrary occurrence IDs unambiguous; cycles may be negative after Previous-wrap.
            val token = UUID.nameUUIDFromBytes("${namespace.length}:$namespace:$cycle:${entry.length}:$entry"
                .toByteArray(Charsets.UTF_8)).toString()
            return QueueVisit(entry, cycle, token)
        }
    }
    private fun ordered(): Ordered? {
        val selected = current ?: return null
        val order = plans[cycle] ?: plans.values.firstOrNull() ?: return null
        val index = order.indexOf(selected.entryId)
        if (index < 0) return null
        val namespace = orderedTraversalId ?: return null
        return Ordered(order, index, selected, namespace, history.firstOrNull { it.token == orderedAnchorToken }, modes.loop)
    }

    /** Nearest visits first in both directions, using transport's durable shuffle/ordered plan. */
    fun artworkWindow(radius: Int = 4): List<QueueVisit> {
        require(radius in 0..4)
        val visits = window()
        val center = visits.indexOfFirst { it.token == current?.token }
        if (center < 0) return emptyList()
        return (1..radius).flatMap { distance ->
            listOfNotNull(visits.getOrNull(center + distance), visits.getOrNull(center - distance))
        }
    }

    /** Bound the player timeline without discarding durable history. */
    fun window(): List<QueueVisit> {
        if (!modes.shuffle && plans.isNotEmpty()) {
            val ordered = ordered() ?: return emptyList()
            return (-64..64).mapNotNull(ordered::at)
        }
        val start = (cursor - 64).coerceAtLeast(0)
        val end = cursor + 65
        return history.subList(start.coerceAtMost(history.size), end.coerceIn(start.coerceAtMost(history.size), history.size)) +
            if (end > history.size) future.take(end - history.size) else emptyList()
    }

    fun select(entryId: String, ids: List<String>, random: Random = Random.Default): QueueJourney {
        require(entryId in ids)
        if (modes.shuffle && visited.containsAll(ids)) return restart(ids, entryId, random)
        val visit = QueueVisit(entryId, cycle)
        val kept = history.take(cursor + 1) + visit
        return copy(history = kept, cursor = kept.lastIndex, visited = visited + entryId,
            orderedTraversalId = if (modes.shuffle) null else UUID.randomUUID().toString(),
            orderedAnchorToken = if (modes.shuffle) null else visit.token).planned(ids, random)
    }

    fun arrive(token: String, ids: List<String>, random: Random = Random.Default): QueueJourney {
        if (token == current?.token) return this
        if (!modes.shuffle && plans.isNotEmpty()) {
            // Old shuffled tokens are not ordered-neighbor identities, even when their entries still exist.
            val visit = window().firstOrNull { it.token == token } ?: return this
            val old = history.indexOfFirst { it.token == token }
            val recorded = if (old >= 0) history else history + visit
            val membership = if (visit.cycle == cycle) visited else
                recorded.filter { it.cycle == visit.cycle }.mapTo(mutableSetOf()) { it.entryId }
            return copy(history = recorded, cursor = if (old >= 0) old else recorded.lastIndex, cycle = visit.cycle,
                visited = membership + visit.entryId).planned(ids, random)
        }
        val old = history.indexOfFirst { it.token == token }
        if (old >= 0) return copy(cursor = old)
        val visit = future.firstOrNull { it.token == token } ?: return this
        val kept = history.take(cursor + 1) + visit
        return copy(history = kept, cursor = kept.lastIndex, cycle = visit.cycle,
            visited = (if (visit.cycle == cycle) visited else emptySet()) + visit.entryId)
            .planned(ids, random)
    }

    private data class Reconciliation(val ids: List<String>, val currentEntryId: String?, val modes: JourneyModes)
    // Only fresh normalized outputs receive this marker. Generated copies and restored values start cold.
    private var reconciliation: Reconciliation? = null

    /** Remove invalid occurrences, retaining valid visits on both sides of the cursor. */
    fun reconcile(ids: List<String>, currentEntryId: String?, requested: JourneyModes,
        random: Random = Random.Default): QueueJourney {
        reconciliation?.let { previous ->
            if (previous.currentEntryId == currentEntryId && previous.modes == requested && previous.ids == ids) return this
        }
        return reconcileUnprepared(ids, currentEntryId, requested, random).also { result ->
            // Duplicate occurrences/plans can change future-token ownership on another repair pass.
            // Preserve that repair rather than treating malformed data as a reusable fixed point.
            if (ids.toSet().size == ids.size && result.plans.values.all { it.toSet().size == it.size }) {
                result.reconciliation = Reconciliation(ids.toList(), currentEntryId, requested)
            }
        }
    }

    private fun reconcileUnprepared(ids: List<String>, currentEntryId: String?, requested: JourneyModes,
        random: Random): QueueJourney {
        if (ids.isEmpty()) return QueueJourney(modes = requested)
        val valid = ids.toSet()
        val selected = currentEntryId?.takeIf { it in valid } ?: ids.first()
        var kept = history.filter { it.entryId in valid }
        var position = history.take(cursor + 1).count { it.entryId in valid } - 1
        var membership = visited.intersect(valid)
        val newSelection = kept.getOrNull(position)?.entryId != selected
        if (newSelection) {
            val at = (position + 1).coerceIn(0, kept.size)
            kept = kept.take(at) + QueueVisit(selected, cycle) + kept.drop(at)
            position = at
            membership += selected
        }
        val activeCycle = if (requested.shuffle) cycle else kept[position].cycle
        if (activeCycle != cycle) membership = kept.filter { it.cycle == activeCycle }.mapTo(mutableSetOf()) { it.entryId }
        val changedShuffle = modes.shuffle != requested.shuffle
        if (changedShuffle && requested.shuffle) {
            // Enabling shuffle starts a fresh traversal at the same real visit; statistics are independent.
            val anchor = kept[position]
            return QueueJourney(history = listOf(anchor), cursor = 0, cycle = anchor.cycle,
                visited = setOf(anchor.entryId), modes = requested).planned(ids, random)
        }
        val newOrderedTraversal = !requested.shuffle && (changedShuffle || newSelection || orderedTraversalId == null || orderedAnchorToken == null)
        val changedCycles = changedShuffle || modes.reshuffle != requested.reshuffle
        val retainedPlans = plans.filterKeys { !changedCycles || it == cycle && !changedShuffle }
        return copy(history = kept, cursor = position, cycle = activeCycle, visited = membership, modes = requested,
            plans = retainedPlans, future = future.filter { it.entryId in valid },
            orderedTraversalId = if (requested.shuffle) null else if (newOrderedTraversal) UUID.randomUUID().toString() else orderedTraversalId,
            orderedAnchorToken = if (requested.shuffle) null else if (newOrderedTraversal) kept[position].token else orderedAnchorToken)
            .planned(ids, random)
    }

    /** Explicit queue-end reset starts a fresh cycle without inventing earlier visits. */
    fun restart(ids: List<String>, entryId: String, random: Random = Random.Default): QueueJourney {
        require(entryId in ids)
        check(cycle < Long.MAX_VALUE) { "Queue repeat cycle is exhausted" }
        val nextCycle = cycle + 1
        val visit = QueueVisit(entryId, nextCycle)
        val kept = history.take(cursor + 1) + visit
        val order = plans[nextCycle] ?: if (modes.shuffle && !modes.reshuffle) plans[cycle] else null
        return copy(history = kept, cursor = kept.lastIndex, cycle = nextCycle, visited = setOf(entryId),
            plans = order?.let { mapOf(nextCycle to it) }.orEmpty(),
            orderedTraversalId = if (modes.shuffle) null else UUID.randomUUID().toString(),
            orderedAnchorToken = if (modes.shuffle) null else visit.token).planned(ids, random)
    }

    private fun planned(ids: List<String>, random: Random): QueueJourney {
        val valid = ids.toSet()
        val prepared = linkedMapOf<Long, List<String>>()
        val lastCycle = if (!modes.loop) cycle else if (cycle <= Long.MAX_VALUE - 2) cycle + 2 else Long.MAX_VALUE
        for (number in cycle..lastCycle) {
            val retained = when {
                !modes.shuffle -> ids
                plans[number] != null -> plans.getValue(number).filter { it in valid }
                number > cycle && !modes.reshuffle -> prepared.getValue(number - 1)
                number == cycle -> {
                    val first = history.firstOrNull { it.cycle == number }?.entryId?.takeIf { it in valid }
                    listOfNotNull(first) + ids.filterNot { it == first }.shuffled(random)
                }
                else -> ids.shuffled(random)
            }
            // Keep traversal order in the list, but do not scan it for every occurrence.
            val retainedIds = retained.toHashSet()
            val added = ids.filterNot { it in retainedIds }.let { if (modes.shuffle) it.shuffled(random) else it }
            prepared[number] = retained + added
        }
        val oldVisits = future.associateBy { it.cycle to it.entryId }
        val pending = prepared.flatMap { (number, order) ->
            val remaining = if (number != cycle) order else if (modes.shuffle) order.filterNot { it in visited }
                else order.drop((order.indexOf(current?.entryId) + 1).coerceAtLeast(0))
            remaining.map { id -> oldVisits[number to id] ?: QueueVisit(id, number) }
        }
        return copy(plans = prepared, future = pending)
    }

    companion object {
        fun start(ids: List<String>, currentEntryId: String?, modes: JourneyModes,
            random: Random = Random.Default): QueueJourney = QueueJourney(modes = modes).reconcile(ids, currentEntryId, modes, random)
    }
}

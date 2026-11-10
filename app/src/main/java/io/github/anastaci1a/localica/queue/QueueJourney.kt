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

/** Visits plus mode-owned traversal shared by transport, preloading and artwork. */
data class QueueJourney(
    // Explicit Play Next visits can be reserved after the cursor; visited records actual arrivals.
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
    // At the start of a shuffle traversal begun mid-queue (#826), Previous continues in queue order behind it.
    val previous: QueueVisit? get() = if (modes.shuffle || plans.isEmpty()) history.getOrNull(cursor - 1) ?: behindStart()
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

    /**
     * The queue-order neighbor behind the oldest visit, kept only by a traversal that began when shuffle was
     * enabled. It is one entry id and index under [BEHIND_START], so it costs nothing per queue size, persists as an
     * ordinary plan row and is refreshed against the live queue order when the queue or the oldest visit changes.
     */
    private fun behindStart(): QueueVisit? {
        val entry = plans[BEHIND_START]?.firstOrNull() ?: return null
        val oldest = history.firstOrNull() ?: return null
        // The token is derived, so every call and every restored copy names the same unplayed visit.
        val token = UUID.nameUUIDFromBytes("behind:${oldest.token.length}:${oldest.token}:$entry"
            .toByteArray(Charsets.UTF_8)).toString()
        return QueueVisit(entry, oldest.cycle, token)
    }

    /** The row is [neighbor id, neighbor queue index]; the index lets going back step without scanning the queue. */
    private fun behindPlan(ids: List<String>, indexOfOldest: Int? = null): Map<Long, List<String>> {
        if (!modes.shuffle || BEHIND_START !in plans) return emptyMap()
        val oldest = history.firstOrNull()?.entryId ?: return mapOf(BEHIND_START to emptyList())
        // A remembered index is trusted only while the queue still has the oldest visit there.
        val index = indexOfOldest?.takeIf { ids.getOrNull(it) == oldest } ?: ids.indexOf(oldest)
        val neighbor = when {
            index > 0 -> index - 1
            // Repeat All wraps backwards exactly as ordered Previous does; a one-song queue has nowhere to go.
            index == 0 && modes.loop && ids.size > 1 -> ids.lastIndex
            else -> null
        }
        return mapOf(BEHIND_START to listOfNotNull(neighbor?.let { ids[it] }, neighbor?.toString()))
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
        // The unplayed queue-order neighbor joins the native timeline so Previous lands on a prepared item.
        val behind = if (start == 0) listOfNotNull(behindStart()) else emptyList()
        // The window never exceeds 129 visits: the neighbor takes the slot of the farthest forward visit.
        val end = if (behind.isEmpty()) cursor + 65 else (cursor + 65).coerceAtMost(128)
        return behind + history.subList(start.coerceAtMost(history.size), end.coerceIn(start.coerceAtMost(history.size), history.size)) +
            if (end > history.size) future.take(end - history.size) else emptyList()
    }

    // Unplayed explicit visits use the active cycle even while retracing older history.
    // Membership distinguishes them from already-played forward history without new stored state.
    private fun scheduledNext(): List<QueueVisit> = if (!modes.shuffle) emptyList() else
        history.drop(cursor + 1).filter { it.cycle == cycle && it.entryId !in visited }

    fun select(entryId: String, ids: List<String>, random: Random = Random.Default): QueueJourney {
        require(entryId in ids)
        if (modes.shuffle && visited.containsAll(ids)) return restart(ids, entryId, random)
        val visit = QueueVisit(entryId, cycle)
        val kept = history.take(cursor + 1) + visit
        return copy(history = kept + scheduledNext().filterNot { it.entryId == entryId }, cursor = kept.lastIndex, visited = visited + entryId,
            orderedTraversalId = if (modes.shuffle) null else UUID.randomUUID().toString(),
            orderedAnchorToken = if (modes.shuffle) null else visit.token).planned(ids, random)
    }

    /** Explicit next visits precede retraceable history without becoming visited until arrival. */
    fun playNext(added: List<String>, ids: List<String>, random: Random = Random.Default): QueueJourney {
        require(added.toSet().size == added.size && ids.toSet().containsAll(added))
        val anchor = current ?: return reconcile(ids, ids.firstOrNull(), modes, random)
        if (!modes.shuffle) return reconcile(ids, anchor.entryId, modes, random)
        val existing = (history.asSequence() + future.asSequence()).map { it.entryId }.toHashSet()
        require(added.none { it in existing })
        val visits = added.map { QueueVisit(it, cycle) }
        return copy(history = history.take(cursor + 1) + visits + history.drop(cursor + 1))
            .planned(ids, random)
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
        if (old >= 0) {
            val visit = history[old]
            val reserved = scheduledNext().mapTo(hashSetOf()) { it.token }
            val skipped = if (old > cursor) history.subList(cursor + 1, old).filter { it.token in reserved } else emptyList()
            if (skipped.isEmpty()) return copy(cursor = old,
                visited = if (visit.cycle == cycle) visited + visit.entryId else visited)
            val skippedTokens = skipped.mapTo(hashSetOf()) { it.token }
            val prefix = history.take(old + 1).filterNot { it.token in skippedTokens }
            return copy(history = prefix + skipped + history.drop(old + 1), cursor = prefix.lastIndex,
                visited = if (visit.cycle == cycle) visited + visit.entryId else visited).planned(ids, random)
        }
        behindStart()?.takeIf { it.token == token }?.let { visit ->
            // Going back past the traversal's start joins the retraceable history and counts as visited for the cycle.
            return copy(history = listOf(visit) + history, cursor = 0,
                visited = if (visit.cycle == cycle) visited + visit.entryId else visited)
                .planned(ids, random, refreshBehind = true, indexOfOldest = plans[BEHIND_START]?.getOrNull(1)?.toIntOrNull())
        }
        val visit = future.firstOrNull { it.token == token } ?: return this
        val kept = history.take(cursor + 1) + visit
        val scheduled = scheduledNext().filterNot { it.entryId == visit.entryId }
            .map { if (it.cycle == visit.cycle) it else it.copy(cycle = visit.cycle) }
        return copy(history = kept + scheduled, cursor = kept.lastIndex, cycle = visit.cycle,
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
        val valid = ids.toSet()
        return reconcileUnprepared(ids, currentEntryId, requested, random, valid).also { result ->
            // Duplicate occurrences/plans can change future-token ownership on another repair pass.
            // Preserve that repair rather than treating malformed data as a reusable fixed point.
            if (valid.size == ids.size && result.plans.values.all { it.toSet().size == it.size }) {
                result.reconciliation = Reconciliation(ids.toList(), currentEntryId, requested)
            }
        }
    }

    private fun reconcileUnprepared(ids: List<String>, currentEntryId: String?, requested: JourneyModes,
        random: Random, valid: Set<String>): QueueJourney {
        if (ids.isEmpty()) return QueueJourney(modes = requested)
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
            // Going back from the anchor follows the queue's order; planned() fills the marker row.
            return QueueJourney(history = listOf(anchor), cursor = 0, cycle = anchor.cycle,
                visited = setOf(anchor.entryId), plans = mapOf(BEHIND_START to emptyList()), modes = requested)
                .planned(ids, random, refreshBehind = true)
        }
        val newOrderedTraversal = !requested.shuffle && (changedShuffle || newSelection || orderedTraversalId == null || orderedAnchorToken == null)
        val changedCycles = changedShuffle || modes.reshuffle != requested.reshuffle
        val retainedPlans = plans.filterKeys {
            !changedCycles || it == cycle && !changedShuffle || it == BEHIND_START && requested.shuffle && !changedShuffle
        }
        return copy(history = kept, cursor = position, cycle = activeCycle, visited = membership, modes = requested,
            plans = retainedPlans, future = future.filter { it.entryId in valid },
            orderedTraversalId = if (requested.shuffle) null else if (newOrderedTraversal) UUID.randomUUID().toString() else orderedTraversalId,
            orderedAnchorToken = if (requested.shuffle) null else if (newOrderedTraversal) kept[position].token else orderedAnchorToken)
            .planned(ids, random, refreshBehind = true)
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
            plans = order?.let { mapOf(nextCycle to it) }.orEmpty() + plans.filterKeys { it == BEHIND_START },
            orderedTraversalId = if (modes.shuffle) null else UUID.randomUUID().toString(),
            orderedAnchorToken = if (modes.shuffle) null else visit.token).planned(ids, random)
    }

    /** The queue-order neighbor is looked up only where the oldest visit or the queue changes, never per step. */
    private fun planned(ids: List<String>, random: Random, refreshBehind: Boolean = false,
        indexOfOldest: Int? = null): QueueJourney {
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
        val scheduled = history.drop(cursor + 1).groupBy { it.cycle }
            .mapValues { (_, visits) -> visits.mapTo(hashSetOf()) { it.entryId } }
        val pending = prepared.flatMap { (number, order) ->
            val remaining = if (number != cycle) order else if (modes.shuffle) order.filterNot { it in visited }
                else order.drop((order.indexOf(current?.entryId) + 1).coerceAtLeast(0))
            remaining.filterNot { modes.shuffle && it in scheduled[number].orEmpty() }
                .map { id -> oldVisits[number to id] ?: QueueVisit(id, number) }
        }
        val behind = if (refreshBehind) behindPlan(ids, indexOfOldest) else if (modes.shuffle) plans.filterKeys { it == BEHIND_START } else emptyMap()
        return copy(plans = prepared + behind, future = pending)
    }

    companion object {
        /** Reserved plan key: no repeat cycle reaches it, and disabling shuffle drops it with every other shuffle plan. */
        internal const val BEHIND_START = Long.MIN_VALUE

        fun start(ids: List<String>, currentEntryId: String?, modes: JourneyModes,
            random: Random = Random.Default): QueueJourney = QueueJourney(modes = modes).reconcile(ids, currentEntryId, modes, random)
    }
}

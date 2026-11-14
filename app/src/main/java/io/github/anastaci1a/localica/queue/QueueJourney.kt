package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.mapView
import io.github.anastaci1a.localica.library.revision
import io.github.anastaci1a.localica.library.viewSource
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

/** Actual/explicit visits own history; unplayed visits are derived only for the native window. */
data class QueueJourney(
    val history: List<QueueVisit> = emptyList(),
    val cursor: Int = -1,
    val cycle: Long = 0,
    val visited: Set<String> = emptySet(),
    val plans: Map<Long, List<String>> = emptyMap(),
    val modes: JourneyModes = JourneyModes(),
    val orderedTraversalId: String? = null,
    val orderedAnchorToken: String? = null,
) {
    private data class Neighbor(val visit: QueueVisit, val ordinal: Int, val sourceOrdinal: Int = -1)
    private class Traversal(val ids: List<String>, val key: Any?, val oldestRank: Int,
        val resume: Map<Long, Int>, val window: List<Neighbor>) {
        val visits = window.mapView(Neighbor::visit)
    }
    // Copies and restored journeys start cold; only worker preparation installs a projection.
    private var traversal: Traversal? = null
    val current: QueueVisit? get() = history.getOrNull(cursor)
    val previous: QueueVisit? get() = window().let { it.getOrNull(it.indexOfFirst { v -> v.token == current?.token } - 1) }
    val next: QueueVisit? get() = window().let { it.getOrNull(it.indexOfFirst { v -> v.token == current?.token } + 1) }
    fun window(): List<QueueVisit> = traversal?.visits.orEmpty()

    internal fun sourceOrdinal(ids: List<String>, entryId: String?): Int? = traversal?.takeIf { it.ids.revision == ids.revision }
        ?.window?.firstOrNull { it.visit.entryId == entryId }?.sourceOrdinal?.takeIf { it >= 0 }

    /** Nearest visits first, shared with the player timeline. */
    fun artworkWindow(radius: Int = 4): List<QueueVisit> {
        require(radius in 0..4)
        val visits = window()
        val center = visits.indexOfFirst { it.token == current?.token }
        if (center < 0) return emptyList()
        return (1..radius).flatMap { distance ->
            listOfNotNull(visits.getOrNull(center + distance), visits.getOrNull(center - distance))
        }
    }

    private fun scheduledNext(): List<QueueVisit> = if (!modes.shuffle) emptyList() else
        history.drop(cursor + 1).filter { it.cycle == cycle && it.entryId !in visited }

    fun select(entryId: String, ids: List<String>, random: Random = Random.Default): QueueJourney {
        require(entryId in ids)
        if (modes.shuffle && visited.containsAll(ids)) return restart(ids, entryId, random)
        val visit = QueueVisit(entryId, cycle)
        val kept = history.take(cursor + 1) + visit
        return copy(history = kept + scheduledNext().filterNot { it.entryId == entryId }, cursor = kept.lastIndex, visited = visited + entryId,
            orderedTraversalId = if (modes.shuffle) orderedTraversalId else UUID.randomUUID().toString(),
            orderedAnchorToken = if (modes.shuffle) orderedAnchorToken else visit.token).prepare(ids, random, ids.toSet())
    }

    /** New requests reserve the active cycle, including while retracing older history. */
    fun playNext(added: List<String>, ids: List<String>, random: Random = Random.Default): QueueJourney {
        val valid = ids.toSet()
        require(added.toSet().size == added.size && valid.containsAll(added))
        val anchor = current ?: return reconcile(ids, ids.firstOrNull(), modes, random)
        if (!modes.shuffle) return reconcile(ids, anchor.entryId, modes, random)
        require(added.none { id -> history.any { it.entryId == id } || plans.values.any { id in it } })
        return copy(history = history.take(cursor + 1) + added.map { QueueVisit(it, cycle) } + history.drop(cursor + 1))
            .prepare(ids, random, valid)
    }

    /** A native receipt remains admissible when a new explicit batch displaced its window item. */
    fun arrive(token: String, ids: List<String>, random: Random = Random.Default, receipt: QueueVisit? = null): QueueJourney {
        if (token == current?.token) return this
        if (traversal == null) {
            val repaired = reconcile(ids, current?.entryId, modes, random)
            if (repaired.current == null) return this
            val arrived = repaired.arrive(token, ids, random, receipt)
            return if (arrived.current?.token == token) arrived else this
        }
        val exposed = traversal?.window?.firstOrNull { it.visit.token == token }
        if (!modes.shuffle) {
            val visit = exposed?.visit ?: return this
            val old = history.indexOfFirst { it.token == token }
            val recorded = if (old >= 0) history else history + visit
            val membership = if (visit.cycle == cycle) visited else
                recorded.filter { it.cycle == visit.cycle }.mapTo(mutableSetOf()) { it.entryId }
            return copy(history = recorded, cursor = if (old >= 0) old else recorded.lastIndex, cycle = visit.cycle,
                visited = membership + visit.entryId).prepare(ids, random, previous = traversal, rank = exposed.ordinal)
        }
        val old = history.indexOfFirst { it.token == token }
        if (old >= 0) {
            val visit = history[old]
            val reserved = scheduledNext().mapTo(hashSetOf()) { it.token }
            val skipped = if (old > cursor) history.subList(cursor + 1, old).filter { it.token in reserved } else emptyList()
            val skippedTokens = skipped.mapTo(hashSetOf()) { it.token }
            val prefix = history.take(old + 1).filterNot { it.token in skippedTokens }
            return copy(history = if (skipped.isEmpty()) history else prefix + skipped + history.drop(old + 1),
                cursor = prefix.lastIndex, visited = if (visit.cycle == cycle) visited + visit.entryId else visited)
                .prepare(ids, random, previous = traversal)
        }
        val visit = exposed?.visit ?: receipt?.takeIf { it.token == token && it.token == token(it.cycle, it.entryId) &&
            it.cycle in plans && it.entryId in plans.getValue(it.cycle) &&
            (it.cycle != cycle || it.entryId !in visited) && history.drop(cursor + 1).none { v -> v.cycle == it.cycle && v.entryId == it.entryId } }
            ?: return this
        if (exposed != null && exposed == traversal?.window?.firstOrNull() && orderedAnchorToken != null && cursor == 0) {
            return copy(history = listOf(visit) + history, cursor = 0,
                visited = if (visit.cycle == cycle) visited + visit.entryId else visited)
                .prepare(ids, random, previous = traversal, oldestRank = exposed.ordinal)
        }
        val kept = history.take(cursor + 1) + visit
        val scheduled = scheduledNext().filterNot { it.entryId == visit.entryId }
            .map { if (it.cycle == visit.cycle) it else it.copy(cycle = visit.cycle) }
        return copy(history = kept + scheduled, cursor = kept.lastIndex, cycle = visit.cycle,
            visited = (if (visit.cycle == cycle) visited else emptySet()) + visit.entryId)
            .prepare(ids, random, previous = traversal)
    }

    /** Repair is a worker operation; production ID views borrow the immutable entries revision. */
    fun reconcile(ids: List<String>, currentEntryId: String?, requested: JourneyModes,
        random: Random = Random.Default): QueueJourney {
        traversal?.let { if (it.key != null && it.key == ids.revision && current?.entryId == currentEntryId && modes == requested) return this }
        if (ids.isEmpty()) return QueueJourney(modes = requested)
        val order = if (ids.viewSource != null) ids else ids.toList()
        val valid = order.toSet()
        val selected = currentEntryId?.takeIf { it in valid } ?: order.first()
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
            val anchor = kept[position]
            return QueueJourney(history = listOf(anchor), cursor = 0, cycle = anchor.cycle,
                visited = setOf(anchor.entryId), modes = requested, orderedTraversalId = UUID.randomUUID().toString(),
                orderedAnchorToken = anchor.token).prepare(order, random, valid)
        }
        val changedCycles = changedShuffle || modes.reshuffle != requested.reshuffle
        val newTraversal = changedShuffle || requested.shuffle && changedCycles || orderedTraversalId == null ||
            !requested.shuffle && (newSelection || orderedAnchorToken == null)
        return copy(history = kept, cursor = position, cycle = activeCycle, visited = membership, modes = requested,
            plans = plans.filterKeys { !changedCycles || it == cycle && !changedShuffle },
            orderedTraversalId = if (newTraversal) UUID.randomUUID().toString() else orderedTraversalId,
            orderedAnchorToken = if (!requested.shuffle && newTraversal) kept[position].token else orderedAnchorToken)
            .prepare(order, random, valid)
    }

    fun restart(ids: List<String>, entryId: String, random: Random = Random.Default): QueueJourney {
        require(entryId in ids)
        check(cycle < Long.MAX_VALUE) { "Queue repeat cycle is exhausted" }
        val nextCycle = cycle + 1
        val visit = QueueVisit(entryId, nextCycle)
        val kept = history.take(cursor + 1) + visit
        val order = plans[nextCycle] ?: if (modes.shuffle && !modes.reshuffle) plans[cycle] else null
        return copy(history = kept, cursor = kept.lastIndex, cycle = nextCycle, visited = setOf(entryId),
            plans = order?.let { mapOf(nextCycle to it) }.orEmpty(), orderedTraversalId = UUID.randomUUID().toString(),
            orderedAnchorToken = if (modes.shuffle) orderedAnchorToken else visit.token).prepare(ids, random)
    }

    private fun prepare(input: List<String>, random: Random, valid: Set<String>? = null,
        previous: Traversal? = null, rank: Int? = null, oldestRank: Int? = null): QueueJourney {
        val ids = previous?.ids ?: if (input.viewSource != null) input else input.toList()
        val prepared = linkedMapOf<Long, List<String>>()
        val lastCycle = if (!modes.loop || !modes.shuffle) cycle else if (cycle <= Long.MAX_VALUE - 2) cycle + 2 else Long.MAX_VALUE
        for (number in cycle..lastCycle) {
            val retained = when {
                !modes.shuffle -> ids
                plans[number] != null -> plans.getValue(number).let { plan ->
                    if (valid == null || plan.all { it in valid }) plan else plan.filter { it in valid } }
                number > cycle && !modes.reshuffle -> prepared.getValue(number - 1)
                number == cycle -> {
                    val first = history.firstOrNull { it.cycle == number }?.entryId?.takeIf { it in ids }
                    listOfNotNull(first) + ids.filterNot { it == first }.shuffled(random)
                }
                else -> ids.shuffled(random)
            }
            val added = if (valid == null || plans[number] == null || !modes.shuffle) emptyList() else
                retained.toHashSet().let { kept -> ids.filterNot { it in kept }.shuffled(random) }
            val order = if (added.isEmpty()) retained else retained + added
            prepared[number] = if (valid != null && !modes.reshuffle)
                prepared.values.firstOrNull { it == order } ?: order else order
        }
        val result = copy(plans = prepared)
        val key = if (ids.viewSource != null && (valid == null || valid.size == ids.size &&
                (!modes.shuffle || plans.isEmpty() || prepared.values.all { it.toSet().size == it.size }))) ids.revision else null
        val selectedRank = rank ?: if (modes.shuffle) -1 else ids.indexOf(current?.entryId)
        val firstRank = oldestRank ?: previous?.oldestRank ?: ids.indexOf(history.firstOrNull()?.entryId)
        result.traversal = result.project(ids, key, selectedRank, firstRank, previous)
        return result
    }

    private fun project(ids: List<String>, key: Any?, rank: Int, oldestRank: Int, old: Traversal?): Traversal {
        val window = ArrayList<Neighbor>(129)
        val resume = linkedMapOf<Long, Int>()
        if (!modes.shuffle) {
            val anchor = history.firstOrNull { it.token == orderedAnchorToken }
            for (offset in -64..64) {
                val position = rank.toLong() + offset
                val shift = Math.floorDiv(position, ids.size.toLong())
                if (!modes.loop && shift != 0L || shift > 0 && cycle > Long.MAX_VALUE - shift ||
                    shift < 0 && cycle < Long.MIN_VALUE - shift) continue
                val number = cycle + shift
                val at = Math.floorMod(position, ids.size.toLong()).toInt()
                val entry = ids[at]
                val visit = if (offset == 0) current!! else if (anchor?.entryId == entry && anchor.cycle == number) anchor else
                    old?.window?.firstOrNull { it.ordinal == at && it.visit.cycle == number }?.visit ?: QueueVisit(entry, number, token(number, entry))
                window += Neighbor(visit, at, at)
            }
        } else {
            if (orderedAnchorToken != null && cursor < 64 && ids.size > 1 && (oldestRank > 0 || modes.loop)) {
                val at = Math.floorMod(oldestRank - 1, ids.size)
                val oldest = history.first()
                val entry = ids[at]
                val token = UUID.nameUUIDFromBytes("behind:${oldest.token.length}:${oldest.token}:$entry".toByteArray(Charsets.UTF_8)).toString()
                window += Neighbor(QueueVisit(entry, oldest.cycle, token), at, at)
            }
            val start = (cursor - 64).coerceAtLeast(0)
            history.subList(start, minOf(cursor + 65, history.size)).mapTo(window) { visit ->
                old?.window?.firstOrNull { it.visit.token == visit.token } ?: Neighbor(visit, -1) }
            val capacity = minOf(129, window.size + maxOf(0, cursor + 65 - history.size))
            val reserved = history.drop(cursor + 1).groupBy { it.cycle }.mapValues { (_, visits) -> visits.mapTo(hashSetOf()) { it.entryId } }
            for ((number, order) in plans) {
                var at = old?.resume?.get(number) ?: 0
                fun eligible(index: Int) = (number != cycle || order[index] !in visited) && order[index] !in reserved[number].orEmpty()
                while (at < order.size && !eligible(at)) at++
                resume[number] = at
                while (at < order.size && window.size < capacity) {
                    if (eligible(at)) {
                        val entry = order[at]
                        window += old?.window?.firstOrNull { it.ordinal == at && it.visit.cycle == number && it.visit.entryId == entry }
                            ?: Neighbor(QueueVisit(entry, number, token(number, entry)), at)
                    }
                    at++
                }
            }
        }
        val positions = window.filter { it.sourceOrdinal >= 0 }.associate { it.visit.entryId to it.sourceOrdinal }.toMutableMap()
        val missing = window.filter { it.sourceOrdinal < 0 && it.visit.entryId !in positions }.mapTo(hashSetOf()) { it.visit.entryId }
        if (missing.isNotEmpty()) for (index in ids.indices) {
            val entry = ids[index]
            if (missing.remove(entry)) positions[entry] = index
            if (missing.isEmpty()) break
        }
        val visits = window.map { if (it.sourceOrdinal >= 0) it else it.copy(sourceOrdinal = positions[it.visit.entryId] ?: -1) }
        return Traversal(ids, key, oldestRank, resume, visits)
    }

    private fun token(cycle: Long, entry: String) = UUID.nameUUIDFromBytes(
        "${orderedTraversalId!!.length}:$orderedTraversalId:$cycle:${entry.length}:$entry".toByteArray(Charsets.UTF_8)).toString()

    companion object {
        fun start(ids: List<String>, currentEntryId: String?, modes: JourneyModes,
            random: Random = Random.Default): QueueJourney = QueueJourney(modes = modes).reconcile(ids, currentEntryId, modes, random)
    }
}

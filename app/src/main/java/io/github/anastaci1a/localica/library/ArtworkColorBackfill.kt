package io.github.anastaci1a.localica.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** A track whose artwork colour is absent or stale, with one readable location. */
data class ArtworkSource(val trackId: String, val modifiedAt: Long?, val uri: String)

/** Persistence used by [ArtworkColorBackfill]; the Room DAO in production. */
interface ArtworkColorStore {
    suspend fun pending(limit: Int): List<ArtworkSource>
    suspend fun sources(uris: List<String>): List<ArtworkSource>
    /** Rows for tracks removed meanwhile are skipped. [ArtworkColors.MISSING] stores "no artwork". */
    suspend fun save(colors: List<Pair<ArtworkSource, Int>>)
}

/**
 * One single-flight worker for scan-time computation and the post-upgrade backfill.
 *
 * Visible requests (latest first) and colours offered by display decodes run ahead of the
 * bulk pass and ignore foreground decodes. Bulk items wait until no foreground decode is in
 * flight. [paused] (scan running or app stopped) cancels the current pass; work resumes later.
 * All work runs on the caller's dispatcher (IO in production), never on Main.
 */
class ArtworkColorBackfill(
    private val store: ArtworkColorStore,
    private val compute: suspend (String) -> Int,
    private val foregroundDecodes: StateFlow<Int>,
    private val paused: Flow<Boolean>,
    private val batch: Int = 50,
    private val priorityCapacity: Int = 48,
    /** Timing evidence for visible-first work; Log.i in production. */
    private val log: (String) -> Unit = {},
) : ArtworkColorRequests {
    private val lock = Any()
    private val priority = LinkedHashSet<String>()
    private val offered = LinkedHashMap<String, Int>()
    private val wakes = MutableStateFlow(0L)
    private val requestedAt = HashMap<String, Long>()
    @Volatile var computed = 0
        private set

    /** Library contents changed (scan, merge, removal or tag edit): look for new or stale rows. */
    fun request() { wakes.value++ }

    override fun prioritize(uris: List<String>) {
        if (uris.isEmpty()) return
        synchronized(lock) {
            val now = System.nanoTime()
            uris.forEach { priority.remove(it); priority.add(it); requestedAt.putIfAbsent(it, now) }
            // Latest visible wins; the oldest requests are stale once the user moved on.
            while (priority.size > priorityCapacity) priority.first().let { priority.remove(it); requestedAt.remove(it) }
        }
        wakes.value++
    }

    override fun withdraw(uris: List<String>) { synchronized(lock) { priority.removeAll(uris.toSet()); uris.forEach(requestedAt::remove) } }

    override fun offer(uri: String, color: Int) {
        synchronized(lock) { offered[uri] = color; priority.remove(uri); requestedAt.putIfAbsent(uri, System.nanoTime()) }
        wakes.value++
    }

    suspend fun run() {
        paused.collectLatest { stopped ->
            if (stopped) return@collectLatest
            var seen = -1L
            while (true) {
                val wake = wakes.value
                if (wake == seen) { wakes.first { it != seen }; continue }
                seen = wake
                pass()
            }
        }
    }

    private fun hasUrgent() = synchronized(lock) { priority.isNotEmpty() || offered.isNotEmpty() }

    private fun takeUrgent(): Pair<Map<String, Int>, List<String>> = synchronized(lock) {
        val offers = LinkedHashMap(offered).also { offered.clear() }
        val visible = priority.toList().asReversed().take(8).also { priority.removeAll(it.toSet()) }
        offers to visible
    }

    private suspend fun urgent(): Boolean {
        val (offers, visible) = takeUrgent()
        if (offers.isEmpty() && visible.isEmpty()) return false
        if (offers.isNotEmpty()) {
            val sources = store.sources(offers.keys.toList())
            store.save(sources.mapNotNull { source -> offers[source.uri]?.let { source to it } })
        }
        if (visible.isNotEmpty()) {
            val sources = store.sources(visible).distinctBy { it.trackId }.sortedBy { visible.indexOf(it.uri) }
            val results = ArrayList<Pair<ArtworkSource, Int>>()
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                results += source to compute(source.uri)
                computed++
            }
            store.save(results)
        }
        val waited = synchronized(lock) { (offers.keys + visible).mapNotNull { requestedAt.remove(it) } }
        if (waited.isNotEmpty()) log("visible colours stored: ${offers.size} from display decodes, ${visible.size} computed; " +
            "longest request-to-store ${(System.nanoTime() - waited.min()) / 1_000_000} ms")
        return true
    }

    private suspend fun pass() {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (urgent()) continue
            val pending = store.pending(batch)
            if (pending.isEmpty()) { log("backfill idle: $computed colours computed in this process"); return }
            val results = ArrayList<Pair<ArtworkSource, Int>>()
            try {
                for (source in pending) {
                    if (hasUrgent()) break
                    // Wait out foreground decodes, but wake at once for a visible request.
                    combine(foregroundDecodes, wakes) { decoding, _ -> decoding == 0 || hasUrgent() }.first { it }
                    if (hasUrgent()) break
                    currentCoroutineContext().ensureActive()
                    val color = compute(source.uri)
                    results += source to color
                    computed++
                }
            } finally {
                // Keep finished work even when a pause or visible request interrupts the batch.
                if (results.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { store.save(results) }
            }
        }
    }
}

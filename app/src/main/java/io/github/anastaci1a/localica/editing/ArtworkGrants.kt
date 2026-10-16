package io.github.anastaci1a.localica.editing

/** Only grants acquired by this owner may be released. Calls and leases share one lock. */
internal class ArtworkGrants(
    private val loadOwned: () -> Set<String>,
    private val saveOwned: (Set<String>) -> Boolean,
    private val retained: () -> Set<String>,
    private val alreadyGranted: (String) -> Boolean,
    private val eligible: (String) -> Boolean,
    private val acquire: (String) -> Unit,
    private val release: (String) -> Unit,
    private val canAcquire: () -> Boolean = { true },
) {
    private val readers = mutableMapOf<String, Int>()
    // An unreadable ownership record must never authorize pruning or new persisted grants.
    private var owned: Set<String>? = runCatching(loadOwned).getOrNull()

    @Synchronized fun selected(uri: String) {
        prune()
        val current = owned ?: return
        if (!eligible(uri) || runCatching { alreadyGranted(uri) }.getOrDefault(true)) return
        if (!runCatching(canAcquire).getOrDefault(false)) return
        // Check storage before taking a new grant. Only completed acquisition establishes ownership.
        if (!runCatching { saveOwned(current) }.getOrDefault(false)) return
        if (runCatching { acquire(uri) }.isFailure) return
        val next = current + uri
        owned = next
        if (!runCatching { saveOwned(next) }.getOrDefault(false)) {
            // This process knows the grant was freshly acquired. After a crash, an unrecorded
            // grant is deliberately unknown, never inferred to be ours from a matching URI.
            if (runCatching { release(uri) }.isSuccess) owned = current
        }
    }

    @Synchronized fun retain(uri: String): AutoCloseable {
        readers[uri] = readers.getOrDefault(uri, 0) + 1
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val count = readers.getValue(uri) - 1
                    if (count == 0) readers.remove(uri) else readers[uri] = count
                    prune()
                }
            }
        }
    }

    @Synchronized fun prune() {
        val current = owned ?: return
        val keep = runCatching(retained).getOrNull() ?: return
        val obsolete = current - keep - readers.keys
        val removed = obsolete.filter { uri ->
            // Defense in depth: never release a tree, even if a damaged ledger names it.
            eligible(uri) && runCatching { release(uri) }.isSuccess
        }.toSet()
        if (removed.isNotEmpty()) {
            val next = current - removed
            // A failed commit keeps retryable ownership, never loses a live grant.
            if (runCatching { saveOwned(next) }.getOrDefault(false)) owned = next
        }
    }
}

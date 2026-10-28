package io.github.anastaci1a.localica.scanner

internal enum class ScanMode { NewFiles, Full }
internal data class ScanRequest(val root: String, val mode: ScanMode)

/** Main-thread owned: full work supersedes queued discovery, never the other way around. */
internal class ScanRequests {
    private val pending = linkedMapOf<String, ScanMode>()
    var current: ScanRequest? = null
        private set
    val size get() = pending.size + if (current == null) 0 else 1

    fun add(roots: List<String>, mode: ScanMode) {
        roots.forEach { root ->
            val active = current?.takeIf { it.root == root }
            if (active != null && (active.mode == ScanMode.Full || mode == ScanMode.NewFiles)) return@forEach
            if (pending[root] != ScanMode.Full) pending[root] = mode
        }
    }
    fun take(): ScanRequest? {
        check(current == null)
        val next = pending.entries.firstOrNull() ?: return null
        return ScanRequest(next.key, next.value).also { pending.remove(next.key); current = it }
    }
    fun complete() { current = null }
    fun clear() { pending.clear(); current = null }
}

package io.github.anastaci1a.localica.statistics

/** One traversal of a queue occurrence. Only elapsed, actually-playing wall time qualifies. */
data class ListeningSource(val occurrenceId: String, val trackId: String, val playlistId: String? = null)

class ListeningCounter(private val qualified: (ListeningSource) -> Unit) {
    private var source: ListeningSource? = null
    private var durationMs: Long? = null
    private var elapsedMs = 0L
    private var lastAt = 0L
    private var playing = false
    private var counted = false

    fun sample(now: Long) {
        if (playing) elapsedMs += (now - lastAt).coerceAtLeast(0)
        lastAt = now
        val threshold = durationMs?.takeIf { it > 0 }?.let { minOf(30_000L, (it + 1) / 2) } ?: 30_000L
        if (!counted && source != null && elapsedMs >= threshold) {
            counted = true
            qualified(requireNotNull(source))
        }
    }

    fun update(now: Long, next: ListeningSource?, duration: Long?, isPlaying: Boolean, restart: Boolean = false) {
        sample(now)
        if (next != source || restart) { source = next; elapsedMs = 0; counted = false }
        durationMs = duration?.takeIf { it > 0 }
        playing = isPlaying && next != null
        lastAt = now
        // A previously unknown duration can become known without any new elapsed time.
        sample(now)
    }
}

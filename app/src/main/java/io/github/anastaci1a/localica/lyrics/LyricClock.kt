package io.github.anastaci1a.localica.lyrics

/** Interpolate the player's latency-aware clock, never a second Bluetooth estimate. */
internal fun lyricPosition(positionMs: Long, sampledAtMs: Long, nowMs: Long, playing: Boolean,
    speed: Float, delayMs: Int, durationMs: Long? = null): Long {
    val elapsed = if (playing && sampledAtMs > 0) (nowMs - sampledAtMs).coerceIn(0, 500) else 0
    val rate = if (speed.isFinite()) speed.coerceIn(0f, 8f) else 1f
    val advanced = positionMs + (elapsed * rate).toLong()
    return (minOf(advanced, durationMs ?: Long.MAX_VALUE) - delayMs.coerceIn(-2000, 2000)).coerceAtLeast(0)
}

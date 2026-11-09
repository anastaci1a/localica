package io.github.anastaci1a.localica.lastfm

/** The archive is immutable. Only qualified local plays committed after its baseline are temporary additions. */
data class LastFmOverlay(val counts: Map<String, Long>, val additions: Long, val sourceSnapshot: LastFmSnapshot? = null)

internal fun LastFmMatches.withLocalOverlay(snapshot: LastFmSnapshot?, local: Map<String, Long>): LastFmOverlay {
    val baseline = snapshot?.localBaseline ?: return LastFmOverlay(counts, 0, snapshot)
    val groups = counts.keys.groupBy { recordingGroups[it] ?: it }
    val deltas = groups.mapValues { (_, ids) -> ids.fold(0L) { total, id ->
        val current = local[id]?.coerceAtLeast(0) ?: 0
        val before = baseline[id]?.coerceAtLeast(0) ?: 0
        addPlays(total, if (current >= before) current - before else 0)
    } }
    return LastFmOverlay(counts.mapValues { (id, count) -> addPlays(count, deltas[recordingGroups[id] ?: id] ?: 0) },
        local.entries.fold(0L) { total, (id, count) ->
            val current = count.coerceAtLeast(0); val before = baseline[id]?.coerceAtLeast(0) ?: 0
            addPlays(total, if (current >= before) current - before else 0)
        }, snapshot)
}

internal fun addPlays(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

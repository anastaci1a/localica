package io.github.anastaci1a.localica.lastfm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

internal data class LastFmFetchedArchive(val tracks: List<LastFmTrack>, val through: Long,
    val localBaseline: Map<String, Long>, val historyComplete: Boolean, val stagingId: String? = null)

internal class LastFmHistorySync(private val store: LastFmHistoryStore) {
    suspend fun fetch(username: String, previous: LastFmSnapshot?, fullRescan: Boolean, through: Long, baseline: Map<String, Long>,
        progress: suspend (LastFmProgress) -> Unit, request: suspend (Int, Long, Long) -> LastFmRecentPage): LastFmFetchedArchive {
        val boundary = previous?.refreshBoundarySeconds
        val full = fullRescan || previous?.historyComplete != true || boundary == null || boundary > through
        val from = if (full) 0 else boundary!!
        if (!full && through == from) return LastFmFetchedArchive(previous!!.tracks, from, previous.localBaseline ?: baseline, true)
        val source = digest(previous?.let { snapshot ->
            "${normalize(snapshot.username)}:${snapshot.refreshBoundarySeconds}:" + snapshot.tracks.sortedBy { it.key }
                .joinToString("") { "${it.key.length}:${it.key}:${it.plays};" }
        }.orEmpty())
        var checkpoint = store.read()?.takeIf {
            it.username.equals(username, true) && it.full == full && it.from == from && it.through <= through && it.sourceFingerprint == source
        }
        if (checkpoint == null) {
            checkpoint = LastFmHistoryCheckpoint(UUID.randomUUID().toString(), username, from, through, full, source, baseline.toMap(),
                tracks = if (full) emptyList() else previous!!.tracks)
            store.begin(checkpoint)
        } else if (checkpoint.nextPage > 1) {
            val last = request(checkpoint.nextPage - 1, (checkpoint.from - 1).coerceAtLeast(0), checkpoint.through)
            if (last.number != checkpoint.nextPage - 1 || last.totalPages != checkpoint.totalPages ||
                historyFingerprint(last.tracks) != checkpoint.lastFingerprint) {
                store.clear(checkpoint.id)
                throw LastFmFailure("Last.fm history changed while sync was paused. Start sync again. Your archive is kept.")
            }
        }
        var current = requireNotNull(checkpoint)
        val collected = current.tracks.associateByTo(linkedMapOf()) { historyKey(it.artist, it.title, it.album) }
        progress(status(current))
        while (current.nextPage <= current.totalPages) {
            val response = request(current.nextPage, (current.from - 1).coerceAtLeast(0), current.through)
            if (response.number != current.nextPage || response.totalPages !in 1..10_000 || response.totalPages < response.number ||
                current.nextPage > 1 && response.totalPages != current.totalPages || response.tracks.isEmpty() && response.number < response.totalPages ||
                response.tracks.any { it.timestampSeconds < current.from || it.timestampSeconds >= current.through } ||
                current.lastTimestamp?.let { last -> response.tracks.firstOrNull()?.timestampSeconds?.let { it > last } } == true ||
                response.tracks.zipWithNext().any { (a, b) -> a.timestampSeconds < b.timestampSeconds })
                throw LastFmFailure("Last.fm returned inconsistent history pages. Your previous archive is kept.")
            aggregateHistory(collected, response.tracks)
            val next = current.copy(nextPage = current.nextPage + 1, totalPages = response.totalPages,
                tracks = collected.values.toList(), lastFingerprint = historyFingerprint(response.tracks),
                lastTimestamp = response.tracks.lastOrNull()?.timestampSeconds ?: current.lastTimestamp)
            currentCoroutineContext().ensureActive()
            store.append(current, next, response)
            current = next
            progress(status(current))
        }
        return LastFmFetchedArchive(current.tracks, current.through, current.localBaseline, true, current.id)
    }

    private fun status(checkpoint: LastFmHistoryCheckpoint) = LastFmProgress(checkpoint.nextPage - 1, checkpoint.totalPages,
        checkpoint.tracks.map { normalize(it.artist) to normalize(it.title) }.distinct().size,
        checkpoint.tracks.map { normalize(it.artist) }.distinct().size,
        checkpoint.tracks.filter { !it.album.isNullOrBlank() }.map { normalize(it.artist) to normalize(it.album) }.distinct().size)
}

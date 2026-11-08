package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import java.text.Normalizer
import java.util.Locale

/** The remote snapshot is never a replacement for local listening events. */
data class LastFmTrack(val artist: String, val title: String, val plays: Long, val album: String? = null, val mbid: String? = null) {
    val key: String get() = listOf(normalize(artist), normalize(title), normalize(album), normalize(mbid))
        .joinToString("") { "${it.length}:$it" }
}
data class LastFmSnapshot(val username: String, val syncedAt: Long, val tracks: List<LastFmTrack>)
data class SavedLastFm(val username: String = "", val useCounts: Boolean = false,
    val snapshot: LastFmSnapshot? = null, val mappings: Map<String, String> = emptyMap(), val attemptedAt: Long? = null)
data class LastFmState(val saved: SavedLastFm = SavedLastFm(), val loading: Boolean = true,
    val syncing: Boolean = false, val problem: String? = null)
data class LastFmReview(val remote: LastFmTrack, val candidates: List<Track>)
data class LastFmMatches(val counts: Map<String, Long>, val review: List<LastFmReview>)

internal fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

/** Conservative whole-credit matching; editions with identical artist/title stay ambiguous. */
object LastFmMatcher {
    fun match(snapshot: LastFmSnapshot?, tracks: List<Track>, mappings: Map<String, String> = emptyMap()): LastFmMatches {
        if (snapshot == null) return LastFmMatches(emptyMap(), emptyList())
        val unique = tracks.distinctBy { it.id }
        val byId = unique.associateBy { it.id }
        val byName = unique.groupBy { normalize(it.artist?.takeIf(String::isNotBlank) ?: it.albumArtist) to normalize(it.title) }
        val byMbid = unique.filter { !it.musicBrainzId.isNullOrBlank() }.groupBy { normalize(it.musicBrainzId) }
        val counts = mutableMapOf<String, Long>(); val review = mutableListOf<LastFmReview>()
        for (remote in snapshot.tracks) {
            var candidates = if (!remote.mbid.isNullOrBlank() && byMbid.containsKey(normalize(remote.mbid)))
                byMbid.getValue(normalize(remote.mbid)) else byName[normalize(remote.artist) to normalize(remote.title)].orEmpty()
            if (!remote.mbid.isNullOrBlank()) candidates = candidates.filter {
                it.musicBrainzId.isNullOrBlank() || normalize(it.musicBrainzId) == normalize(remote.mbid) }
            if (candidates.size > 1 && !remote.album.isNullOrBlank()) {
                val sameAlbum = candidates.filter { normalize(it.album) == normalize(remote.album) }
                if (sameAlbum.isNotEmpty()) candidates = sameAlbum
            }
            val chosen = mappings[remote.key]?.let(byId::get) ?: candidates.singleOrNull()
            if (chosen == null) review += LastFmReview(remote, candidates)
            else {
                val previous = counts[chosen.id] ?: 0; val plays = remote.plays.coerceAtLeast(0)
                counts[chosen.id] = if (previous > Long.MAX_VALUE - plays) Long.MAX_VALUE else previous + plays
            }
        }
        return LastFmMatches(counts, review)
    }
}

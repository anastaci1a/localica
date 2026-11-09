package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class LastFmCandidateScore(val trackId: String, val score: Int, val tier: LastFmMatchTier, val reason: String)

/** Explicit metadata allowlist: never serialize account state, transport data, URIs or file paths. */
internal object LastFmMatchingReport {
    fun json(snapshot: LastFmSnapshot?, library: List<Track>, mappings: Map<String, String>): String {
        val engine = LastFmMatchingEngine(library)
        val reviews = snapshot?.let { engine.match(it, mappings).review }.orEmpty()
        return JSONObject().apply {
            put("format", "localica.lastfm-matching"); put("version", 1)
            put("libraryTracks", library.size); put("reviewCount", reviews.size)
            put("review", JSONArray().apply { reviews.forEach { review -> put(JSONObject().apply {
                put("remote", JSONObject().apply {
                    put("artist", review.remote.artist); put("title", review.remote.title)
                    put("album", review.remote.album); put("plays", review.remote.plays)
                    put("durationMs", review.remote.durationMs); put("recordingId", review.remote.mbid)
                })
                put("reason", review.reason); put("explicitlyUndone", mappings[review.remote.key] == LastFmMatcher.REVIEW)
                val scores = engine.inspect(review.remote).associateBy { it.trackId }
                put("candidateCount", review.candidates.size)
                put("candidates", JSONArray().apply { review.candidates.forEach { candidate -> put(JSONObject().apply {
                    val names = LastFmNames.name(candidate.artist ?: candidate.albumArtist, candidate.title)
                    put("id", MessageDigest.getInstance("SHA-256").digest(candidate.id.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) })
                    put("title", candidate.title); put("artist", candidate.artist); put("albumArtist", candidate.albumArtist)
                    put("album", candidate.album); put("durationMs", candidate.durationMs); put("recordingId", candidate.musicBrainzId)
                    put("normalizedArtist", names.artist); put("normalizedTitle", names.title)
                    put("normalizedAlbum", LastFmNames.album(candidate.album))
                    scores[candidate.id]?.let { scored ->
                        put("score", scored.score); put("tier", scored.tier.name); put("reason", scored.reason)
                    }
                }) } })
            }) } })
        }.toString(2)
    }
}

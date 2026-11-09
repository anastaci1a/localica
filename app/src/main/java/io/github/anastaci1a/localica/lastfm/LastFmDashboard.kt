package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track

internal enum class LastFmGroup(val label: String) {
    MATCHED("Matched"), JUDGMENTS("Judgment calls"), REVIEW("Needs review"),
    EDITIONS("Auto-matched editions"), ABSENT("Not in your library")
}
internal data class LastFmMatchRow(val remote: LastFmTrack, val candidates: List<Track>, val reason: String,
    val selectedId: String? = null, val canUndo: Boolean = false)

internal fun LastFmMatches.rows(group: LastFmGroup, mappings: Map<String, String> = emptyMap()): List<LastFmMatchRow> = when (group) {
    LastFmGroup.MATCHED -> resolutions.filter { it.tier == LastFmMatchTier.CLEAR }.map { LastFmMatchRow(it.remote, it.tracks, it.reason) }
    LastFmGroup.JUDGMENTS -> judgments.map { LastFmMatchRow(it.remote, it.tracks, it.reason) }
    LastFmGroup.REVIEW -> review.map { LastFmMatchRow(it.remote, it.candidates, it.reason) }
    LastFmGroup.EDITIONS -> editions.map { LastFmMatchRow(it.remote, it.candidates, it.reason) }
    LastFmGroup.ABSENT -> unmatched.map { LastFmMatchRow(it, emptyList(), "No local match. Choose any song in your library.") }
}.map { row ->
    val chosen = mappings[row.remote.key]?.takeUnless { it == LastFmMatcher.REVIEW }
    row.copy(selectedId = chosen, canUndo = group == LastFmGroup.JUDGMENTS || group == LastFmGroup.ABSENT && chosen == LastFmMatcher.NONE,
        reason = if (group == LastFmGroup.ABSENT && chosen == LastFmMatcher.NONE) "Marked not in your library by you" else row.reason)
}.sortedByDescending { it.remote.plays }

internal fun LastFmMatchRow.contains(query: String): Boolean {
    val tokens = LastFmNames.text(query).split(' ').filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val text = LastFmNames.text(listOf(remote.artist, remote.title, remote.album, reason).joinToString(" ") + " " +
        candidates.joinToString(" ") { "${it.title} ${it.artist.orEmpty()} ${it.album.orEmpty()} ${it.fileName}" })
    return tokens.all { it in text }
}
internal fun libraryMatches(tracks: List<Track>, query: String): List<Track> {
    val tokens = LastFmNames.text(query).split(' ').filter(String::isNotBlank)
    return (if (tokens.isEmpty()) tracks else tracks.filter { track ->
        val text = LastFmNames.text("${track.title} ${track.artist.orEmpty()} ${track.album.orEmpty()} ${track.fileName}")
        tokens.all { it in text }
    }).sortedWith(compareBy<Track> { it.title.lowercase() }.thenBy { it.album }.thenBy { it.id })
}

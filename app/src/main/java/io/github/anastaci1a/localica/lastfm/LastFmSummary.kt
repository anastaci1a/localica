package io.github.anastaci1a.localica.lastfm

/** Progress is ephemeral. Only completed, validated pages contribute; it is never a saved snapshot. */
data class LastFmProgress(val page: Int = 0, val totalPages: Int = 0, val songs: Int = 0) {
    val percent: Int get() = if (totalPages == 0) 0 else (100L * page / totalPages).toInt().coerceIn(0, 100)
    val label: String get() = if (totalPages == 0) "Connecting to Last.fm…" else
        "Page $page of $totalPages · $percent% · $songs songs fetched"
}

data class LastFmAccountStats(val entries: Int, val scrobbles: Long, val songs: Int, val artists: Int,
    val albums: Int, val entriesWithAlbums: Int)

internal fun LastFmSnapshot.accountStats(): LastFmAccountStats {
    var scrobbles = 0L
    tracks.forEach { val plays = it.plays.coerceAtLeast(0)
        scrobbles = if (scrobbles > Long.MAX_VALUE - plays) Long.MAX_VALUE else scrobbles + plays }
    return LastFmAccountStats(tracks.size, scrobbles,
        tracks.map { LastFmNames.name(it.artist, it.title) }.distinct().size,
        tracks.map { LastFmNames.name(it.artist, "").artist }.distinct().size,
        tracks.filter { !it.album.isNullOrBlank() }.map { LastFmNames.name(it.artist, "").artist to LastFmNames.album(it.album) }.distinct().size,
        tracks.count { !it.album.isNullOrBlank() })
}

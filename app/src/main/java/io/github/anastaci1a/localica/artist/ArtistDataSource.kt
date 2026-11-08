package io.github.anastaci1a.localica.artist

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.time.ZonedDateTime
import java.util.Locale

/** Exact whole-tag matching, like the existing library. Featured-artist splitting is deferred. */
data class ArtistScope(val name: String, val albumArtist: Boolean = false) {
    fun contains(track: Track): Boolean = normalized(if (albumArtist) track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
        else track.artist) == normalized(name)
}

data class ArtistRelease(val album: LibraryGroup, val classification: ReleaseClassification, val year: Int?, val addedAt: Long,
    val longestListenedMs: Long)
data class RankedSong(val track: Track, val plays: Long, val longestListenedMs: Long, val origin: PlayCountOrigin)
data class ArtistPlaylist(val playlist: ManualPlaylist, val matchingSongs: Int)
data class ArtistPageData(val releases: List<ArtistRelease>, val latestRelease: ArtistRelease?,
    val topSongs: List<RankedSong>, val usesFallbackOrder: Boolean, val playlists: List<ArtistPlaylist>,
    val appearsOn: List<LibraryGroup>, val similarArtists: List<String>, val longestListenedMs: Long)

/** Enrichment deliberately stays empty until credit splitting and online matching are implemented. */
interface ArtistEnrichmentSource {
    fun appearsOn(artist: ArtistScope): List<LibraryGroup>
    fun similarArtists(artist: ArtistScope): List<String>
}
object EmptyArtistEnrichment : ArtistEnrichmentSource {
    override fun appearsOn(artist: ArtistScope): List<LibraryGroup> = emptyList()
    override fun similarArtists(artist: ArtistScope): List<String> = emptyList()
}

class ArtistDataSource(private val enrichment: ArtistEnrichmentSource = EmptyArtistEnrichment) {
    /** Caller refreshes [now] on resume/day changes, retaining a single consistent clock per result. */
    fun observe(artist: ArtistScope, library: Flow<LibrarySnapshot>, playlists: Flow<List<ManualPlaylist>>,
        rankings: PlayRankingSource, settings: Flow<RankingSettings>, range: ListeningRange,
        now: ZonedDateTime): Flow<ArtistPageData> = combine(library, playlists, rankings.counts(range.window(now), settings)) {
            snapshot, lists, counts -> load(artist, snapshot, lists, counts, now.year)
        }.flowOn(Dispatchers.Default)

    fun load(artist: ArtistScope, library: LibrarySnapshot, playlists: List<ManualPlaylist>, counts: RankingCounts,
        currentYear: Int, limit: Int = 5): ArtistPageData {
        require(limit >= 0)
        val songs = library.tracks.filter(artist::contains).distinctBy { it.id }
        val ids = songs.mapTo(mutableSetOf()) { it.id }
        val releases = LibraryViews.groups(library, SongListView.Albums, LibraryQuery())
            .filter { album -> album.tracks.any { it.id in ids } }
            .map { album -> ArtistRelease(album, ReleaseClassifier.classify(album), album.tracks.mapNotNull { it.year }.minOrNull(),
                album.tracks.mapNotNull { it.addedAt }.maxOrNull() ?: Long.MIN_VALUE, counts.longestListenedMs(album.tracks)) }
            .sortedWith(compareByDescending<ArtistRelease> { it.year ?: Int.MIN_VALUE }.thenByDescending { it.addedAt }
                .thenBy { normalized(it.album.title) }.thenBy { it.album.id })
        val fallback = releases.flatMap { release -> release.album.tracks.filter { it.id in ids }.sortedWith(
            compareBy<Track> { it.discNumber ?: 1 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy { normalized(it.title) }.thenBy { it.id }) }.distinctBy { it.id }
        val hasPlays = songs.any { counts.plays(it.id) > 0 }
        val fallbackIndex = fallback.mapIndexed { index, track -> track.id to index }.toMap()
        val top = if (hasPlays) songs.sortedWith(compareByDescending<Track> { counts.plays(it.id) }
            .thenBy { fallbackIndex[it.id] ?: Int.MAX_VALUE }.thenBy { it.id }) else fallback
        val inPlaylists = playlists.mapNotNull { playlist ->
            val matches = playlist.entries.map { library.tracksById[it.track.id] ?: it.track }.filter(artist::contains)
                .distinctBy { it.id }.size
            if (matches == 0) null else ArtistPlaylist(playlist, matches)
        }.sortedWith(compareByDescending<ArtistPlaylist> { it.matchingSongs }.thenBy { normalized(it.playlist.name) }.thenBy { it.playlist.id })
        return ArtistPageData(releases, releases.firstOrNull { it.year == currentYear }, top.take(limit).map {
            RankedSong(it, counts.plays(it.id), counts.longestListenedMs(it), counts.origin(it.id))
        }, !hasPlays, inPlaylists, enrichment.appearsOn(artist), enrichment.similarArtists(artist), counts.longestListenedMs(songs))
    }
}

private fun normalized(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

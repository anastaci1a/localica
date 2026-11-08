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
    val snapshot: LastFmSnapshot? = null, val mappings: Map<String, String> = emptyMap(), val attemptedAt: Long? = null,
    val keepSyncingInBackground: Boolean = true, val activeAttempt: String? = null)
data class LastFmState(val saved: SavedLastFm = SavedLastFm(), val loading: Boolean = true,
    val syncing: Boolean = false, val problem: String? = null, val progress: LastFmProgress? = null)
data class LastFmReview(val remote: LastFmTrack, val candidates: List<Track>)
data class LastFmMatches(val counts: Map<String, Long>, val review: List<LastFmReview>,
    val notInLibrary: Int = 0, val editions: List<LastFmReview> = emptyList(), val matched: Int = 0, val dismissed: Int = 0)

internal fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

/** Matching normalization is separate from persisted remote keys, so existing choices remain valid. */
internal object LastFmNames {
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val feature = Regex("(?i)\\b(?:feat\\.?|ft\\.?|featuring)\\s+")
    private val edition = "(?:(?:\\d{4}\\s+)?(?:re-?master(?:ed)?)(?:\\s+\\d{4})?|(?:super\\s+)?deluxe(?:\\s+edition)?|expanded(?:\\s+edition)?|special\\s+edition|anniversary(?:\\s+edition)?|\\d+(?:st|nd|rd|th)\\s+anniversary(?:\\s+edition)?)"
    private val editionSuffix = Regex("(?i)(?:\\s*[-–—]\\s*|\\s*[\\[(])$edition[\\])]?$|\\s+$edition$")
    private val fileExtension = Regex("(?i)\\.(?:mp3|m4a|flac|wav|ogg|opus|aac)$")
    private val trackNumber = Regex("^\\s*\\d{1,3}[. _-]+(?=\\p{L})")
    private val collaboration = Regex("(?i)\\s+(?:and|x)\\s+|\\s*&\\s*")
    data class Name(val artist: String, val title: String, val featured: String)
    fun text(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT).replace(Regex("['’'.]"), "")
        .replace("&", " and ").replace(punctuation, " ").trim().replace(spaces, " ")
    private fun credit(value: String): String = value.split(collaboration).map(::text).filter(String::isNotBlank).sorted().joinToString(" & ")
    fun album(value: String?): String = text(stripEdition(value.orEmpty()))
    private fun stripEdition(value: String): String {
        var result = value.trim()
        while (true) {
            val next = result.replace(editionSuffix, "").trim()
            if (next == result) return result
            result = next
        }
    }
    fun name(artist: String?, title: String): Name {
        val artists = artist.orEmpty().split(feature, limit = 2)
        val rawTitle = stripEdition(title.replace(fileExtension, "").replace(trackNumber, ""))
        val titles = rawTitle.split(feature, limit = 2)
        val guests = listOfNotNull(artists.getOrNull(1), titles.getOrNull(1))
            .map { it.trim().trimEnd(')', ']') }.filter(String::isNotBlank)
        val mainTitle = titles.first().trim().trimEnd('(', '[').trim()
        // Live, remix, acoustic, intro and part labels remain part of the identity.
        return Name(credit(artists.first()), text(stripEdition(mainTitle)), guests.map(::credit).distinct().sorted().joinToString(" & "))
    }
}

/** Exact normalized identities, recording IDs and album edition families; no edit-distance guesses. */
object LastFmMatcher {
    const val NONE = "" // Explicit rejection in the existing mappings store; never a track ID.
    fun match(snapshot: LastFmSnapshot?, tracks: List<Track>, mappings: Map<String, String> = emptyMap()): LastFmMatches {
        if (snapshot == null) return LastFmMatches(emptyMap(), emptyList())
        val unique = tracks.distinctBy { it.id }
        val byId = unique.associateBy { it.id }
        val names = unique.associate { it.id to LastFmNames.name(it.artist?.takeIf(String::isNotBlank) ?: it.albumArtist, it.title) }
        val byName = unique.groupBy { names.getValue(it.id) }
        val byMbid = unique.filter { !it.musicBrainzId.isNullOrBlank() }.groupBy { normalize(it.musicBrainzId) }
        val counts = mutableMapOf<String, Long>(); val review = mutableListOf<LastFmReview>()
        val editions = mutableListOf<LastFmReview>()
        var absent = 0; var matched = 0; var dismissed = 0
        for (remote in snapshot.tracks) {
            if (mappings[remote.key] == NONE) { dismissed++; continue }
            val strong = remote.mbid?.takeIf(String::isNotBlank)?.let { byMbid[normalize(it)] }.orEmpty()
            var candidates = strong.ifEmpty { byName[LastFmNames.name(remote.artist, remote.title)].orEmpty() }
            val plausible = candidates
            if (!remote.mbid.isNullOrBlank()) candidates = candidates.filter {
                it.musicBrainzId.isNullOrBlank() || normalize(it.musicBrainzId) == normalize(remote.mbid) }
            // Resolve a whole edition family before using a remote album to disambiguate families.
            if (candidates.size > 1 && !sameSong(candidates, names) && !remote.album.isNullOrBlank()) {
                val sameAlbum = candidates.filter { LastFmNames.album(it.album) == LastFmNames.album(remote.album) }
                if (sameAlbum.isNotEmpty()) candidates = sameAlbum
            }
            val manual = mappings[remote.key]?.let(byId::get)
            val chosen = when {
                manual != null -> {
                    val family = byName[names.getValue(manual.id)].orEmpty().filter {
                        LastFmNames.album(it.album) == LastFmNames.album(manual.album) }
                    if (family.size > 1 && sameSong(family, names)) family else listOf(manual)
                }
                strong.isNotEmpty() -> candidates
                candidates.size == 1 -> candidates
                candidates.size > 1 && sameSong(candidates, names) -> candidates
                else -> emptyList()
            }
            if (chosen.isEmpty()) {
                if (plausible.isEmpty()) absent++ else review += LastFmReview(remote, plausible)
            } else {
                matched++
                if (chosen.size > 1) editions += LastFmReview(remote, chosen)
                for (track in chosen) {
                    val previous = counts[track.id] ?: 0; val plays = remote.plays.coerceAtLeast(0)
                    counts[track.id] = if (previous > Long.MAX_VALUE - plays) Long.MAX_VALUE else previous + plays
                }
            }
        }
        return LastFmMatches(counts, review.sortedByDescending { it.remote.plays }, absent,
            editions.sortedByDescending { it.remote.plays }, matched, dismissed)
    }
    private fun sameSong(tracks: List<Track>, names: Map<String, LastFmNames.Name>): Boolean =
        tracks.map { names.getValue(it.id) }.distinct().size == 1 &&
            tracks.map { LastFmNames.album(it.album) }.distinct().singleOrNull()?.isNotBlank() == true &&
            tracks.mapNotNull { it.musicBrainzId?.takeIf(String::isNotBlank)?.let(::normalize) }.distinct().size <= 1
}

package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import java.text.Normalizer
import java.util.Locale

/** The remote snapshot is never a replacement for local listening events. */
data class LastFmTrack(val artist: String, val title: String, val plays: Long, val album: String? = null, val mbid: String? = null, val durationMs: Long? = null) {
    val key: String = listOf(normalize(artist), normalize(title), normalize(album), normalize(mbid))
        .joinToString("") { "${it.length}:$it" }
}
data class LastFmSnapshot(val username: String, val syncedAt: Long, val tracks: List<LastFmTrack>,
    val localBaseline: Map<String, Long>? = null, val refreshBoundarySeconds: Long? = null)
data class SavedLastFm(val username: String = "", val useCounts: Boolean = false,
    val snapshot: LastFmSnapshot? = null, val mappings: Map<String, String> = emptyMap(), val attemptedAt: Long? = null, val active: Boolean = true,
    val keepSyncingInBackground: Boolean = true, val activeAttempt: String? = null)
data class LastFmState(val saved: SavedLastFm = SavedLastFm(), val loading: Boolean = true,
    val syncing: Boolean = false, val problem: String? = null, val progress: LastFmProgress? = null)
enum class LastFmMatchTier { CLEAR, PROBABLE, UNCERTAIN }
data class LastFmReview(val remote: LastFmTrack, val candidates: List<Track>,
    val reason: String = "Several plausible local recordings", val tier: LastFmMatchTier = LastFmMatchTier.UNCERTAIN)
data class LastFmResolution(val remote: LastFmTrack, val tracks: List<Track>, val tier: LastFmMatchTier,
    val reason: String, val score: Int, val manual: Boolean = false)
data class LastFmMatches(val counts: Map<String, Long>, val review: List<LastFmReview>,
    val notInLibrary: Int = 0, val editions: List<LastFmReview> = emptyList(), val matched: Int = 0, val dismissed: Int = 0,
    val resolutions: List<LastFmResolution> = emptyList(), val unmatched: List<LastFmTrack> = emptyList(), val recordingGroups: Map<String, String> = emptyMap(), val sourceSnapshot: LastFmSnapshot? = null,
    /** False for counts-only results (streamed or restored, #779): review and resolution lists are absent, [buckets] are not. */
    val complete: Boolean = true,
    /** Bucket sizes as metadata, so a counts-only result still describes the archive without any list. */
    val buckets: LastFmMatchBuckets = LastFmMatchBuckets(matched, resolutions.count { it.tier == LastFmMatchTier.CLEAR },
        resolutions.count { it.tier == LastFmMatchTier.PROBABLE }, review.size, notInLibrary, editions.size)) {
    val judgments: List<LastFmResolution> = resolutions.filter { it.tier == LastFmMatchTier.PROBABLE }
    val clearCount: Int = resolutions.count { it.tier == LastFmMatchTier.CLEAR }
}

/** Sizes of the dashboard buckets; equal whether folded from lists or counted while streaming. */
data class LastFmMatchBuckets(val matched: Int, val clear: Int, val judgments: Int, val review: Int, val absent: Int, val editions: Int)

private val whitespace = Regex("\\s+")
internal fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
    .trim().replace(whitespace, " ").lowercase(Locale.ROOT)

/** Matching normalization never changes persisted remote keys. Musical version labels remain meaningful. */
internal object LastFmNames {
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val feature = Regex("(?i)\\b(?:feat\\.?|ft\\.?|featuring)\\s+")
    private val edition = "(?:(?:\\d{4}\\s+)?(?:re-?master(?:ed)?)(?:\\s+\\d{4})?|(?:super\\s+)?deluxe(?:\\s+edition)?|expanded(?:\\s+edition)?|special\\s+edition|anniversary(?:\\s+edition)?|\\d+(?:st|nd|rd|th)\\s+anniversary(?:\\s+edition)?)"
    private val editionSuffix = Regex("(?i)(?:\\s*[-–—]\\s*|\\s*[\\[(])$edition[\\])]?$|\\s+$edition$")
    private val description = "(?:official(?:\\s+music)?\\s+(?:audio|video)|lyrics?(?:\\s+video)?|(?:drake\\s+)?diss(?:\\s+track)?|hd|hq|4k|explicit(?:\\s+version)?|re-?upload(?:ed)?|visuali[sz]er)"
    private val bracket = Regex("[\\[(]([^\\])]+)[\\])]")
    private val descriptor = Regex("(?i)$description")
    private val descriptiveSuffix = Regex("(?i)\\s*[-–—]\\s*$description$|\\s+(?:official(?:\\s+music)?\\s+(?:audio|video)|lyrics?(?:\\s+video)?|hd|hq|4k)$")
    private val fileExtension = Regex("(?i)\\.(?:mp3|m4a|flac|wav|ogg|opus|aac)$")
    private val trackNumber = Regex("^\\s*\\d{1,3}[. _-]+(?=\\p{L})")
    private val collaboration = Regex("(?i)\\s+(?:and|x)\\s+|\\s*&\\s*")
    private val apostrophes = Regex("['’'.]")
    data class Name(val artist: String, val title: String, val featured: String)
    fun text(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT).replace(apostrophes, "")
        .replace("&", " and ").replace(punctuation, " ").trim().replace(spaces, " ")
    private fun credit(value: String) = value.split(collaboration).map(::text).filter(String::isNotBlank).sorted().joinToString(" & ")
    fun album(value: String?): String = text(stripEdition(value.orEmpty()))
    private fun stripEdition(value: String): String {
        var result = value.trim()
        while (true) {
            val next = result.replace(editionSuffix, "").trim()
            if (next == result) return result
            result = next
        }
    }
    private fun descriptions(value: String): String {
        var result = value.replace(bracket) { if (descriptor.matches(it.groupValues[1].trim())) "" else it.value }.trim()
        while (true) {
            val next = result.replace(descriptiveSuffix, "").trim()
            if (next == result || next.isBlank()) return result
            result = next
        }
    }
    fun name(artist: String?, title: String): Name {
        val artists = artist.orEmpty().split(feature, limit = 2)
        val rawTitle = stripEdition(descriptions(title.replace(fileExtension, "").replace(trackNumber, "")))
        val titles = rawTitle.split(feature, limit = 2)
        val guests = listOfNotNull(artists.getOrNull(1), titles.getOrNull(1))
            .map { it.trim().trimEnd(')', ']') }.filter(String::isNotBlank)
        val mainTitle = titles.first().trim().trimEnd('(', '[').trim()
        return Name(credit(artists.first()), text(stripEdition(mainTitle)), guests.map(::credit).distinct().sorted().joinToString(" & "))
    }
}

object LastFmMatcher {
    const val NONE = ""
    const val REVIEW = ":review:"
    fun match(snapshot: LastFmSnapshot?, tracks: List<Track>, mappings: Map<String, String> = emptyMap(),
        checkActive: () -> Unit = {}): LastFmMatches =
        if (snapshot == null) LastFmMatches(emptyMap(), emptyList()) else {
            io.github.anastaci1a.localica.diagnostics.WorkCounters.lastFmMatches.incrementAndGet()
            LastFmMatchingEngine(tracks).match(snapshot, mappings, checkActive)
        }
    /** Streams the archive and keeps only per-song totals and groups (#779); equal to [match] for those fields. */
    fun matchCounts(snapshot: LastFmSnapshot?, tracks: List<Track>, mappings: Map<String, String> = emptyMap(),
        checkActive: () -> Unit = {}): LastFmMatches =
        if (snapshot == null) LastFmMatches(emptyMap(), emptyList()) else {
            io.github.anastaci1a.localica.diagnostics.WorkCounters.lastFmMatches.incrementAndGet()
            LastFmMatchingEngine(tracks).matchCounts(snapshot, mappings, checkActive)
        }
}

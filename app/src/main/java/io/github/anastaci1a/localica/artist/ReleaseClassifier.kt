package io.github.anastaci1a.localica.artist

import io.github.anastaci1a.localica.library.LibraryGroup
import java.util.Locale

/** Single shelf classification; secondary tagged types take precedence over the base album type. */
enum class ReleaseType { Album, Single, EP, Live, Compilation }
enum class ClassificationConfidence { Tagged, Heuristic, Uncertain }
data class ReleaseClassification(val type: ReleaseType, val confidence: ClassificationConfidence)

object ReleaseClassifier {
    private val live = Regex("(?i)(\\blive\\b|\\bunplugged\\b|\\bin concert\\b)")
    private val compilation = Regex("(?i)\\b(best of|greatest hits|essential|anthology|collection)\\b")

    fun classify(release: LibraryGroup): ReleaseClassification {
        val tracks = release.tracks.distinctBy { it.id }
        val tags = tracks.mapNotNull { it.releaseType }.flatMap { it.lowercase(Locale.ROOT)
            .split(Regex("[;,/\\s]+")) }.toSet()
        val explicit = when {
            "compilation" in tags -> ReleaseType.Compilation
            "live" in tags -> ReleaseType.Live
            "ep" in tags -> ReleaseType.EP
            "single" in tags -> ReleaseType.Single
            "album" in tags -> ReleaseType.Album
            else -> null
        }
        // TCMP is also an explicit tag and overrides a generic "album" release type.
        if (tracks.any { it.compilation == true }) return ReleaseClassification(ReleaseType.Compilation, ClassificationConfidence.Tagged)
        if (explicit != null) return ReleaseClassification(explicit, ClassificationConfidence.Tagged)
        if (tracks.isEmpty()) return ReleaseClassification(ReleaseType.Album, ClassificationConfidence.Uncertain)
        val liveTracks = tracks.count { live.containsMatchIn(it.title) }
        if (live.containsMatchIn(release.title) || liveTracks * 5 >= tracks.size * 3)
            return ReleaseClassification(ReleaseType.Live, ClassificationConfidence.Heuristic)
        val multipleArtists = tracks.mapNotNull { it.artist?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }.distinct().size > 1
        val variousArtists = tracks.any { it.albumArtist?.trim()?.equals("Various Artists", true) == true }
        if (compilation.containsMatchIn(release.title) || variousArtists || multipleArtists)
            return ReleaseClassification(ReleaseType.Compilation, ClassificationConfidence.Heuristic)
        val knownDuration = tracks.all { (it.durationMs ?: 0) > 0 }
        // Compare against thresholds without overflowing when malformed durations are extremely large.
        val duration = tracks.fold(0L) { sum, track -> (sum + (track.durationMs ?: 0).coerceIn(0, 1_800_000)).coerceAtMost(1_800_000) }
        val type = when {
            knownDuration && tracks.size <= 3 && duration < 1_200_000 -> ReleaseType.Single
            tracks.size <= 6 || (knownDuration && duration < 1_800_000) -> ReleaseType.EP
            else -> ReleaseType.Album
        }
        return ReleaseClassification(type, if (knownDuration) ClassificationConfidence.Heuristic else ClassificationConfidence.Uncertain)
    }
}

package io.github.anastaci1a.localica.statistics

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.time.ZonedDateTime

/** Calendar ranges use the caller's zone; the rolling month/week ranges retain local wall time. */
enum class ListeningRange {
    AllTime, ThisYear, LastSixMonths, LastMonth, LastWeek;

    fun window(now: ZonedDateTime): ListeningWindow = ListeningWindow(when (this) {
        AllTime -> Long.MIN_VALUE
        ThisYear -> now.toLocalDate().withDayOfYear(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        LastSixMonths -> now.minusMonths(6).toInstant().toEpochMilli()
        LastMonth -> now.minusMonths(1).toInstant().toEpochMilli()
        LastWeek -> now.minusWeeks(1).toInstant().toEpochMilli()
    }, now.toInstant().toEpochMilli())
}

/** Inclusive bounds exclude future events. Refresh the window when the time range or clock changes. */
data class ListeningWindow(val from: Long, val through: Long) {
    init { require(from <= through) }
}

/** Providers return only counts available for this exact window. Unsupported windows return empty. */
fun interface PlayCountSource {
    fun counts(window: ListeningWindow): Flow<Map<String, Long>>
}

data class RankingSettings(val useExternalCounts: Boolean = false)
enum class PlayCountOrigin { Local, External }

/** Separate snapshots remain intact. Missing, zero or invalid external counts fall back per song. */
data class RankingCounts(val local: Map<String, Long>, val external: Map<String, Long> = emptyMap(),
    val settings: RankingSettings = RankingSettings()) {
    fun origin(id: String): PlayCountOrigin = if (settings.useExternalCounts && (external[id] ?: 0) > 0)
        PlayCountOrigin.External else PlayCountOrigin.Local
    fun plays(id: String): Long = (if (origin(id) == PlayCountOrigin.External) external[id] else local[id])?.coerceAtLeast(0) ?: 0
    fun longestListenedMs(track: Track): Long = saturatedMultiply(plays(track.id), track.durationMs?.coerceAtLeast(0) ?: 0)
    /** Albums and artists count a library song once, even if it has several folder locations. */
    fun longestListenedMs(tracks: Iterable<Track>): Long = tracks.distinctBy { it.id }
        .fold(0L) { total, track -> saturatedAdd(total, longestListenedMs(track)) }
}

/** #20 can supply a cached Last.fm provider without writing into local statistics or Track.playCount. */
class PlayRankingSource(private val local: PlayCountSource, private val external: PlayCountSource? = null) {
    fun counts(window: ListeningWindow, settings: Flow<RankingSettings>): Flow<RankingCounts> =
        combine(local.counts(window), external?.counts(window) ?: flowOf(emptyMap()), settings) { own, remote, options ->
            RankingCounts(own, remote, options)
        }
}

internal fun saturatedMultiply(a: Long, b: Long): Long = if (b > 0 && a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
internal fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

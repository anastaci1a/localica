package io.github.anastaci1a.localica.artist

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ArtistDataSourceTest {
    private fun song(id: String, album: String = "Album", year: Int = 2026, number: Int = 1,
        duration: Long? = 180_000, artist: String = "Artist") = Track(id, id, artist, album, duration,
        "$id.mp3", "$id.mp3", trackNumber = number, year = year, albumArtist = "Artist")
    private fun album(vararg tracks: Track) = LibraryGroup("release", tracks.first().album!!, "Artist", tracks.toList())
    private fun load(tracks: List<Track>, counts: RankingCounts = RankingCounts(emptyMap()),
        playlists: List<ManualPlaylist> = emptyList()) = ArtistDataSource().load(ArtistScope(" artist "),
        LibrarySnapshot(tracks = tracks), playlists, counts, 2026)

    @Test fun releaseTagsAndCompilationFlagOverrideEveryHeuristic() {
        val live = song("one", "Greatest Hits (Live)").copy(releaseType = "album")
        assertEquals(ReleaseClassification(ReleaseType.Album, ClassificationConfidence.Tagged), ReleaseClassifier.classify(album(live)))
        for ((tag, type) in listOf("single" to ReleaseType.Single, "EP" to ReleaseType.EP,
            "album; live" to ReleaseType.Live, "album / compilation" to ReleaseType.Compilation)) {
            assertEquals(ReleaseClassification(type, ClassificationConfidence.Tagged),
                ReleaseClassifier.classify(album(live.copy(releaseType = tag))))
        }
        assertEquals(ReleaseType.Compilation, ReleaseClassifier.classify(album(live.copy(compilation = true))).type)
        assertEquals(ReleaseType.Single, ReleaseClassifier.classify(album(song("one").copy(compilation = false))).type)
    }

    @Test fun heuristicThresholdsAndMissingDurationsReportConfidence() {
        fun release(count: Int, duration: Long?) = album(*(1..count).map { song("$it", duration = duration) }.toTypedArray())
        assertEquals(ReleaseType.Single, ReleaseClassifier.classify(release(3, 399_999)).type)
        assertEquals(ReleaseType.EP, ReleaseClassifier.classify(release(3, 400_000)).type)
        assertEquals(ReleaseType.EP, ReleaseClassifier.classify(release(6, 600_000)).type)
        assertEquals(ReleaseType.EP, ReleaseClassifier.classify(release(7, 250_000)).type)
        assertEquals(ReleaseType.Album, ReleaseClassifier.classify(release(7, 260_000)).type)
        assertEquals(ClassificationConfidence.Uncertain, ReleaseClassifier.classify(release(3, null)).confidence)
        assertEquals(ReleaseType.EP, ReleaseClassifier.classify(release(3, null)).type)
        assertEquals(ReleaseType.Album, ReleaseClassifier.classify(release(7, Long.MAX_VALUE)).type)
        assertEquals(ClassificationConfidence.Uncertain,
            ReleaseClassifier.classify(LibraryGroup("empty", "Empty", null, emptyList())).confidence)
    }

    @Test fun liveMarkersAndSixtyPercentTracksAndCompilationArtistsAreDetected() {
        for (title in listOf("Concert (Live)", "Unplugged", "Live at Home", "In Concert"))
            assertEquals(ReleaseType.Live, ReleaseClassifier.classify(album(song("one", title))).type)
        val live = (1..5).map { song(if (it <= 3) "$it (Live)" else "$it") }
        assertEquals(ReleaseType.Live, ReleaseClassifier.classify(album(*live.toTypedArray())).type)
        assertEquals(ReleaseType.Single, ReleaseClassifier.classify(album(*live.takeLast(3).toTypedArray())).type)
        assertEquals(ReleaseType.Compilation, ReleaseClassifier.classify(album(song("one", "Anthology"))).type)
        assertEquals(ReleaseType.Compilation, ReleaseClassifier.classify(album(song("one"), song("two", artist = "Guest"))).type)
        assertEquals(ReleaseType.Compilation, ReleaseClassifier.classify(album(song("one").copy(albumArtist = "Various Artists"))).type)
    }

    @Test fun topSongsUseSelectedCountsThenStableReleaseAndTrackOrder() {
        val tracks = listOf(song("old", "Old", 2020), song("b", number = 2), song("a", number = 1))
        val ranked = load(tracks, RankingCounts(mapOf("old" to 8, "a" to 2, "b" to 2)))
        assertEquals(listOf("old", "a", "b"), ranked.topSongs.map { it.track.id })
        assertFalse(ranked.usesFallbackOrder)
        assertEquals(listOf(8L, 2L, 2L), ranked.topSongs.map { it.plays })
        assertEquals(12L * 180_000, ranked.longestListenedMs)
        assertEquals(8L * 180_000, ranked.releases.first { it.album.title == "Old" }.longestListenedMs)
        assertEquals(listOf("a", "b", "old"), load(tracks).topSongs.map { it.track.id })
        assertTrue(load(tracks).usesFallbackOrder)
    }

    @Test fun fallbackAndLatestUseYearThenAddedDateWithFiveSongLimit() {
        val tracks = listOf(song("old", "Old", 2025), song("z", "Z").copy(addedAt = 10),
            song("a", "A").copy(addedAt = 9)) + (1..6).map { song("$it", "Z", number = it + 1).copy(addedAt = 10) }
        val result = load(tracks)
        assertEquals("Z", result.latestRelease?.album?.title)
        assertEquals(listOf("z", "1", "2", "3", "4"), result.topSongs.map { it.track.id })
        assertNull(load(tracks.map { it.copy(year = 2025) }).latestRelease)
        assertNull(load(tracks.map { it.copy(year = null) }).latestRelease)
        assertEquals("A", load(listOf(song("future", "Future", 2027), song("current", "A"))).latestRelease?.album?.title)
    }

    @Test fun playlistMembershipRefreshesMetadataCountsUniqueSongsAndKeepsMissingReferences() {
        val a = song("a"); val b = song("b")
        val lists = listOf(ManualPlaylist("few", "Few", entries = listOf(SongRow("1", a), SongRow("2", a))),
            ManualPlaylist("many", "Many", entries = listOf(SongRow("3", a.copy(artist = "Stale")), SongRow("4", b))),
            ManualPlaylist("other", "Other", entries = listOf(SongRow("5", song("x", artist = "Other")))))
        val result = load(listOf(a), playlists = lists)
        assertEquals(listOf("many", "few"), result.playlists.map { it.playlist.id })
        assertEquals(listOf(2, 1), result.playlists.map { it.matchingSongs })
        assertTrue(result.appearsOn.isEmpty()); assertTrue(result.similarArtists.isEmpty())
        assertTrue(load(emptyList()).topSongs.isEmpty())
        assertFalse(ArtistScope("Artist").contains(a.copy(artist = "Artist feat. Guest")))
        assertTrue(ArtistScope("Artist", albumArtist = true).contains(a.copy(artist = "Guest")))
    }

    @Test fun weightedCountsStaySeparateAndSaturateWithoutNegativeOrDuplicateTotals() {
        val a = song("a", duration = 10); val b = song("b", duration = 100)
        val own = mapOf("a" to 10L, "b" to 2L)
        val remote = mapOf("a" to 1L, "b" to 0L)
        val selected = RankingCounts(own, remote, RankingSettings(true))
        assertEquals(1L, selected.plays("a")); assertEquals(2L, selected.plays("b"))
        assertEquals(PlayCountOrigin.External, selected.origin("a"))
        assertEquals(PlayCountOrigin.Local, selected.origin("b"))
        assertEquals(210L, selected.longestListenedMs(listOf(a, a, b)))
        assertEquals(300L, selected.copy(settings = RankingSettings(false)).longestListenedMs(listOf(a, b)))
        assertEquals(own, selected.local); assertEquals(remote, selected.external)
        assertEquals(0L, selected.longestListenedMs(a.copy(durationMs = null)))
        assertEquals(0L, selected.longestListenedMs(a.copy(durationMs = -1)))
        assertEquals(0L, RankingCounts(mapOf("a" to -1)).plays("a"))
        assertEquals(Long.MAX_VALUE, RankingCounts(mapOf("a" to Long.MAX_VALUE)).longestListenedMs(a))
        assertEquals(Long.MAX_VALUE, RankingCounts(mapOf("a" to Long.MAX_VALUE, "b" to 1)).longestListenedMs(listOf(a, b)))
    }

    @Test fun calendarWindowsIncludeCurrentYearAndPreserveZoneAcrossDstAndMonthEnds() {
        val now = ZonedDateTime.parse("2026-11-07T12:00:00-05:00[America/New_York]")
        assertEquals(Long.MIN_VALUE, ListeningRange.AllTime.window(now).from)
        assertEquals(ZonedDateTime.parse("2026-01-01T00:00:00-05:00[America/New_York]").toInstant().toEpochMilli(), ListeningRange.ThisYear.window(now).from)
        for ((range, expected) in listOf(ListeningRange.LastWeek to now.minusWeeks(1), ListeningRange.LastMonth to now.minusMonths(1),
            ListeningRange.LastSixMonths to now.minusMonths(6))) {
            assertEquals(expected.toInstant().toEpochMilli(), range.window(now).from)
            assertEquals(now.toInstant().toEpochMilli(), range.window(now).through)
        }
        val march = ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, ZoneId.of("America/New_York"))
        assertEquals(28, java.time.Instant.ofEpochMilli(ListeningRange.LastMonth.window(march).from).atZone(march.zone).dayOfMonth)
    }

    @Test fun providerSelectionReactsToSettingAndUnsupportedRangesFallBackLocally() = runBlocking {
        val local = PlayCountSource { flowOf(mapOf("a" to 7L)) }
        val remote = PlayCountSource { window -> flowOf(if (window.from == Long.MIN_VALUE) mapOf("a" to 2L) else emptyMap()) }
        val source = PlayRankingSource(local, remote)
        val settings = MutableStateFlow(RankingSettings())
        val all = ListeningWindow(Long.MIN_VALUE, 10)
        assertEquals(7L, source.counts(all, settings).first().plays("a"))
        settings.value = RankingSettings(true)
        assertEquals(2L, source.counts(all, settings).first().plays("a"))
        assertEquals(7L, source.counts(ListeningWindow(0, 10), settings).first().plays("a"))
        assertEquals(7L, PlayRankingSource(local).counts(all, settings).first().plays("a"))
        settings.value = RankingSettings(false)
        assertEquals(7L, source.counts(all, settings).first().plays("a"))
    }
    @Test fun observedArtistRankingUsesEachRequestedWindowAndFallbackWithinThatWindow() = runBlocking {
        val now = ZonedDateTime.parse("2026-11-07T12:00:00Z")
        val local = PlayCountSource { window -> flowOf(if (window.from == Long.MIN_VALUE) mapOf("old" to 5L) else emptyMap()) }
        val library = flowOf(LibrarySnapshot(tracks = listOf(song("old", "Old", 2020), song("new", "New"))))
        for (range in ListeningRange.entries) {
            val page = ArtistDataSource().observe(ArtistScope("Artist"), library, flowOf(emptyList()),
                PlayRankingSource(local), flowOf(RankingSettings()), range, now).first()
            assertEquals(range != ListeningRange.AllTime, page.usesFallbackOrder)
            assertEquals(if (range == ListeningRange.AllTime) "old" else "new", page.topSongs.first().track.id)
        }
    }

}

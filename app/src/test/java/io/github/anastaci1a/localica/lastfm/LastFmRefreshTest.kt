package io.github.anastaci1a.localica.lastfm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LastFmRefreshTest {
    private val song = LastFmTrack("Artist", "Song", 20)
    private fun archive() = LastFmSnapshot("user", 100_000, listOf(song) + (1..999).map {
        LastFmTrack("Artist $it", "Archived $it", it.toLong()) }, localBaseline = mapOf("local-song" to 7), refreshBoundarySeconds = 100)
    private abstract class QuickApi : LastFmApi {
        override fun supportsQuickRefresh() = true
        override suspend fun page(username: String, number: Int): LastFmPage = error("No full paging expected")
        override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage = error("No recent paging expected")
        override suspend fun info(username: String, track: LastFmTrack): LastFmTrack = error("No info request expected")
    }

    @Test fun quickRefreshFetchesOnlyRecentPagesAndAuthoritativeChangedSongCounts() = runBlocking {
        val requests = mutableListOf<String>(); val pauses = mutableListOf<Long>(); val progress = mutableListOf<LastFmProgress>()
        val sync = LastFmSync(object : QuickApi() {
            override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage {
                assertEquals("user", username); assertEquals(99L, from); assertEquals(201L, through)
                requests += "recent$number"
                return LastFmRecentPage(number, 2, listOf(LastFmRecentTrack("Artist", "Song", 150L + number, "Album")))
            }
            override suspend fun info(username: String, track: LastFmTrack): LastFmTrack {
                requests += "info"; assertEquals(song.key, track.key)
                return track.copy(plays = 25, durationMs = 240_000)
            }
        }, { pauses += it })
        val result = sync.refresh("user", archive(), false, 200, { progress += it })
        assertEquals(listOf("recent1", "recent2", "info"), requests)
        assertEquals(listOf(500L, 500L), pauses)
        assertEquals(1000, result.size); assertEquals(25L, result.single { it.key == song.key }.plays)
        assertEquals(240_000L, result.single { it.key == song.key }.durationMs)
        assertEquals(20L, archive().tracks.first().plays)
        assertEquals(100, progress.last().percent); assertEquals(1, progress.last().songs)
        assertEquals(1, progress.last().artists); assertEquals(1, progress.last().albums)
        assertFalse(progress.last().label.contains("Page"))
    }

    @Test fun largeChangeSetsFallBackToFullPagingUsingTheSameRequestPacer() = runBlocking {
        val requests = mutableListOf<String>(); val pauses = mutableListOf<Long>()
        val sync = LastFmSync(object : QuickApi() {
            override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage {
                requests += "recent"
                return LastFmRecentPage(1, 1, (1..6).map { LastFmRecentTrack("Artist $it", "Changed $it", 150) })
            }
            override suspend fun page(username: String, number: Int): LastFmPage {
                requests += "full"; return LastFmPage(1, 1, listOf(song.copy(plays = 99)))
            }
        }, { pauses += it })
        assertEquals(listOf(song.copy(plays = 99)), sync.refresh("user", archive(), false, 200))
        assertEquals(listOf("recent", "full"), requests); assertEquals(listOf(500L), pauses)
    }

    @Test fun explicitFullRescanNeverUsesRecentOrTrackInfoEndpoints() = runBlocking {
        var calls = 0
        val sync = LastFmSync(object : QuickApi() {
            override suspend fun page(username: String, number: Int): LastFmPage { calls++; return LastFmPage(1, 1, listOf(song)) }
        }, {})
        assertEquals(listOf(song), sync.refresh("user", archive(), true, 200)); assertEquals(1, calls)
    }

    @Test fun anUnresolvableChangedTrackUsesAFullRescanWithoutPublishingPartialQuickData() = runBlocking {
        val requests = mutableListOf<String>(); val pauses = mutableListOf<Long>()
        val sync = LastFmSync(object : QuickApi() {
            override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage {
                requests += "recent"; return LastFmRecentPage(1, 1, listOf(LastFmRecentTrack("Artist", "Song", 150)))
            }
            override suspend fun info(username: String, track: LastFmTrack): LastFmTrack {
                requests += "info"; throw LastFmFailure("Full rescan required", needsFullRescan = true)
            }
            override suspend fun page(username: String, number: Int): LastFmPage {
                requests += "full"; return LastFmPage(1, 1, listOf(song.copy(plays = 26)))
            }
        }, { pauses += it })
        assertEquals(listOf(song.copy(plays = 26)), sync.refresh("user", archive(), false, 200))
        assertEquals(listOf("recent", "info", "full"), requests); assertEquals(listOf(500L, 500L), pauses)
    }

    @Test fun recentParserIgnoresNowPlayingAndInfoNeverSubstitutesGlobalPlayCounts() {
        val recent = LastFmResponses.recent("""{"recenttracks":{"track":[{"artist":{"#text":"Artist"},"name":"Playing","@attr":{"nowplaying":"true"}},{"artist":{"#text":"Artist"},"name":"Song","mbid":null,"date":{"uts":"150"}}],"@attr":{"page":"1","totalPages":"1"}}}""")
        assertEquals(listOf(LastFmRecentTrack("Artist", "Song", 150)), recent.tracks)
        val info = LastFmResponses.info("""{"track":{"artist":{"name":"Artist"},"name":"Song","duration":"240000","userplaycount":"12","playcount":"999999"}}""", song)
        assertEquals(12L, info.plays); assertEquals(240_000L, info.durationMs)
        try {
            LastFmResponses.info("""{"track":{"artist":{"name":"Artist"},"name":"Song","playcount":"999999"}}""", song)
            fail("Missing userplaycount must fail; global playcount is never an account total")
        } catch (_: LastFmFailure) { }
        val top = LastFmResponses.parse("""{"toptracks":{"track":[{"artist":"Artist","name":"Song","playcount":"12","duration":"240"}],"@attr":{"page":"1","totalPages":"1"}}}""")
        assertEquals(240_000L, top.tracks.single().durationMs)
        val unknown = LastFmResponses.parse("""{"toptracks":{"track":[{"artist":"Artist","name":"Song","playcount":"12","duration":"0"}],"@attr":{"page":"1","totalPages":"1"}}}""")
        assertNull(unknown.tracks.single().durationMs)
    }

    @Test fun aCancelledOrConsentRevokedQuickRefreshMakesNoSubsequentRequest() = runBlocking {
        var allowed = true; var infos = 0
        val sync = LastFmSync(object : QuickApi() {
            override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage {
                allowed = false; return LastFmRecentPage(1, 1, listOf(LastFmRecentTrack("Artist", "Song", 150)))
            }
            override suspend fun info(username: String, track: LastFmTrack): LastFmTrack { infos++; return track.copy(plays = 50) }
        }, {})
        try { sync.refresh("user", archive(), false, 200, mayRequest = { allowed }); fail("Consent off must stop the refresh") }
        catch (_: LastFmFailure) { }
        assertEquals(0, infos); assertEquals(20L, archive().tracks.first().plays)
    }
}

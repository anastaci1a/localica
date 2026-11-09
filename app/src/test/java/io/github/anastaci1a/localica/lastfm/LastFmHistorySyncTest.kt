package io.github.anastaci1a.localica.lastfm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LastFmHistorySyncTest {
    private fun api(read: suspend (Int, Long, Long) -> LastFmRecentPage) = object : LastFmApi {
        override fun supportsHistoryArchive() = true
        override suspend fun page(username: String, number: Int): LastFmPage = error("Top tracks must never build an archive")
        override suspend fun info(username: String, track: LastFmTrack): LastFmTrack = error("Title-wide counts must never overwrite editions")
        override suspend fun recent(username: String, number: Int, from: Long, through: Long) = read(number, from, through)
    }
    private fun row(time: Long, album: String = "Studio") = LastFmRecentTrack("Artist", "Song", time, album)

    @Test fun hundredThousandScrobblesUseFiveHundredPacedPagesAndPreserveMultiplicity() = runBlocking {
        var requests = 0; val pauses = mutableListOf<Long>(); val store = MemoryLastFmHistoryStore()
        val sync = LastFmSync(api { number, from, through ->
            requests++; assertEquals(0L, from); assertEquals(200_000L, through)
            LastFmRecentPage(number, 500, (0 until 200).map { index ->
                val n = (number - 1) * 200 + index
                LastFmRecentTrack("Artist", "Song ${n % 100}", 100_001L - n, "Album ${n % 2}")
            })
        }, store, { pauses += it })
        val result = sync.archive("user", null, true, 200_000, mapOf("local" to 7), {}, { true })
        assertEquals(500, requests); assertEquals(499, pauses.size); assertEquals(249_500L, pauses.sum())
        assertEquals(100_000L, result.tracks.sumOf { it.plays }); assertEquals(100, result.tracks.size)
        assertEquals(mapOf("local" to 7L), result.localBaseline); assertTrue(result.historyComplete)
        assertEquals(501, store.read()!!.nextPage)
    }

    @Test fun albumAwareQuickSyncAddsOnlyItsEditionAndCountsIdenticalSameSecondScrobbles() = runBlocking {
        val old = LastFmSnapshot("user", 100_000, listOf(LastFmTrack("Artist", "Song", 20, "Studio"),
            LastFmTrack("Artist", "Song", 10, "Live")), mapOf("local" to 7), 100, true)
        val sync = LastFmSync(api { number, from, through ->
            assertEquals(99L, from); assertEquals(200L, through)
            LastFmRecentPage(number, 1, listOf(row(150, "Live"), row(140), row(140)))
        }, {})
        val result = sync.archive("user", old, false, 200, mapOf("local" to 9), {}, { true })
        assertEquals(22L, result.tracks.single { it.album == "Studio" }.plays)
        assertEquals(11L, result.tracks.single { it.album == "Live" }.plays)
        assertEquals(20L, old.tracks.first().plays); assertEquals(200L, result.through)
    }

    @Test fun legacyTopTrackArchiveMigratesFromFullHistoryRatherThanAddingToTitleTotals() = runBlocking {
        val old = LastFmSnapshot("user", 100_000, listOf(LastFmTrack("Artist", "Song", 999)), refreshBoundarySeconds = 100)
        val sync = LastFmSync(api { number, from, _ ->
            assertEquals(0L, from); LastFmRecentPage(number, 1, listOf(row(90), row(80, "Live"), row(70)))
        }, {})
        val result = sync.archive("user", old, false, 200, emptyMap(), {}, { true })
        assertEquals(3L, result.tracks.sumOf { it.plays }); assertEquals(2, result.tracks.size)
        assertEquals(999L, old.tracks.single().plays)
    }

    @Test fun interruptedScanResumesItsOriginalCutoffAndBaselineAfterVerifyingThePreviousPage() = runBlocking {
        val requests = mutableListOf<Int>(); val store = MemoryLastFmHistoryStore()
        val sync = LastFmSync(api { number, _, through ->
            requests += number; assertEquals(100L, through)
            LastFmRecentPage(number, 3, listOf(row(100L - 10 * number)))
        }, store, {})
        try {
            sync.archive("user", null, true, 100, mapOf("local" to 7), { if (it.page == 1) throw CancellationException() }, { true })
            fail("Cancellation expected")
        } catch (_: CancellationException) { }
        assertEquals(2, store.read()!!.nextPage)
        val result = sync.archive("user", null, true, 300, mapOf("local" to 20), {}, { true })
        assertEquals(listOf(1, 1, 2, 3), requests); assertEquals(3L, result.tracks.single().plays)
        assertEquals(100L, result.through); assertEquals(mapOf("local" to 7L), result.localBaseline)
        sync.acknowledgeHistory(result.stagingId); assertNull(store.read())
    }

    @Test fun changedResumeBoundaryDiscardsOnlyStagingAndRequiresAnExplicitRetry() = runBlocking {
        val store = MemoryLastFmHistoryStore(); var changed = false
        val sync = LastFmSync(api { number, _, _ -> LastFmRecentPage(number, 2,
            listOf(row(if (changed) 89 else 90))) }, store, {})
        try { sync.archive("user", null, true, 100, emptyMap(), { if (it.page == 1) throw CancellationException() }, { true }) }
        catch (_: CancellationException) { }
        changed = true
        try { sync.archive("user", null, true, 200, emptyMap(), {}, { true }); fail("Changed page must not be counted") }
        catch (failure: LastFmFailure) { assertTrue(failure.message!!.contains("history changed")) }
        assertNull(store.read())
    }

    @Test fun consentRevocationStopsBeforeTheNextRequestAndExplicitDeleteInvalidatesAnOldCursor() = runBlocking {
        val store = MemoryLastFmHistoryStore(); var allowed = true; var requests = 0
        val sync = LastFmSync(api { number, _, _ -> requests++; LastFmRecentPage(number, 2, listOf(row(90))) }, store, {})
        try { sync.archive("user", null, true, 100, emptyMap(), { if (it.page == 1) allowed = false }, { allowed }); fail("Consent gate") }
        catch (_: LastFmFailure) { }
        assertEquals(1, requests); val old = store.read()!!
        sync.discardHistory(); assertNull(store.read())
        try { store.append(old, old.copy(nextPage = 3), LastFmRecentPage(2, 2, listOf(row(80)))); fail("Deleted cursor resurrected") }
        catch (_: IllegalStateException) { }
    }

    @Test fun failedCheckpointWriteNeverAdvancesItsCursorOrPublishesPartialCounts() = runBlocking {
        val memory = MemoryLastFmHistoryStore()
        val failing = object : LastFmHistoryStore by memory {
            override suspend fun append(previous: LastFmHistoryCheckpoint, next: LastFmHistoryCheckpoint, page: LastFmRecentPage) {
                throw java.io.IOException("Checkpoint full")
            }
        }
        val sync = LastFmSync(api { number, _, _ -> LastFmRecentPage(number, 1, listOf(row(90))) }, failing, {})
        try { sync.archive("user", null, true, 100, emptyMap(), {}, { true }); fail("Write failure") }
        catch (_: java.io.IOException) { }
        assertEquals(1, memory.read()!!.nextPage); assertTrue(memory.read()!!.tracks.isEmpty())
    }
}

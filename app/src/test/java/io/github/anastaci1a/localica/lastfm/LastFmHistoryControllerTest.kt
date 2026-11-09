package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LastFmHistoryControllerTest {
    @Test fun cancelledHistoryKeepsPublishedArchiveThenResumePublishesOnceWithItsOriginalLocalBaseline() = runBlocking {
        val original = LastFmSnapshot("user", 100_000, listOf(LastFmTrack("Artist", "Song", 999)), mapOf("song" to 7), 100)
        val storage = object : LastFmStore {
            var value = SavedLastFm("user", true, original)
            override suspend fun read() = value
            override suspend fun write(value: SavedLastFm) { this.value = value }
        }
        val history = MemoryLastFmHistoryStore()
        val secondEntered = CompletableDeferred<Unit>(); val second = CompletableDeferred<LastFmRecentPage>()
        val api = object : LastFmApi {
            override fun supportsHistoryArchive() = true
            override suspend fun page(username: String, number: Int): LastFmPage = error("No top tracks")
            override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage {
                assertEquals(200L, through)
                return if (number == 1) LastFmRecentPage(1, 2, listOf(LastFmRecentTrack("Artist", "Song", 150, "Studio")))
                else { secondEntered.complete(Unit); second.await() }
            }
        }
        val consent = object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }
        var clock = 200_000L
        val local = MutableStateFlow(ListeningStatistics(mapOf("song" to 7)))
        val library = flowOf(LibrarySnapshot(tracks = listOf(Track("song", "Song", "Artist", "Studio", 240_000, "song", "song"))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = LastFmController(storage, LastFmSync(api, history, {}), library, true, consent, scope, { clock }, localStatistics = local)
        try {
            withTimeout(3000) { controller.state.first { !it.loading } }
            controller.sync("user"); withTimeout(3000) { secondEntered.await() }
            controller.cancelSync(); withTimeout(3000) { controller.state.first { !it.syncing } }
            assertEquals(original, storage.read().snapshot); assertEquals(2, history.read()!!.nextPage)
            local.value = ListeningStatistics(mapOf("song" to 9)); clock += 60_000
            second.complete(LastFmRecentPage(2, 2, listOf(LastFmRecentTrack("Artist", "Song", 100, "Studio"))))
            controller.sync("user")
            val saved = withTimeout(3000) { controller.state.first { !it.syncing && it.saved.snapshot?.historyComplete == true } }.saved.snapshot!!
            assertEquals(200L, saved.refreshBoundarySeconds); assertEquals(mapOf("song" to 7L), saved.localBaseline)
            assertEquals(2L, saved.tracks.single().plays); assertEquals(260_000L, saved.syncedAt)
            assertEquals(saved, LastFmSnapshotCodec.decode(LastFmSnapshotCodec.encode(SavedLastFm(snapshot = saved))).snapshot)
            withTimeout(3000) { controller.overlay.first { it.counts["song"] == 4L } }
            assertNull(history.read()); assertEquals(9L, local.value.tracks["song"])
        } finally { scope.cancel() }
    }

    @Test fun splittingLegacyTitleTotalsPreservesExplicitUndoAndAbsenceWhilePositiveChoicesStayOnTheirAlbum() = runBlocking {
        for (choice in listOf(LastFmMatcher.REVIEW, LastFmMatcher.NONE, "studio")) {
            val remote = LastFmTrack("Artist", "Song", 999)
            val storage = object : LastFmStore {
                var value = SavedLastFm("user", true, LastFmSnapshot("user", 100_000, listOf(remote), emptyMap(), 100), mapOf(remote.key to choice))
                override suspend fun read() = value
                override suspend fun write(value: SavedLastFm) { this.value = value }
            }
            val api = object : LastFmApi {
                override fun supportsHistoryArchive() = true
                override suspend fun page(username: String, number: Int): LastFmPage = error("No top tracks")
                override suspend fun recent(username: String, number: Int, from: Long, through: Long) = LastFmRecentPage(1, 1,
                    listOf(LastFmRecentTrack("Artist", "Song", 150, "Studio"), LastFmRecentTrack("Artist", "Song", 140, "Live")))
            }
            val consent = object : NetworkConsent {
                override val allowed = MutableStateFlow(true)
                override suspend fun setAllowed(value: Boolean) { allowed.value = value }
            }
            val library = flowOf(LibrarySnapshot(tracks = listOf(Track("studio", "Song", "Artist", "Studio", 240_000, "studio", "studio"),
                Track("live", "Song (Live)", "Artist", "Live", 240_000, "live", "live"))))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller = LastFmController(storage, LastFmSync(api, MemoryLastFmHistoryStore(), {}), library, true, consent, scope, { 200_000 })
            try {
                withTimeout(3000) { controller.state.first { !it.loading } }; controller.sync("user")
                val saved = withTimeout(3000) { controller.state.first { !it.syncing && it.saved.snapshot?.historyComplete == true } }.saved
                val rows = saved.snapshot!!.tracks.associateBy { it.album }
                assertEquals(choice, saved.mappings[rows.getValue("Studio").key])
                if (choice == "studio") assertFalse(saved.mappings.containsKey(rows.getValue("Live").key))
                else {
                    assertEquals(choice, saved.mappings[rows.getValue("Live").key])
                    val result = LastFmMatcher.match(saved.snapshot, library.first().tracks, saved.mappings)
                    assertTrue(result.counts.isEmpty())
                    if (choice == LastFmMatcher.REVIEW) assertEquals(2, result.review.size) else assertEquals(2, result.notInLibrary)
                }
                assertEquals(choice, saved.mappings[remote.key])
            } finally { scope.cancel() }
        }
    }
}

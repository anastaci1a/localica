package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.diagnostics.WorkCounters
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** #779: a launch with an unchanged library, archive and mappings never matches the whole archive again. */
class LastFmMatchCacheTest {
    private class Consent : NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private class Store(initial: SavedLastFm) : LastFmStore {
        var bytes = LastFmSnapshotCodec.encode(initial)
        override suspend fun read() = LastFmSnapshotCodec.decode(bytes)
        override suspend fun write(value: SavedLastFm) { bytes = LastFmSnapshotCodec.encode(value) }
    }
    private class MemoryCache(var value: LastFmMatchCache? = null) : LastFmMatchCacheStore {
        val writes = mutableListOf<LastFmMatchCache?>()
        override suspend fun read() = value
        override suspend fun write(value: LastFmMatchCache?) { this.value = value; writes += value }
    }
    /** Blocks every match until released, polling cancellation like the real engine does. */
    private class Gate {
        val latch = CountDownLatch(1)
        var invocations = 0
        val matcher: (LastFmSnapshot, List<Track>, Map<String, String>, Boolean, () -> Unit) -> LastFmMatches = { snapshot, tracks, mappings, countsOnly, checkActive ->
            invocations++
            while (!latch.await(5, TimeUnit.MILLISECONDS)) checkActive()
            if (countsOnly) LastFmMatcher.matchCounts(snapshot, tracks, mappings, checkActive) else LastFmMatcher.match(snapshot, tracks, mappings, checkActive)
        }
    }

    /** The streamed counts-only fold must equal the full match wherever both report, on a corpus with every outcome kind. */
    @Test fun streamedCountsEqualTheFullMatchOnAMixedCorpus() {
        val random = java.util.Random(779)
        val titles = listOf("Orbit", "Moss", "Delta", "Glass", "Harbor", "Nova", "Prism", "Reed", "Slate", "Tide")
        val singles = (0 until 400).map { n ->
            val artist = "Artist ${n % 37}"
            val title = "${titles[n % titles.size]} ${titles[(n * 7) % titles.size]} ${n / 10}"
            Track("t$n", title, artist, "Album ${n / 10}${if (n % 23 == 0) " (Deluxe)" else ""}", 150_000L + (n % 50) * 1000, "$n.mp3", "$n.mp3",
                id = "t$n", albumArtist = artist, musicBrainzId = if (n % 11 == 0) "mb-${n / 2}" else null)
        }
        // Every 29th song also exists as a second copy of the same recording (another folder, same tags).
        val tracks = singles + singles.filterIndexed { index, _ -> index % 29 == 0 }.map { it.copy(uri = "${it.id}-copy", id = "${it.id}-copy", fileName = "${it.id}-copy.mp3") }
        val remote = (0 until 600).map { i ->
            val base = tracks[random.nextInt(tracks.size)]
            when (i % 6) {
                0 -> LastFmTrack(base.artist.orEmpty(), base.title, 1L + random.nextInt(9), base.album, durationMs = base.durationMs)
                1 -> LastFmTrack(base.artist.orEmpty(), base.title, 1L + random.nextInt(9))
                2 -> LastFmTrack(base.artist.orEmpty(), "${base.title} (Live)", 1L + random.nextInt(9), "Live")
                3 -> LastFmTrack("Channel ${i % 5}", "${base.artist} - ${base.title}", 1L + random.nextInt(9))
                4 -> LastFmTrack("Absent ${i % 13}", "Nothing here $i", 1L + random.nextInt(9))
                else -> LastFmTrack(base.artist.orEmpty(), base.title, 1L + random.nextInt(9), base.album, mbid = "mb-${random.nextInt(200)}", durationMs = base.durationMs)
            }
        }
        val archive = LastFmSnapshot("user", 7, remote, emptyMap(), 1)
        val mappings = mapOf(remote[0].key to "t3", remote[6].key to LastFmMatcher.NONE, remote[12].key to LastFmMatcher.REVIEW)
        val oracle = LegacyLastFmMatchingEngine(tracks).match(archive, mappings)
        assertTrue("fixture must exercise reviews", oracle.review.isNotEmpty())
        assertTrue("fixture must exercise multi-recording resolutions", oracle.resolutions.any { it.tracks.size > 1 })
        assertTrue(oracle.counts.isNotEmpty() && oracle.notInLibrary > 0 && oracle.dismissed >= 1 && oracle.resolutions.any { it.manual })
        assertTrue(oracle.resolutions.map { it.tier }.toSet().size >= 2)
        assertSameFullResult(oracle, LastFmMatcher.match(archive, tracks, mappings))
        assertSameCounts(oracle, LastFmMatcher.matchCounts(archive, tracks, mappings))
    }

    /** Strict full-output equality against the frozen pre-#779 engine: order, tiers, scores, reasons and manual flags included. */
    private fun assertSameFullResult(oracle: LastFmMatches, actual: LastFmMatches) {
        assertEquals(oracle.counts, actual.counts); assertEquals(oracle.recordingGroups, actual.recordingGroups)
        assertEquals(oracle.matched, actual.matched); assertEquals(oracle.notInLibrary, actual.notInLibrary); assertEquals(oracle.dismissed, actual.dismissed)
        assertEquals(oracle.resolutions.map { listOf(it.remote, it.tracks.map { t -> t.id }, it.tier, it.reason, it.score, it.manual) },
            actual.resolutions.map { listOf(it.remote, it.tracks.map { t -> t.id }, it.tier, it.reason, it.score, it.manual) })
        assertEquals(oracle.review.map { listOf(it.remote, it.candidates.map { t -> t.id }, it.reason, it.tier) },
            actual.review.map { listOf(it.remote, it.candidates.map { t -> t.id }, it.reason, it.tier) })
        assertEquals(oracle.editions.map { listOf(it.remote, it.candidates.map { t -> t.id }, it.reason, it.tier) },
            actual.editions.map { listOf(it.remote, it.candidates.map { t -> t.id }, it.reason, it.tier) })
        assertEquals(oracle.unmatched, actual.unmatched); assertEquals(oracle.judgments.size, actual.judgments.size); assertEquals(oracle.clearCount, actual.clearCount)
        assertTrue(actual.complete); assertSame(oracle.sourceSnapshot, actual.sourceSnapshot)
    }

    private fun assertSameCounts(oracle: LastFmMatches, streamed: LastFmMatches) {
        assertEquals(oracle.counts, streamed.counts); assertEquals(oracle.recordingGroups, streamed.recordingGroups)
        assertEquals(oracle.matched, streamed.matched); assertEquals(oracle.notInLibrary, streamed.notInLibrary); assertEquals(oracle.dismissed, streamed.dismissed)
        assertFalse(streamed.complete); assertTrue(streamed.resolutions.isEmpty() && streamed.review.isEmpty() && streamed.editions.isEmpty() && streamed.unmatched.isEmpty())
        assertSame(oracle.sourceSnapshot, streamed.sourceSnapshot)
    }

    /** Linked editions, a group joined transitively through a MusicBrainz recording, and saturating totals. */
    @Test fun editionsTransitiveGroupsAndSaturationFoldExactly() {
        val standard = Track("std", "Song", "Artist", "Album", 200_000, "std.flac", "std.flac", id = "std", albumArtist = "Artist")
        val deluxe = standard.copy(uri = "dlx", id = "dlx", fileName = "dlx.flac", musicBrainzId = "rec-1")
        val live = standard.copy(uri = "live", id = "live", fileName = "live.flac", album = "Live Album", musicBrainzId = "rec-1")
        val unrelated = standard.copy(uri = "other", id = "other", title = "Other", fileName = "other.flac")
        val library = listOf(standard, deluxe, live, unrelated)
        val plain = LastFmTrack("Artist", "Song", 10, "Album", durationMs = 200_000)
        val recording = LastFmTrack("Artist", "Song", 5, "Live Album", mbid = "rec-1", durationMs = 200_000)
        val huge = LastFmTrack("Artist", "Other", Long.MAX_VALUE - 1, "Album", durationMs = 200_000)
        val hugeAgain = LastFmTrack("Artist", "Other", 7, "Album", mbid = null, durationMs = 200_000)
        val archive = LastFmSnapshot("user", 1, listOf(plain, recording, huge, hugeAgain), emptyMap(), 1)
        val oracle = LegacyLastFmMatchingEngine(library).match(archive, emptyMap())
        val full = LastFmMatcher.match(archive, library, emptyMap())
        val streamed = LastFmMatcher.matchCounts(archive, library, emptyMap())
        // Linked editions: the plain song resolves to both album copies and is listed as an edition decision.
        val editions = oracle.resolutions.first { it.remote == plain }
        assertEquals(setOf("std", "dlx"), editions.tracks.map { it.id }.toSet()); assertTrue(oracle.editions.any { it.remote == plain })
        // The recording id links the live copy to the deluxe copy, and through it to the standard copy: one group of three.
        val linked = oracle.resolutions.first { it.remote == recording }
        assertTrue(linked.tracks.map { it.id }.containsAll(listOf("dlx", "live")))
        assertEquals(1, listOf("std", "dlx", "live").map { oracle.recordingGroups.getValue(it) }.toSet().size)
        assertEquals(15L, oracle.counts["std"]); assertEquals(15L, oracle.counts["dlx"]); assertEquals(15L, oracle.counts["live"])
        // Saturation: totals never overflow.
        assertEquals(Long.MAX_VALUE, oracle.counts["other"])
        assertSameFullResult(oracle, full); assertSameCounts(oracle, streamed)
    }
    private val song = Track("song", "Song", "Artist", "Album", 200_000, "song.flac", "song.flac")
    private val other = song.copy(uri = "other", id = "other", title = "Other")
    private val remote = LastFmTrack("Artist", "Song", 42, durationMs = 200_000)
    private val remoteOther = LastFmTrack("Artist", "Other", 5, durationMs = 200_000)
    private val archive = LastFmSnapshot("user", 1000, listOf(remote, remoteOther), emptyMap(), 1)
    private fun saved(active: Boolean = true) = SavedLastFm("user", true, archive, emptyMap(), active = active)
    private fun controller(library: Flow<LibrarySnapshot>, cache: LastFmMatchCacheStore, scope: CoroutineScope,
        store: Store = Store(saved()), gate: Gate? = null) =
        LastFmController(store, LastFmSync(LastFmApi { _, _ -> error("No request expected") }, {}), library, true, Consent(), scope,
            { 100_000 }, localStatistics = flowOf(ListeningStatistics()), matchCache = cache,
            matcher = gate?.matcher ?: { s, t, m, countsOnly, c -> if (countsOnly) LastFmMatcher.matchCounts(s, t, m, c) else LastFmMatcher.match(s, t, m, c) })
    private suspend fun LastFmController.ready() = withTimeout(3000) { state.first { !it.loading } }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val library = LibrarySnapshot(tracks = listOf(song, other))

    @Test fun codecRoundTripsEveryField() {
        val cache = LastFmMatchCache("lib", "archive", "-", mapOf("song" to 42L), mapOf("song" to "song"), 1, 2, 3)
        assertEquals(cache, LastFmMatchCacheCodec.decode(LastFmMatchCacheCodec.encode(cache)))
        assertNull(LastFmMatchCacheCodec.decode("{\"version\":0}"))
    }

    @Test fun keysChangeOnlyWithMatchingInputs() {
        val key = LastFmMatchKey.of(library, archive, emptyMap())
        // Local play counts and dates never invalidate a match; matching fields do.
        assertEquals(key, LastFmMatchKey.of(library.copy(tracks = listOf(song.copy(playCount = 9), other.copy(addedAt = 5))), archive, emptyMap()))
        assertNotEquals(key, LastFmMatchKey.of(library.copy(tracks = listOf(song.copy(title = "Renamed"), other)), archive, emptyMap()))
        assertNotEquals(key, LastFmMatchKey.of(library, archive.copy(syncedAt = 2000), emptyMap()))
        assertNotEquals(key, LastFmMatchKey.of(library, archive, mapOf(remote.key to "other")))
    }

    @Test fun archivesWithEqualAggregatesButDifferentContentOrDistributionGetDifferentKeys() {
        val base = LastFmMatchKey.archiveKey(archive)
        val renamed = archive.copy(tracks = listOf(remote.copy(title = "Song B"), remoteOther))
        val redistributed = archive.copy(tracks = listOf(remote.copy(plays = 5), remoteOther.copy(plays = 42)))
        val otherAlbum = archive.copy(tracks = listOf(remote.copy(album = "Live"), remoteOther))
        val otherId = archive.copy(tracks = listOf(remote.copy(mbid = "0000"), remoteOther))
        val otherDuration = archive.copy(tracks = listOf(remote.copy(durationMs = 1), remoteOther))
        listOf(renamed, redistributed, otherAlbum, otherId, otherDuration).forEach { variant ->
            assertEquals(variant.tracks.size, archive.tracks.size); assertEquals(variant.tracks.sumOf { it.plays }, archive.tracks.sumOf { it.plays })
            assertNotEquals(base, LastFmMatchKey.archiveKey(variant))
        }
        assertEquals(base, LastFmMatchKey.archiveKey(archive.copy(tracks = archive.tracks.map { it.copy() })))
    }

    @Test fun fieldBoundariesAreFramedSoShiftedDelimitersNeverCollide() {
        // Same byte stream under naive delimiting: artist "A\u0000B" + title "C" versus artist "A" + title "B\u0000C".
        val shifted = archive.copy(tracks = listOf(LastFmTrack("A\u0000B", "C", 42), remoteOther))
        val unshifted = archive.copy(tracks = listOf(LastFmTrack("A", "B\u0000C", 42), remoteOther))
        assertNotEquals(LastFmMatchKey.archiveKey(shifted), LastFmMatchKey.archiveKey(unshifted))
        val localShifted = library.copy(tracks = listOf(song.copy(artist = "A\u0000B", title = "C"), other))
        val localUnshifted = library.copy(tracks = listOf(song.copy(artist = "A", title = "B\u0000C"), other))
        assertNotEquals(LastFmMatchKey.libraryKey(localShifted.tracks), LastFmMatchKey.libraryKey(localUnshifted.tracks))
        assertNotEquals(LastFmMatchKey.mappingsKey(mapOf("A\u0000B" to "C")), LastFmMatchKey.mappingsKey(mapOf("A" to "B\u0000C")))
        assertNotEquals(LastFmMatchKey.libraryKey(listOf(song.copy(artist = null, title = ""))), LastFmMatchKey.libraryKey(listOf(song.copy(artist = "", title = ""))))
        // The two archives really match differently against a library that contains both spellings.
        val both = library.copy(tracks = listOf(song.copy(id = "shifted", uri = "shifted", artist = "A\u0000B", title = "C"),
            song.copy(id = "unshifted", uri = "unshifted", artist = "A", title = "B\u0000C")))
        val first = LastFmMatcher.match(shifted, both.tracks, emptyMap()); val second = LastFmMatcher.match(unshifted, both.tracks, emptyMap())
        assertNotEquals(first.counts, second.counts)
        val key = LastFmMatchKey.of(both, shifted, emptyMap())
        val cache = LastFmMatchCache(key.library, key.archive, key.mappings, first.counts, first.recordingGroups, first.matched, first.notInLibrary, first.dismissed)
        assertTrue(cache.matches(key)); assertFalse("a shifted archive must not reuse the other's counts", cache.matches(LastFmMatchKey.of(both, unshifted, emptyMap())))
    }

    @Test fun restoredCountsEqualAFullMatchForEveryDistribution() {
        listOf(archive, archive.copy(tracks = listOf(remote.copy(plays = 5), remoteOther.copy(plays = 42)))).forEach { variant ->
            val full = LastFmMatcher.match(variant, library.tracks, emptyMap())
            val key = LastFmMatchKey.of(library, variant, emptyMap())
            val restored = LastFmMatchCache(key.library, key.archive, key.mappings, full.counts, full.recordingGroups,
                full.matched, full.notInLibrary, full.dismissed).toMatches(variant)
            assertEquals(full.counts, restored.counts); assertEquals(full.recordingGroups, restored.recordingGroups)
            assertEquals(full.matched, restored.matched); assertFalse(restored.complete); assertSame(variant, restored.sourceSnapshot)
        }
    }

    @Test fun firstMatchIsPersistedAndTheNextLaunchUsesItWithoutMatching() = runBlocking {
        val cache = MemoryCache()
        val scope = scope()
        try {
            WorkCounters.reset()
            val first = controller(flowOf(library), cache, scope)
            withTimeout(3000) { first.overlay.first { it.counts["song"] == 42L } }
            assertEquals(1L, WorkCounters.lastFmMatches.get()); assertEquals(1, cache.writes.size)
            assertEquals(mapOf("song" to 42L, "other" to 5L), requireNotNull(cache.value).counts)
            WorkCounters.reset()
            val second = controller(flowOf(library), cache, scope)
            withTimeout(3000) { second.overlay.first { it.counts["song"] == 42L } }
            assertEquals("A cached result must not match again", 0L, WorkCounters.lastFmMatches.get())
            assertFalse(second.counts.value.complete)
            assertNull("matches carries only complete results", second.matches.value.sourceSnapshot)
            assertEquals(mapOf("song" to 42L, "other" to 5L),
                withTimeout(3000) { second.applyTo(flowOf(ListeningStatistics())).first { it.tracks.isNotEmpty() } }.tracks)
            // Review lists are demanded explicitly; the match then completes once and stays complete after the demand ends.
            val complete = withTimeout(3000) { second.details.first { it.complete } }
            assertEquals(2, complete.matched); assertEquals(1L, WorkCounters.lastFmMatches.get())
            assertSame(complete, second.matches.value)
            withTimeout(3000) { second.details.first { it.complete } }
            assertEquals(1L, WorkCounters.lastFmMatches.get())
        } finally { scope.cancel() }
    }

    @Test fun aSwitchedOffFeatureUsesTheCacheTooAndKeepsLocalCounts() = runBlocking {
        val cache = MemoryCache()
        val scope = scope()
        try {
            val warm = controller(flowOf(library), cache, scope)
            withTimeout(3000) { warm.overlay.first { it.counts["song"] == 42L } }
            WorkCounters.reset()
            val off = controller(flowOf(library), cache, scope, Store(saved(active = false)))
            withTimeout(3000) { off.overlay.first { it.counts["song"] == 42L } }
            assertEquals(0L, WorkCounters.lastFmMatches.get())
            val local = ListeningStatistics(mapOf("song" to 7L))
            assertEquals(7L, withTimeout(3000) { off.applyTo(flowOf(local)).first() }.tracks["song"])
        } finally { scope.cancel() }
    }

    /** The #753 summary collector observes `matches` for the controller's lifetime; it must neither force a match nor see partial buckets. */
    @Test fun aPermanentBackgroundObserverNeverStartsMatchingOrReadsPartialBuckets() = runBlocking {
        for (observerFirst in listOf(true, false)) {
            val cache = MemoryCache()
            val scope = scope()
            try {
                controller(flowOf(library), cache, scope).let { withTimeout(3000) { it.overlay.first { o -> o.counts["song"] == 42L } } }
                WorkCounters.reset()
                val controller = controller(flowOf(library), cache, scope)
                val summaries = mutableListOf<Int>()
                fun observe() = scope.launch {
                    combine(controller.state.map { it.saved.snapshot }.distinctUntilChanged(), controller.matches) { snapshot, matched -> snapshot to matched }
                        .collect { (snapshot, matched) -> if (snapshot != null && matched.sourceSnapshot === snapshot) summaries += matched.resolutions.size }
                }
                if (observerFirst) observe()
                controller.ready()
                withTimeout(3000) { controller.counts.first { it.counts["song"] == 42L } }
                if (!observerFirst) observe()
                delay(50)
                assertEquals("observer must not force a match", 0L, WorkCounters.lastFmMatches.get())
                assertTrue("no partial bucket summary", summaries.isEmpty())
                withTimeout(3000) { controller.details.first { it.complete } }
                withTimeout(3000) { controller.matches.first { it.complete && it.sourceSnapshot != null } }
                delay(20)
                assertEquals(listOf(2), summaries.distinct()); assertEquals(1L, WorkCounters.lastFmMatches.get())
            } finally { scope.cancel() }
        }
    }

    @Test fun aNewerMappingCancelsTheRunningMatchAndOnlyTheLatestResultIsPublishedAndPersisted() = runBlocking {
        val cache = MemoryCache()
        val scope = scope()
        try {
            controller(flowOf(library), cache, scope).let { withTimeout(3000) { it.overlay.first { o -> o.counts["song"] == 42L } } }
            val gate = Gate()
            val store = Store(saved())
            val controller = controller(flowOf(library), cache, scope, store, gate)
            withTimeout(3000) { controller.counts.first { it.counts["song"] == 42L } }
            assertEquals(0, gate.invocations)
            val demand = scope.launch { controller.details.collect {} }
            withTimeout(3000) { while (gate.invocations < 1) delay(5) }
            cache.writes.clear()
            controller.map(remote.key, "other") // A newer input arrives while the first full match is blocked.
            withTimeout(3000) { controller.state.first { it.saved.mappings.isNotEmpty() } }
            withTimeout(3000) { while (gate.invocations < 2) delay(5) }
            gate.latch.countDown()
            val result = withTimeout(3000) { controller.matches.first { it.complete && it.sourceSnapshot != null } }
            assertEquals("manual choice applied by the latest input", 47L, result.counts["other"])
            assertNull(result.counts["song"])
            assertEquals(1, cache.writes.size)
            assertEquals(LastFmMatchKey.mappingsKey(mapOf(remote.key to "other")), requireNotNull(cache.value).mappingsKey)
            demand.cancel()
        } finally { scope.cancel() }
    }

    @Test fun deletingTheArchiveDuringAMatchPublishesEmptyAndClearsTheCache() = runBlocking {
        val cache = MemoryCache()
        val scope = scope()
        try {
            controller(flowOf(library), cache, scope).let { withTimeout(3000) { it.overlay.first { o -> o.counts["song"] == 42L } } }
            val gate = Gate()
            val controller = controller(flowOf(library), cache, scope, Store(saved()), gate)
            withTimeout(3000) { controller.counts.first { it.counts["song"] == 42L } }
            cache.writes.clear()
            val demand = scope.launch { controller.details.collect {} }
            withTimeout(3000) { while (gate.invocations < 1) delay(5) }
            controller.deleteSyncedData()
            withTimeout(3000) { controller.state.first { it.saved.snapshot == null } }
            gate.latch.countDown()
            withTimeout(3000) { controller.counts.first { it.counts.isEmpty() } }
            delay(50)
            assertTrue(controller.matches.value.counts.isEmpty()); assertNull(cache.value)
            assertEquals(listOf(null), cache.writes)
            demand.cancel()
        } finally { scope.cancel() }
    }

    @Test fun leavingTheDetailViewCancelsTheMatchWithoutPoisoningLaterWork() = runBlocking {
        val cache = MemoryCache()
        val scope = scope()
        try {
            controller(flowOf(library), cache, scope).let { withTimeout(3000) { it.overlay.first { o -> o.counts["song"] == 42L } } }
            val gate = Gate()
            val controller = controller(flowOf(library), cache, scope, Store(saved()), gate)
            withTimeout(3000) { controller.counts.first { it.counts["song"] == 42L } }
            val demand = scope.launch { controller.details.collect {} }
            withTimeout(3000) { while (gate.invocations < 1) delay(5) }
            demand.cancel(); delay(50)
            gate.latch.countDown(); delay(50)
            assertNull("cancelled work must not publish", controller.matches.value.sourceSnapshot)
            assertEquals(42L, controller.counts.value.counts["song"])
            val complete = withTimeout(3000) { controller.details.first { it.complete } }
            assertEquals(2, complete.matched); assertEquals(2, gate.invocations)
        } finally { scope.cancel() }
    }

    @Test fun aChangedLibraryMatchesAgainAndRefreshesTheCache() = runBlocking {
        val cache = MemoryCache()
        val library = MutableStateFlow(LibrarySnapshot(tracks = listOf(song)))
        val scope = scope()
        try {
            WorkCounters.reset()
            val controller = controller(library, cache, scope)
            withTimeout(3000) { controller.overlay.first { it.counts["song"] == 42L } }
            val stale = requireNotNull(cache.value)
            library.value = LibrarySnapshot(tracks = listOf(song.copy(title = "Renamed"), other))
            withTimeout(3000) { controller.overlay.first { it.counts["song"] == null && it.counts["other"] == 5L } }
            assertEquals(2L, WorkCounters.lastFmMatches.get())
            assertNotEquals(stale.libraryKey, requireNotNull(cache.value).libraryKey)
        } finally { scope.cancel() }
    }
}

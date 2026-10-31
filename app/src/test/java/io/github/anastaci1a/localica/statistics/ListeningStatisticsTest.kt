package io.github.anastaci1a.localica.statistics

import io.github.anastaci1a.localica.library.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ListeningStatisticsTest {
    private fun track(index: Int, count: Long = 0) =
        Track("content://fixture/$index", "Song $index", fileName = "$index.mp3",
            relativePath = "$index.mp3", playCount = count)

    @Test fun unchangedThirteenThousandTracksRetainTheEntireSnapshot() {
        val snapshot = LibrarySnapshot(tracks = List(13_000) { track(it) })
        assertSame(snapshot, ListeningStatistics().applyTo(snapshot))
        val counts = mapOf(snapshot.tracks.last().id to 3L)
        val populated = ListeningStatistics(counts, mapOf("playlist" to 2L)).applyTo(snapshot)
        assertSame(populated, ListeningStatistics(counts.toMutableMap(),
            mutableMapOf("playlist" to 2L)).applyTo(populated))
    }

    @Test fun oneChangedCountCopiesOnlyThatTrackWithoutMutatingTheSource() {
        val source = LibrarySearchIndexBuilder().prepare(LibrarySnapshot(
            folders = listOf(FolderSelection("content://root", "Music")),
            tracks = List(13_000) { track(it) },
            metadataFailures = listOf(MetadataFailure("missing", "content://root", "bad.mp3"))))
        val target = source.tracks.last()
        val counts = mapOf(target.id to 7L)
        val result = ListeningStatistics(counts).applyTo(source)

        assertNotSame(source.tracks, result.tracks)
        source.tracks.dropLast(1).forEachIndexed { index, track -> assertSame(track, result.tracks[index]) }
        assertEquals(target.copy(playCount = 7), result.tracks.last())
        assertEquals(0L, target.playCount)
        assertSame(source.folders, result.folders)
        assertSame(source.metadataFailures, result.metadataFailures)
        assertSame(source.searchIndex, result.searchIndex)
        assertSame(counts, result.trackPlays)
    }

    @Test fun repeatedRawSnapshotsReuseOnlyTracksWhoseStoredCountAlreadyMatches() {
        val source = LibrarySnapshot(tracks = List(13_000) { track(it) })
        val counts = source.tracks.filterIndexed { index, _ -> index % 10 == 0 }.associate { it.id to 4L }
        val statistics = ListeningStatistics(counts)
        val first = statistics.applyTo(source)
        val second = statistics.applyTo(source)
        source.tracks.forEachIndexed { index, track ->
            if (index % 10 == 0) {
                assertEquals(4L, second.tracks[index].playCount)
                assertNotSame(track, second.tracks[index])
                // Room supplies zero counts: this is source reuse, not cross-emission memoization.
                assertNotSame(first.tracks[index], second.tracks[index])
            } else assertSame(track, second.tracks[index])
        }
    }

    @Test fun removedCountsResetToZeroWhileUnchangedTracksKeepTheirIdentity() {
        val first = track(0, 4)
        val second = track(1, 8)
        val source = LibrarySnapshot(tracks = listOf(first, second),
            trackPlays = mapOf(first.id to 4L, second.id to 8L))
        val result = ListeningStatistics(mapOf(second.id to 8L)).applyTo(source)
        assertEquals(first.copy(playCount = 0), result.tracks.first())
        assertSame(second, result.tracks.last())
        assertEquals(mapOf(second.id to 8L), result.trackPlays)
        assertEquals(4L, source.tracks.first().playCount)
    }

    @Test fun mapOnlyChangesPreserveTrackListAndKeepMissingTrackAndPlaylistCounts() {
        val source = LibrarySnapshot(tracks = listOf(track(0)))
        val counts = mapOf("not-in-library" to 6L)
        val playlists = mapOf("playlist" to 9L)
        val result = ListeningStatistics(counts, playlists).applyTo(source)
        assertNotSame(source, result)
        assertSame(source.tracks, result.tracks)
        assertSame(counts, result.trackPlays)
        assertSame(playlists, result.playlistPlays)
        val reset = ListeningStatistics().applyTo(result)
        assertSame(result.tracks, reset.tracks)
        assertTrue(reset.trackPlays.isEmpty())
        assertTrue(reset.playlistPlays.isEmpty())
    }

    @Test fun statisticsPreparationAndOverlayRunOffTheCollectorThread() = runBlocking {
        val collector = Thread.currentThread()
        val sourceTracks = listOf(track(0))
        var overlayThread: Thread? = null
        val checkedTracks = object : AbstractList<Track>() {
            override val size get() = sourceTracks.size
            override fun get(index: Int): Track {
                overlayThread = Thread.currentThread()
                assertNotSame(collector, overlayThread)
                return sourceTracks[index]
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val result = flowOf(LibrarySnapshot(tracks = checkedTracks)).withListeningStatistics(flow {
                assertNotSame(collector, Thread.currentThread())
                emit(ListeningStatistics())
            }, dispatcher).first()
            assertSame(collector, Thread.currentThread())
            assertNotNull(overlayThread)
            assertSame(checkedTracks, result.tracks)
        }
    }

    @Test fun publishedSnapshotCarriesItsTrackLookupPreparedOffTheCollector() = runBlocking {
        val collector = Thread.currentThread()
        val sourceTracks = List(3) { track(it) }
        val checkedTracks = object : AbstractList<Track>() {
            override val size get() = sourceTracks.size
            override fun get(index: Int): Track {
                assertNotSame("The ID lookup must not be built on the collector", collector, Thread.currentThread())
                return sourceTracks[index]
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val result = flowOf(LibrarySnapshot(tracks = checkedTracks))
                .withListeningStatistics(flowOf(ListeningStatistics()), dispatcher).first()
            assertSame(collector, Thread.currentThread())
            assertSame(checkedTracks, result.tracks)
            // A synchronous UI projection reads the prepared lookup without touching the track list.
            assertSame(sourceTracks[1], result.tracksById.getValue(sourceTracks[1].id))
            assertEquals(sourceTracks.map { it.id }.toSet(), result.tracksById.keys)
        }
    }

    @Test fun collectionCancellationReleasesBothUpstreams() = runBlocking {
        val libraryStopped = CompletableDeferred<Unit>()
        val statisticsStopped = CompletableDeferred<Unit>()
        val snapshots = flow {
            try { emit(LibrarySnapshot()); awaitCancellation() }
            finally { libraryStopped.complete(Unit) }
        }
        val statistics = flow {
            try { emit(ListeningStatistics()); awaitCancellation() }
            finally { statisticsStopped.complete(Unit) }
        }
        withTimeout(5000) {
            snapshots.withListeningStatistics(statistics).first()
            libraryStopped.await()
            statisticsStopped.await()
        }
    }

    @Test fun upstreamFailureStillReachesTheCollector() = runBlocking {
        val failure = IllegalStateException("statistics unavailable")
        try {
            flowOf(LibrarySnapshot()).withListeningStatistics(flow { throw failure }).first()
            fail("The UI must be able to publish its existing load-failed state")
        } catch (caught: IllegalStateException) {
            // Coroutine stack-trace recovery may copy the exception across dispatchers.
            assertEquals(failure.message, caught.message)
        }
    }
}

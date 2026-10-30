package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.statistics.ListeningStatistics
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StatisticsGroupPreparationTest {
    private val original = listOf(
        Track("first", "Same", artist = " Artist ", album = " Album ", albumArtist = "Various",
            fileName = "a.mp3", relativePath = "a.mp3", id = "duplicate", durationMs = 10,
            locations = listOf(TrackLocation("root", "root/a", "dir/a.mp3"), TrackLocation("child", "child/a", "a.mp3"))),
        Track("second", "Same", artist = " Artist ", album = " Album ", albumArtist = "Various",
            fileName = "b.mp3", relativePath = "b.mp3", id = "duplicate", durationMs = 20,
            locations = listOf(TrackLocation("root", "root/b", "dir/b.mp3"))),
        Track("other", "Other", artist = "Other", album = "Other", fileName = "c.mp3", relativePath = "c.mp3",
            locations = listOf(TrackLocation("root", "root/c", "dir/c.mp3"))),
        Track("unknown", "Unknown", fileName = "d.mp3", relativePath = "d.mp3")
    )
    private fun source(tracks: List<Track> = original) = LibrarySnapshot(
        folders = listOf(FolderSelection("root", "Music"), FolderSelection("child", "Child")), tracks = tracks)
    private class Reads(private val values: List<Track>) : AbstractList<Track>() {
        var gets = 0
        override val size get() = values.size
        override fun get(index: Int): Track { gets++; return values[index] }
    }

    @Test fun allViewsQueriesAndCountUpdatesMatchFrozenGroupingWithDuplicateOccurrences() {
        val base = source()
        val snapshots = listOf(base, ListeningStatistics(mapOf("duplicate" to 9, "other" to 3)).applyTo(base),
            ListeningStatistics(mapOf("other" to 12)).applyTo(base))
        for (snapshot in snapshots) {
            val preparation = LibraryViews.Preparation(snapshot) {}
            for (view in SongListView.entries) for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
                for (query in listOf(LibraryQuery(sort = sort, ascending = ascending),
                    LibraryQuery(text = "same", folderUri = "root", sort = sort, ascending = ascending))) {
                    assertEquals("$view / $query", OriginalStatisticsGroups.groups(snapshot, view, query), preparation.groups(view, query))
                }
            }
        }
    }

    @Test fun overlayReusesMetadataPreparationButReturnsCurrentTracksAndFolderAccessPaths() {
        val reads = Reads(original)
        val base = source(reads)
        val query = LibraryViews.defaults(SongListView.Albums)
        LibraryViews.Preparation(base) {}.groups(SongListView.Albums, query)
        val current = ListeningStatistics(mapOf("duplicate" to 9)).applyTo(base)
        val before = reads.gets
        val result = LibraryViews.Preparation(current) {}.groups(SongListView.Albums, query)
        assertEquals("Cached grouping must not traverse base metadata again", before, reads.gets)
        val duplicate = result.flatMap { it.tracks }.single { it.id == "duplicate" }
        assertSame(current.tracks.first(), duplicate)
        assertEquals(9L, duplicate.playCount)
        assertEquals(10L, duplicate.durationMs)
        assertEquals("second", current.tracksById.getValue("duplicate").uri)
        val folderQuery = LibraryViews.defaults(SongListView.Folders)
        LibraryViews.Preparation(base) {}.groups(SongListView.Folders, folderQuery)
        val countBeforeFolders = reads.gets
        val folders = LibraryViews.Preparation(current) {}.groups(SongListView.Folders, folderQuery)
        assertEquals(countBeforeFolders, reads.gets)
        assertEquals(OriginalStatisticsGroups.groups(current, SongListView.Folders, folderQuery), folders)
        val firstCopies = folders.flatMap { it.tracks }.filter { it.id == "duplicate" }
        assertEquals(setOf("root/a", "child/a"), firstCopies.map { it.uri }.toSet())
        assertTrue(firstCopies.all { it.playCount == 9L && it.durationMs == 10L })
    }

    @Test fun arbitraryCopiesResetPreparationIncludingNonsearchMetadataAndLocations() {
        val base = LibrarySearchIndexBuilder().prepare(source())
        val overlay = ListeningStatistics(mapOf("duplicate" to 4)).applyTo(base)
        val query = LibraryViews.defaults(SongListView.Folders)
        LibraryViews.Preparation(overlay) {}.groups(SongListView.Folders, query)
        val changed = overlay.copy(tracks = overlay.tracks.map {
            it.copy(durationMs = 9876, modifiedAt = 77, locations = listOf(TrackLocation("new", "new/${it.uri}", "fresh/${it.fileName}")))
        }, folders = listOf(FolderSelection("new", "Renamed")))
        assertSame(overlay.searchIndex, changed.searchIndex)
        assertSame(changed, changed.groupingSource)
        assertSame(base, overlay.groupingSource)
        val freshOverlay = ListeningStatistics(mapOf("duplicate" to 8)).applyTo(changed)
        assertSame(changed, freshOverlay.groupingSource)
        val result = LibraryViews.Preparation(freshOverlay) {}.groups(SongListView.Folders, query)
        assertEquals(OriginalStatisticsGroups.groups(freshOverlay, SongListView.Folders, query), result)
        assertTrue(result.flatMap { it.tracks }.all { it.durationMs == 9876L && it.modifiedAt == 77L && it.uri.startsWith("new/") })
        val ordinaryCopy = overlay.copy()
        assertSame(ordinaryCopy, ordinaryCopy.groupingSource)
    }

    @Test fun chainedOverlaysAndMapOnlyUpdatesRetainBaseButMostPlayedUsesCurrentOrder() {
        val base = source()
        val first = ListeningStatistics(mapOf("duplicate" to 2)).applyTo(base)
        val second = ListeningStatistics(mapOf("other" to 20), mapOf("playlist" to 11)).applyTo(first)
        val mapOnly = ListeningStatistics(second.trackPlays, mapOf("playlist" to 12)).applyTo(second)
        assertSame(base, second.groupingSource)
        assertSame(base, mapOnly.groupingSource)
        assertSame(second.tracks, mapOnly.tracks)
        for (snapshot in listOf(first, second, mapOnly)) for (ascending in listOf(true, false)) {
            val query = LibraryQuery(sort = TrackSort.MostPlayed, ascending = ascending)
            assertEquals(OriginalStatisticsGroups.groups(snapshot, SongListView.Folders, query),
                LibraryViews.Preparation(snapshot) {}.groups(SongListView.Folders, query))
        }
        assertTrue(second.tracks.filter { it.id == "duplicate" }.all { it.playCount == 0L })
    }

    @Test fun cancelledColdAndWarmWorkNeverReturnsPartialGroupsAndRetryRemainsFresh() {
        val base = source()
        val current = ListeningStatistics(mapOf("duplicate" to 5)).applyTo(base)
        val query = LibraryViews.defaults(SongListView.Albums)
        var total = 0
        LibraryViews.Preparation(current) { total++ }.groups(SongListView.Albums, query)
        for (stop in 1..total) {
            val cold = ListeningStatistics(mapOf("duplicate" to 5)).applyTo(source())
            var checks = 0
            try {
                LibraryViews.Preparation(cold) { if (++checks == stop) throw CancellationException("stop") }
                    .groups(SongListView.Albums, query)
                fail("Cold preparation ignored checkpoint $stop")
            } catch (expected: CancellationException) { assertEquals("stop", expected.message) }
            assertEquals(OriginalStatisticsGroups.groups(cold, SongListView.Albums, query),
                LibraryViews.Preparation(cold) {}.groups(SongListView.Albums, query))
        }
        try {
            LibraryViews.Preparation(current) { throw CancellationException("warm") }.groups(SongListView.Albums, query)
            fail("Warm preparation ignored cancellation")
        } catch (expected: CancellationException) { assertEquals("warm", expected.message) }
    }

    @Test fun preparationEvictionRecomputesWithoutChangingResultsOrOtherMetadataSources() {
        val reads = Reads(original)
        val base = source(reads)
        val first = LibraryQuery(text = "same")
        val prepared = LibraryViews.Preparation(base) {}
        val expected = OriginalStatisticsGroups.groups(source(), SongListView.Albums, first)
        assertEquals(expected, prepared.groups(SongListView.Albums, first))
        val beforeWarm = reads.gets
        assertEquals(expected, prepared.groups(SongListView.Albums, first))
        assertEquals(beforeWarm, reads.gets)
        for (index in 1..13) prepared.groups(SongListView.Albums, LibraryQuery(text = "missing-$index"))
        val beforeEvicted = reads.gets
        assertEquals(expected, prepared.groups(SongListView.Albums, first))
        assertTrue(reads.gets > beforeEvicted)
        val other = source(original.map { it.copy(album = "Replacement") })
        LibraryViews.Preparation(other) {}.groups(SongListView.Albums, first)
        val afterOther = reads.gets
        assertEquals(expected, prepared.groups(SongListView.Albums, first))
        assertEquals(afterOther, reads.gets)
    }

    @Test fun oversizedMembershipPlansAreRecomputedWithCompleteCurrentCounts() {
        val values = List(40_001) { index ->
            Track("large/$index", "Song $index", album = "Large album", fileName = "$index.mp3",
                relativePath = "$index.mp3", id = "large:$index")
        }
        val reads = Reads(values)
        val base = source(reads)
        val current = ListeningStatistics(mapOf(values.first().id to 7L, values.last().id to 9L)).applyTo(base)
        val query = LibraryQuery(sort = TrackSort.Manual)
        val expectedIds = values.map { it.id }
        repeat(2) {
            val before = reads.gets
            val groups = LibraryViews.Preparation(current) {}.groups(SongListView.Albums, query)
            assertTrue("An oversized plan must read source metadata again on every request", reads.gets > before)
            val group = groups.single()
            assertEquals("Large album", group.title)
            assertEquals(expectedIds, group.tracks.map { it.id })
            assertEquals(current.tracks, group.tracks)
            group.tracks.forEachIndexed { index, track -> assertSame(current.tracks[index], track) }
            assertEquals(7L, group.tracks.first().playCount)
            assertEquals(9L, group.tracks.last().playCount)
            assertEquals(16L, group.tracks.sumOf { it.playCount })
        }
        assertTrue("Overlay must leave the metadata source unchanged", values.all { it.playCount == 0L })
    }

    @Test fun oldAndNewMetadataPreparationsRemainIndependentAcrossWorkerCompletionOrder() {
        val oldReads = Reads(original)
        val oldSource = source(oldReads)
        val oldCurrent = ListeningStatistics(mapOf("duplicate" to 4L)).applyTo(oldSource)
        val newValues = original.map { track ->
            track.copy(album = "New album", durationMs = 9876L,
                locations = listOf(TrackLocation("new", "new/${track.uri}", "fresh/${track.fileName}")))
        }
        val newReads = Reads(newValues)
        val newSource = LibrarySnapshot(folders = listOf(FolderSelection("new", "New root")), tracks = newReads)
        val newCurrent = ListeningStatistics(mapOf("duplicate" to 8L, "other" to 12L)).applyTo(newSource)
        val albumQuery = LibraryViews.defaults(SongListView.Albums)
        val folderQuery = LibraryViews.defaults(SongListView.Folders)
        fun groups(snapshot: LibrarySnapshot, checkActive: () -> Unit = {}): Pair<List<LibraryGroup>, List<LibraryGroup>> {
            val preparation = LibraryViews.Preparation(snapshot, checkActive)
            return preparation.groups(SongListView.Albums, albumQuery) to
                preparation.groups(SongListView.Folders, folderQuery)
        }
        fun expected(snapshot: LibrarySnapshot) =
            OriginalStatisticsGroups.groups(snapshot, SongListView.Albums, albumQuery) to
                OriginalStatisticsGroups.groups(snapshot, SongListView.Folders, folderQuery)
        val oldExpected = expected(oldCurrent)
        val newExpected = expected(newCurrent)
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val oldResult = workers.submit<Pair<List<LibraryGroup>, List<LibraryGroup>>> {
                var held = false
                groups(oldCurrent) {
                    if (!held) {
                        held = true
                        oldEntered.countDown()
                        assertTrue("Old worker release is bounded", releaseOld.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue("Old source starts first", oldEntered.await(10, TimeUnit.SECONDS))
            val newResult = workers.submit<Pair<List<LibraryGroup>, List<LibraryGroup>>> { groups(newCurrent) }
            assertEquals(newExpected, newResult.get(10, TimeUnit.SECONDS))
            assertFalse("Old source remains held until the new source completes", oldResult.isDone)
            releaseOld.countDown()
            assertEquals(oldExpected, oldResult.get(10, TimeUnit.SECONDS))
            val oldBeforeWarm = oldReads.gets
            val newBeforeWarm = newReads.gets
            assertEquals(newExpected, groups(newCurrent))
            assertEquals(oldExpected, groups(oldCurrent))
            assertEquals("Old completion must not evict the new metadata source", newBeforeWarm, newReads.gets)
            assertEquals("New preparation must not contaminate the old metadata source", oldBeforeWarm, oldReads.gets)
        } finally {
            releaseOld.countDown()
            workers.shutdown()
            assertTrue("All released workers finish before fixture cleanup", workers.awaitTermination(15, TimeUnit.SECONDS))
        }
    }
}

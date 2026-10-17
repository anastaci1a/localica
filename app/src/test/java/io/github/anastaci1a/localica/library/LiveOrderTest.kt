package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.playlist.PlaylistEdits
import io.github.anastaci1a.localica.queue.JourneyModes
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEdits
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.queue.QueueJourney
import io.github.anastaci1a.localica.queue.QueueVisit
import io.github.anastaci1a.localica.queue.SongTap
import org.junit.Assert.*
import org.junit.Test

class LiveOrderTest {
    private fun track(id: String, title: String = id, playCount: Long = 0) = Track(
        uri = "saved:$id", id = id, title = title, fileName = "$id.mp3", relativePath = "$id.mp3",
        playCount = playCount,
    )

    private fun row(id: String, track: Track, origin: String? = null) = SongRow(id, track, origin)

    @Test fun titleDriftMakesTheSameSavedSortApplicableAgainUsingTrackIdentity() {
        val rows = listOf(row("first", track("a", "Alpha")), row("second", track("b", "Beta")))
        val order = SavedOrder(rows.map { it.id }, TrackSort.Title)
        val original = LiveOrder(LibrarySnapshot(tracks = rows.map { it.track }))
        assertTrue(original.isApplied(order, rows, TrackSort.Title, true))

        // URI and occurrence identity differ from the stable library track identity.
        val live = LiveOrder(LibrarySnapshot(tracks = listOf(
            rows[0].track.copy(uri = "current:a", title = "Zulu"),
            rows[1].track.copy(uri = "current:b", title = "Alpha"),
        )))
        assertEquals(listOf("second", "first"), live.target(order, rows, TrackSort.Title, true))
        assertFalse(live.isApplied(order, rows, TrackSort.Title, true))
        assertTrue(live.isApplied(order, rows.reversed(), TrackSort.Title, true))
    }

    @Test fun playCountDriftUsesLiveAvailableTrackCountsInsteadOfSavedCounts() {
        val rows = listOf(row("first", track("a", playCount = 20)), row("second", track("b", playCount = 1)))
        val order = SavedOrder(rows.map { it.id }, TrackSort.MostPlayed, false)
        val live = LiveOrder(LibrarySnapshot(tracks = listOf(
            rows[0].track.copy(playCount = 2), rows[1].track.copy(playCount = 12),
        )))

        assertEquals(listOf("second", "first"), live.target(order, rows, TrackSort.MostPlayed, false))
        assertFalse(live.isApplied(order, rows, TrackSort.MostPlayed, false))
        assertTrue(live.isApplied(order, rows.reversed(), TrackSort.MostPlayed, false))
        assertEquals(listOf(20L, 1L), rows.map { it.track.playCount })
    }

    @Test fun unchangedPhysicalOrderStillAllowsSavingAnotherSortChoiceOrDirection() {
        val rows = listOf(
            row("first", track("a", "Same").copy(artist = "Artist")),
            row("second", track("b", "Same").copy(artist = "Artist")),
        )
        val order = SavedOrder(rows.map { it.id }, TrackSort.Title)
        val live = LiveOrder(LibrarySnapshot(tracks = rows.map { it.track }))

        assertTrue(live.isApplied(order, rows, TrackSort.Title, true))
        assertEquals(rows.map { it.id }, live.target(order, rows, TrackSort.Artist, true))
        assertFalse(live.isApplied(order, rows, TrackSort.Artist, true))
        assertEquals(rows.map { it.id }, live.target(order, rows, TrackSort.Title, false))
        assertFalse(live.isApplied(order, rows, TrackSort.Title, false))

        val playlist = ManualPlaylist(name = "Mix", entries = rows, order = order)
        val sorted = PlaylistEdits.sort(playlist, TrackSort.Title, false, LibrarySnapshot(tracks = rows.map { it.track }))
        assertEquals(rows, sorted.entries)
        assertEquals(order.copy(ascending = false), sorted.order)
        assertTrue(live.isApplied(sorted.order, sorted.entries, TrackSort.Title, false))
    }

    @Test fun unavailableTracksRetainSavedMetadataButUseCurrentCountsOrZero() {
        val unavailable = row("unavailable", track("missing", "Alpha", playCount = 90))
        val available = row("available", track("indexed", "Zulu", playCount = 1))
        val uncounted = row("uncounted", track("uncounted", "Charlie", playCount = 100))
        val rows = listOf(unavailable, available, uncounted)
        val order = SavedOrder(rows.map { it.id })
        val live = LiveOrder(LibrarySnapshot(
            tracks = listOf(available.track.copy(title = "Beta", playCount = 5)),
            trackPlays = mapOf("missing" to 2L),
        ))

        assertEquals(listOf("unavailable", "available", "uncounted"), live.target(order, rows, TrackSort.Title, true))
        assertEquals(listOf("uncounted", "unavailable", "available"), live.target(order, rows, TrackSort.MostPlayed, true))
        assertEquals(listOf("available", "unavailable", "uncounted"), live.target(order, rows, TrackSort.MostPlayed, false))
        assertEquals(listOf(90L, 1L, 100L), rows.map { it.track.playCount })
    }

    @Test fun duplicateOccurrencesHaveStableTiesAfterResolvingDifferentSavedSnapshots() {
        val rows = listOf(
            row("duplicate-first", track("same", "Zulu"), "first-playlist"),
            row("other", track("other", "Middle")),
            row("duplicate-second", track("same", "Alpha"), "second-playlist"),
        )
        val order = SavedOrder(rows.map { it.id })
        val snapshot = LibrarySnapshot(tracks = listOf(track("same", "Alpha", 3), track("other", "Zulu", 8)))
        val live = LiveOrder(snapshot)

        assertEquals(listOf("duplicate-first", "duplicate-second", "other"), live.target(order, rows, TrackSort.Title, true))
        assertEquals(listOf("other", "duplicate-first", "duplicate-second"), live.target(order, rows, TrackSort.Title, false))
        assertEquals(listOf("duplicate-first", "duplicate-second", "other"), live.target(order, rows, TrackSort.MostPlayed, true))

        val sorted = PlaylistEdits.sort(ManualPlaylist(name = "Mix", entries = rows), TrackSort.Title, true, snapshot)
        assertEquals(live.target(order, rows, TrackSort.Title, true), sorted.entries.map { it.id })
        sorted.entries.forEach { entry -> assertSame(rows.single { it.id == entry.id }, entry) }
        assertEquals(listOf("first-playlist", "second-playlist", null), sorted.entries.map { it.sourcePlaylistId })
    }

    @Test fun playlistAdditionsMakeSavedSortStaleWithoutReplacingManualHistory() {
        val rows = listOf(row("alpha", track("a", "Alpha")), row("charlie", track("c", "Charlie")))
        val order = SavedOrder(rows.map { it.id }, TrackSort.Title)
        val appended = PlaylistEdits.append(ManualPlaylist(name = "Mix", entries = rows, order = order), listOf(track("b", "Zulu")))
        val added = appended.entries.last()
        val snapshot = LibrarySnapshot(tracks = rows.map { it.track } + added.track.copy(title = "Beta"))
        val live = LiveOrder(snapshot)

        assertFalse(live.isApplied(appended.order, appended.entries, TrackSort.Title, true))
        val sorted = PlaylistEdits.sort(appended, TrackSort.Title, true, snapshot)
        assertEquals(listOf("alpha", added.id, "charlie"), sorted.entries.map { it.id })
        assertTrue(live.isApplied(sorted.order, sorted.entries, TrackSort.Title, true))
        assertEquals(appended.order.manual, sorted.order.manual)
        assertEquals(appended.entries, PlaylistEdits.sort(sorted, TrackSort.Manual, true, snapshot).entries)

        val removed = PlaylistEdits.remove(sorted, setOf("charlie"))
        val restored = PlaylistEdits.sort(removed, TrackSort.Manual, true, snapshot)
        assertEquals(listOf(rows[0], added), restored.entries)
        assertEquals(restored.entries.map { it.id }, restored.order.manual)
        assertEquals("Zulu", restored.entries.last().track.title)
    }

    @Test fun queueAdditionsMakeSavedSortStaleWithoutReplacingManualHistory() {
        val entries = listOf(QueueEntry("alpha", track("a", "Alpha")), QueueEntry("charlie", track("c", "Charlie")))
        val order = SavedOrder(entries.map { it.id }, TrackSort.Title)
        val queue = ListeningQueue(name = "Mix", entries = entries, order = order)
        val appended = QueueEdits.insert(queue, track("b", "Zulu"), SongTap.Append)
        val added = appended.entries.last()
        val rows = appended.entries.map { SongRow(it.id, it.track, it.sourcePlaylistId) }
        val snapshot = LibrarySnapshot(tracks = entries.map { it.track } + added.track.copy(title = "Beta"))

        assertFalse(LiveOrder(snapshot).isApplied(appended.order, rows, TrackSort.Title, true))
        val sorted = QueueEdits.sort(appended, TrackSort.Title, true, snapshot)
        assertEquals(listOf("alpha", added.id, "charlie"), sorted.entries.map { it.id })
        assertEquals(appended.order.manual, sorted.order.manual)
        assertEquals(appended.entries, QueueEdits.sort(sorted, TrackSort.Manual, true, snapshot).entries)
    }

    @Test fun queueLiveSortPreservesSnapshotsCursorPositionAndCompleteJourney() {
        val entries = listOf(
            QueueEntry("first", track("a", "Alpha", 99), "origin-a"),
            QueueEntry("second", track("b", "Beta", 0), "origin-b"),
            QueueEntry("duplicate", track("a", "Older Alpha", 1), "origin-c"),
        )
        val ids = entries.map { it.id }
        val journey = QueueJourney(
            history = listOf(QueueVisit("first", 1, "visit-1"), QueueVisit("duplicate", 1, "visit-2"),
                QueueVisit("second", 1, "visit-3")),
            cursor = 1,
            future = listOf(QueueVisit("first", 2, "visit-4"), QueueVisit("duplicate", 2, "visit-5")),
            cycle = 1,
            visited = ids.toSet(),
            plans = mapOf(1L to ids, 2L to ids.reversed()),
            modes = JourneyModes(shuffle = true, loop = true, reshuffle = true),
        )
        val queue = ListeningQueue(name = "Mix", entries = entries, currentEntryId = "duplicate",
            positionMs = 12_345, journey = journey, lastActivatedAt = 42)
        val snapshot = LibrarySnapshot(tracks = listOf(
            track("a", "Zulu", 2).copy(uri = "current:a"), track("b", "Alpha", 8).copy(uri = "current:b"),
        ))

        val sorted = QueueEdits.sort(queue, TrackSort.MostPlayed, false, snapshot)
        assertEquals(listOf("second", "first", "duplicate"), sorted.entries.map { it.id })
        assertEquals(queue.currentEntryId, sorted.currentEntryId)
        assertEquals(queue.positionMs, sorted.positionMs)
        assertEquals(queue.lastActivatedAt, sorted.lastActivatedAt)
        assertEquals(queue.journey, sorted.journey)
        assertEquals(queue.order.manual, sorted.order.manual)
        sorted.entries.forEach { entry -> assertSame(entries.single { it.id == entry.id }, entry) }
        assertEquals(queue, QueueEdits.sort(sorted, TrackSort.Manual, true, snapshot))
    }

    @Test fun manualRestorationReconcilesMembershipAndIgnoresLiveKeysAndDirection() {
        val rows = listOf(row("new", track("a", "Alpha")), row("second", track("b", "Beta")),
            row("first", track("c", "Charlie")))
        val order = SavedOrder(listOf("first", "removed", "second"), TrackSort.Title)
        val live = LiveOrder(LibrarySnapshot(tracks = rows.map { it.track.copy(title = "Changed", playCount = 50) }))
        val expected = listOf("new", "first", "second")

        assertEquals(expected, live.target(order, rows, TrackSort.Manual, true))
        assertEquals(expected, live.target(order, rows, TrackSort.Manual, false))
        assertFalse(live.isApplied(order, rows, TrackSort.Manual, true))
        val restored = expected.map { id -> rows.single { it.id == id } }
        val manual = order.copy(sort = TrackSort.Manual)
        assertTrue(live.isApplied(manual, restored, TrackSort.Manual, true))
        assertTrue(live.isApplied(manual, restored, TrackSort.Manual, false))
    }
}

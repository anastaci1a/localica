package io.github.anastaci1a.localica.library

import java.util.Locale
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LibraryQuerySortKeysTest {
    private fun track(id: String, title: String = id) =
        Track("uri/$id", title, fileName = "$id.mp3", relativePath = "$id.mp3", id = id)

    private fun edgeRows(): List<SongRow> {
        val texts = listOf(null, "", " ", "Alpha", "ALPHA", "zeta", "İ", "i\u0307", "É", "e\u0301", "😀")
        val numbers = listOf(null, -1, 0, 1, 2, Int.MAX_VALUE, Int.MIN_VALUE)
        val dates = listOf(null, -1L, 0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE)
        val rows = texts.mapIndexed { index, text ->
            SongRow("occurrence-$index", track("track-$index", text ?: "unknown").copy(
                artist = text, album = texts[(index + 2) % texts.size],
                albumArtist = texts[(index + 1) % texts.size], composer = text, genre = text,
                relativePath = text ?: "unknown-path", year = numbers[index % numbers.size],
                discNumber = numbers[(index + 1) % numbers.size], trackNumber = numbers[(index + 2) % numbers.size],
                durationMs = dates[index % dates.size], modifiedAt = dates[(index + 1) % dates.size],
                addedAt = dates[(index + 2) % dates.size], playCount = dates[index % dates.size] ?: 0L,
            ), "playlist-$index")
        }
        val equal = track("same-track", "SAME").copy(artist = "Band", album = "Album", albumArtist = "",
            discNumber = 1, trackNumber = 1, year = 2000, durationMs = 100, modifiedAt = 20, addedAt = 10)
        return rows + listOf(
            SongRow("z-first", equal, "playlist-z"),
            SongRow("a-second", equal.copy(uri = "alternate-uri", title = "same"), "playlist-a"),
            SongRow("same-id-new-metadata", equal.copy(title = "Different", artist = "Other", playCount = 9)),
            SongRow("upper-id", track("A", "same")),
            SongRow("lower-id", track("a", "SAME")),
        )
    }

    @Test fun everySortAndDirectionMatchesFrozenComparator() {
        assertEquals(14, TrackSort.entries.size)
        val rows = edgeRows()
        val inputs = listOf(rows, rows.reversed(), rows.drop(3) + rows.take(3))
        for (input in inputs) for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
            val query = LibraryQuery(sort = sort, ascending = ascending)
            assertEquals("$sort ascending=$ascending", originalRows(input, query), LibraryQueries.rows(input, query))
        }
    }

    @Test fun ascendingTiesUseTrackIdentityAndKeepEqualOccurrencesStable() {
        val common = track("unused").copy(artist = "Band", album = "Album", albumArtist = "Band",
            relativePath = "same/path", composer = "Composer", genre = "Genre", year = 2000,
            discNumber = 1, trackNumber = 2, durationMs = 50, modifiedAt = 100, addedAt = 200, playCount = 3)
        val rows = listOf(
            SongRow("zulu", common.copy(id = "z", title = "Zulu")),
            SongRow("lower", common.copy(id = "a", title = "same")),
            SongRow("upper-first", common.copy(id = "A", title = "SAME"), "source-z"),
            SongRow("upper-second", common.copy(id = "A", title = "same"), "source-a"),
            SongRow("alpha", common.copy(id = "b", title = "Alpha")),
        )
        val ascendingTies = listOf("alpha", "upper-first", "upper-second", "lower", "zulu")
        for (sort in TrackSort.entries.filter { it != TrackSort.Manual }) for (ascending in listOf(true, false)) {
            val expected = if (sort == TrackSort.Title && !ascending)
                listOf("zulu", "upper-first", "upper-second", "lower", "alpha") else ascendingTies
            assertEquals("$sort ascending=$ascending", expected,
                LibraryQueries.rows(rows, LibraryQuery(sort = sort, ascending = ascending)).map { it.id })
        }
    }

    @Test fun albumChainFollowsDirectionAndBlankArtistDoesNotFallback() {
        val common = track("unused", "Same").copy(album = "Album", artist = "Zulu", albumArtist = "",
            discNumber = 1, trackNumber = 1)
        val rows = listOf(
            SongRow("null-artist", common.copy(id = "null-artist", albumArtist = null, artist = null)),
            SongRow("disc-two", common.copy(id = "disc-two", discNumber = 2)),
            SongRow("fallback", common.copy(id = "fallback", albumArtist = null)),
            SongRow("track-two", common.copy(id = "track-two", trackNumber = 2)),
            SongRow("blank", common.copy(id = "blank")),
            SongRow("unknown-album", common.copy(id = "unknown-album", album = null)),
            SongRow("later-album", common.copy(id = "later-album", album = "Zulu")),
        )
        assertEquals(listOf("blank", "track-two", "disc-two", "fallback", "null-artist", "later-album", "unknown-album"),
            LibraryQueries.rows(rows, LibraryQuery(sort = TrackSort.Album)).map { it.id })
        assertEquals(listOf("later-album", "fallback", "disc-two", "track-two", "blank", "null-artist", "unknown-album"),
            LibraryQueries.rows(rows, LibraryQuery(sort = TrackSort.Album, ascending = false)).map { it.id })
        val artistRows = listOf(rows.first { it.id == "fallback" }, rows.first { it.id == "blank" },
            rows.first { it.id == "null-artist" })
        assertEquals(listOf("blank", "fallback", "null-artist"),
            LibraryQueries.rows(artistRows, LibraryQuery(sort = TrackSort.AlbumArtist)).map { it.id })
        assertEquals(listOf("fallback", "blank", "null-artist"),
            LibraryQueries.rows(artistRows, LibraryQuery(sort = TrackSort.AlbumArtist, ascending = false)).map { it.id })
    }

    @Test fun folderProjectionAndSearchKeepFirstLocationAndOccurrenceMetadata() {
        val shared = track("shared", "Quiet Song").copy(artist = "Soloist", album = "Collection",
            albumArtist = "Various Artists", genre = "Jazz", composer = "Writer", lyricist = "Poet",
            comment = "Memo", year = 2020, relativePath = "global-only/song.mp3", locations = listOf(
                TrackLocation("other", "other-uri", "alternate-only/song.mp3"),
                TrackLocation("selected", "first-uri", "Zebra/first-only.mp3"),
                TrackLocation("selected", "second-uri", "Aardvark/second-only.mp3"),
            ))
        val rows = listOf(SongRow("duplicate-z", shared, "source-z"),
            SongRow("early", track("early", "Quiet Song").copy(locations = listOf(
                TrackLocation("selected", "early-uri", "Alpha/keep.mp3")))),
            SongRow("duplicate-a", shared, "source-a"), SongRow("outside", track("outside")))
        val searches = listOf("", " QUIET  song ", "first-only", "second-only", "alternate-only",
            "global-only", "VARIOUS writer poet memo 2020 jazz", "shared.mp3", "missing")
        for (folder in listOf(null, "selected", "missing")) for (text in searches)
            for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
                val query = LibraryQuery(text, folder, sort, ascending)
                assertEquals("$query", originalRows(rows, query), LibraryQueries.rows(rows, query))
            }
        val selected = LibraryQueries.rows(rows, LibraryQuery(folderUri = "selected", sort = TrackSort.Folder))
        assertEquals(listOf("early", "duplicate-z", "duplicate-a"), selected.map { it.id })
        assertEquals(listOf("early-uri", "first-uri", "first-uri"), selected.map { it.track.uri })
        assertEquals(listOf(null, "source-z", "source-a"), selected.map { it.sourcePlaylistId })
        assertEquals(listOf("Alpha/keep.mp3", "Zebra/first-only.mp3", "Zebra/first-only.mp3"),
            selected.map { it.track.relativePath })
        assertTrue(LibraryQueries.rows(rows, LibraryQuery(text = "second-only", folderUri = "selected")).isEmpty())
        assertEquals(listOf(rows[0], rows[2]), LibraryQueries.rows(rows, LibraryQuery(text = "second-only")))
        assertSame(shared, rows[0].track)
        assertEquals("uri/shared", shared.uri)
    }

    @Test fun manualReversesOccurrencesWhileCountOnlyUsesTitleAndTrackId() {
        val shared = track("shared", "same").copy(playCount = 99)
        val rows = listOf(SongRow("duplicate-z", shared, "source-z"),
            SongRow("alpha", track("alpha", "Alpha").copy(playCount = 1000)),
            SongRow("duplicate-a", shared.copy(playCount = -1), "source-a"))
        for (ascending in listOf(true, false)) {
            assertEquals(if (ascending) rows else rows.reversed(),
                LibraryQueries.rows(rows, LibraryQuery(sort = TrackSort.Manual, ascending = ascending)))
            assertEquals(listOf(rows[1], rows[0], rows[2]),
                LibraryQueries.rows(rows, LibraryQuery(sort = TrackSort.Count, ascending = ascending)))
            assertEquals(if (ascending) listOf(rows[0], rows[2]) else listOf(rows[2], rows[0]),
                LibraryQueries.rows(rows, LibraryQuery(text = "same", sort = TrackSort.Manual, ascending = ascending)))
        }
    }

    @Test fun repeatedRequestsReadFreshMetadataAndStatistics() {
        val a = track("a", "Alpha").copy(artist = "Alpha", album = "Alpha", albumArtist = "Alpha",
            composer = "Alpha", genre = "Alpha", year = 1990, durationMs = 10,
            modifiedAt = 10, addedAt = 10, playCount = 1)
        val b = track("b", "Zulu").copy(artist = "Zulu", album = "Zulu", albumArtist = "Zulu",
            composer = "Zulu", genre = "Zulu", year = 2020, durationMs = 20,
            modifiedAt = 20, addedAt = 20, playCount = 9)
        val before = listOf(SongRow("a-first", a, "source-a"), SongRow("b", b), SongRow("a-last", a, "source-b"))
        val after = before.map { row -> row.copy(track = if (row.track.id == "a")
            b.copy(id = a.id, uri = "fresh-a", relativePath = "z.mp3", playCount = 15)
            else a.copy(id = b.id, uri = "fresh-b", relativePath = "a.mp3", playCount = 0)) }
        for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
            val query = LibraryQuery(sort = sort, ascending = ascending)
            assertEquals(originalRows(before, query), LibraryQueries.rows(before, query))
            assertEquals(originalRows(after, query), LibraryQueries.rows(after, query))
            assertEquals(originalRows(before, query), LibraryQueries.rows(before, query))
            assertEquals(originalRows(after, query).map { it.track },
                LibraryQueries.tracks(LibrarySnapshot(tracks = after.map { it.track }), query))
        }
        val query = LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)
        assertEquals(listOf("b", "a-first", "a-last"), LibraryQueries.rows(before, query).map { it.id })
        assertEquals(listOf("a-first", "a-last", "b"), LibraryQueries.rows(after, query).map { it.id })
        assertEquals(listOf(1L, 9L, 1L), before.map { it.track.playCount })
    }

    @Test fun cancellationPropagatesDuringTraversalWithoutPoisoningRetry() {
        val source = (0 until 1024).map { SongRow("occurrence-$it", track("track-$it", "Song $it")) }
        for (sort in TrackSort.entries) {
            var visited = 0
            val input = observed(source, onNext = { visited++ })
            val cancelled = CancellationException("cancel traversal")
            try {
                LibraryQueries.rows(input, LibraryQuery(sort = sort)) {
                    if (visited >= 129) throw cancelled
                }
                fail("Cancellation was ignored for $sort")
            } catch (failure: CancellationException) {
                assertSame(cancelled, failure)
            }
            assertTrue("Traversal should stop before consuming all rows", visited < source.size)
            val query = LibraryQuery(sort = sort)
            assertEquals(originalRows(source, query), LibraryQueries.rows(source, query))
        }
    }

    @Test fun cancellationBeforePublicationCoversEmptySingleAndFilteredRows() {
        val one = SongRow("occurrence", track("one"))
        val inputs = listOf(emptyList(), listOf(one), edgeRows())
        for (rows in inputs) for (sort in TrackSort.entries) for (text in listOf("", "no-match")) {
            var exhausted = false
            val input = observed(rows, onExhausted = { exhausted = true })
            val cancelled = CancellationException("cancel before publication")
            try {
                LibraryQueries.rows(input, LibraryQuery(text = text, sort = sort)) {
                    if (exhausted) throw cancelled
                }
                fail("Cancellation was ignored for $sort")
            } catch (failure: CancellationException) {
                assertSame(cancelled, failure)
            }
            assertTrue(exhausted)
        }
    }

    private fun observed(rows: List<SongRow>, onNext: () -> Unit = {}, onExhausted: () -> Unit = {}): List<SongRow> =
        object : AbstractList<SongRow>() {
            override val size: Int get() = rows.size
            override fun get(index: Int): SongRow = rows[index]
            override fun iterator(): Iterator<SongRow> {
                val source = rows.iterator()
                return object : Iterator<SongRow> {
                    override fun hasNext(): Boolean = source.hasNext().also { if (!it) onExhausted() }
                    override fun next(): SongRow = source.next().also { onNext() }
                }
            }
        }

    // Frozen from aca610bc04f815593e458a31312316e64aed9dc0 LibraryQuery.kt.
    // Keep independent of prepared keys and production comparison helpers.
    private fun originalRows(rows: List<SongRow>, query: LibraryQuery, checkActive: () -> Unit = {}): List<SongRow> {
        var steps = 0
        fun checkpoint() { if (steps++ % 128 == 0) checkActive() }
        val terms = query.text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun <T : Comparable<T>> compare(a: T?, b: T?) = originalCompareKnown(a, b, query.ascending)
        fun key(value: String?) = value?.lowercase(Locale.ROOT)
        val order = Comparator<Track> { a, b ->
            checkpoint()
            val primary = when (query.sort) {
                TrackSort.Manual -> 0
                TrackSort.Title -> compare(key(a.title), key(b.title))
                TrackSort.Artist -> compare(key(a.artist), key(b.artist))
                TrackSort.AlbumArtist -> compare(key(a.albumArtist ?: a.artist), key(b.albumArtist ?: b.artist))
                TrackSort.Album -> compare(key(a.album), key(b.album)).takeIf { it != 0 }
                    ?: compare(key(a.albumArtist ?: a.artist), key(b.albumArtist ?: b.artist)).takeIf { it != 0 }
                    ?: compare(a.discNumber, b.discNumber).takeIf { it != 0 }
                    ?: compare(a.trackNumber, b.trackNumber)
                TrackSort.Folder -> compare(key(a.relativePath), key(b.relativePath))
                TrackSort.Year -> compare(a.year, b.year)
                TrackSort.Duration -> compare(a.durationMs, b.durationMs)
                TrackSort.Composer -> compare(key(a.composer), key(b.composer))
                TrackSort.Genre -> compare(key(a.genre), key(b.genre))
                TrackSort.Modified -> compare(a.modifiedAt, b.modifiedAt)
                TrackSort.Added -> compare(a.addedAt, b.addedAt)
                TrackSort.MostPlayed -> compare(a.playCount, b.playCount)
                TrackSort.Count -> 0
            }
            if (query.sort == TrackSort.Manual) 0 else primary.takeIf { it != 0 } ?: a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
        val result = rows.asSequence().mapNotNull { row ->
            checkpoint()
            val track = row.track
            if (query.folderUri == null) row else {
                track.locations.firstOrNull { it.folderUri == query.folderUri }?.let {
                    row.copy(track = track.copy(uri = it.uri, relativePath = it.relativePath))
                }
            }
        }.filter { row ->
            val track = row.track
            if (terms.isEmpty()) true else {
                val paths = if (query.folderUri == null) track.locations.map { it.relativePath } + track.relativePath else listOf(track.relativePath)
                val searchable = (listOfNotNull(track.title, track.artist, track.album, track.albumArtist,
                    track.genre, track.comment, track.composer, track.lyricist, track.year?.toString(), track.fileName) + paths).joinToString("\n").lowercase(Locale.ROOT)
                terms.all { it in searchable }
            }
        }.sortedWith { a, b -> order.compare(a.track, b.track) }.toList()
        checkActive()
        return if (query.sort == TrackSort.Manual && !query.ascending) result.reversed() else result
    }

    private fun <T : Comparable<T>> originalCompareKnown(a: T?, b: T?, ascending: Boolean): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> a.compareTo(b) * if (ascending) 1 else -1
    }
}

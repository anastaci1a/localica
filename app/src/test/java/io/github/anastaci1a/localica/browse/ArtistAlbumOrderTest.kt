package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ArtistAlbumOrderTest {
    private fun track(id: String, album: String? = "Album", year: Int? = 2000,
        disc: Int? = 1, number: Int? = 1, title: String = id,
        artist: String? = "Singer", albumArtist: String? = "Band") = Track(
        uri = "uri:$id", title = title, artist = artist, album = album,
        fileName = "$id.mp3", relativePath = "$id.mp3", id = id,
        albumArtist = albumArtist, discNumber = disc, trackNumber = number, year = year)
    private fun row(track: Track, occurrence: String = track.id, source: String? = null) = SongRow(occurrence, track, source)
    private fun ordered(rows: List<SongRow>, sort: TrackSort = TrackSort.Year, ascending: Boolean = true) =
        ArtistAlbumOrder.order(rows, sort, ascending) as ArtistAlbumOrder.Ordered
    private fun ArtistAlbumOrder.Ordered.titles() = albums.map { it.title }

    @Test fun mixedYearAlbumsStayContiguousAndUseTheirEarliestKnownYear() {
        val input = listOf(row(track("late", "Mixed", 2024, number = 2)),
            row(track("middle", "Middle", 2005)), row(track("early", "Mixed", 1990)),
            row(track("no-year", "Mixed", null, number = 3)))
        assertEquals(listOf("Mixed", "Middle"), ordered(input).titles())
        assertEquals(listOf("early", "late", "no-year", "middle"), ordered(input).rows.map { it.id })
        assertEquals(listOf("Middle", "Mixed"), ordered(input, ascending = false).titles())
    }

    @Test fun descendingOuterOrderNeverReversesDiscTrackOrCanonicalFallback() {
        val input = listOf(row(track("disc2", "A", 2000, disc = 2)),
            row(track("track2", "A", 2000, number = 2)), row(track("track1", "A", 2000)),
            row(track("b2", "B", 2020, number = 2)), row(track("b1", "B", 2020)))
        val asc = ordered(input)
        val desc = ordered(input, ascending = false)
        assertEquals(asc.groups.associate { it.album.id to it.rows }, desc.groups.associate { it.album.id to it.rows })
        assertEquals(listOf("b1", "b2", "track1", "track2", "disc2"), desc.rows.map { it.id })
    }

    @Test fun unknownYearIsLastInBothDirectionsAndTiesAlwaysUseTitleThenCanonicalId() {
        val input = listOf(row(track("z", "Zulu", 2000)), row(track("b", "alpha", 2000, albumArtist = "B")),
            row(track("a", "Alpha", 2000, albumArtist = "A")), row(track("u2", "Unknown Z", null)),
            row(track("u1", "Unknown A", null)))
        val expectedKnown = LibraryViews.groups(LibrarySnapshot(tracks = input.take(3).map { it.track }),
            SongListView.Albums, LibraryQuery()).sortedWith(compareBy<LibraryGroup> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
        for (ascending in listOf(true, false)) {
            val result = ordered(input, ascending = ascending)
            assertEquals(expectedKnown.map { it.id }, result.albums.take(3).map { it.id })
            assertEquals(listOf("Unknown A", "Unknown Z"), result.titles().takeLast(2))
        }
    }

    @Test fun canonicalAlbumIdentitySeparatesSameTitleAndReusesTrimCaseAndArtistFallback() {
        val input = listOf(row(track("a", " Same ", albumArtist = " Band ")),
            row(track("b", "same", albumArtist = "band")),
            row(track("c", "Same", artist = "Other", albumArtist = " ")))
        val expected = LibraryViews.groups(LibrarySnapshot(tracks = input.map { it.track }), SongListView.Albums, LibraryQuery())
        val result = ordered(input)
        assertEquals(2, result.groups.size)
        assertEquals(expected.map { it.id }.toSet(), result.albums.map { it.id }.toSet())
        assertEquals(listOf(1, 2), result.groups.map { it.rows.size }.sorted())
    }

    @Test fun missingAlbumTagIsNotTheLiteralUnknownAlbumOrADelimiterCollision() {
        val input = listOf(row(track("missing", null)), row(track("literal", "Unknown album")),
            row(track("left", "a:b", albumArtist = "c")), row(track("right", "a", albumArtist = "b:c")))
        val result = ordered(input, TrackSort.Title)
        assertEquals(4, result.groups.size)
        assertEquals(4, result.albums.map { it.id }.toSet().size)
        assertEquals("missing", result.rows.last().id)
        assertEquals("missing", ordered(input, TrackSort.Title, false).rows.last().id)
    }

    @Test fun duplicateOccurrencesAndExactRowObjectsSurviveCanonicalGrouping() {
        val song = track("song")
        val first = row(song, "occurrence-b", "playlist-one")
        val second = row(song, "occurrence-a", "playlist-two")
        val input = listOf(first, second, first)
        val result = ordered(input)
        assertEquals(listOf("occurrence-a", "occurrence-b", "occurrence-b"), result.rows.map { it.id })
        assertSame(second, result.rows[0])
        assertSame(first, result.rows[1])
        assertSame(first, result.rows[2])
        assertEquals(1, result.albums.single().tracks.size)
        assertSame(song, result.albums.single().tracks.single())
        assertEquals(listOf(first, second, first), input)
    }

    @Test fun identicalTrackIdsInDifferentCanonicalAlbumsNeverCrossJoinOccurrences() {
        val first = row(track("shared", "First"), "one")
        val second = row(track("shared", "Second"), "two")
        val result = ordered(listOf(second, first))
        assertEquals(listOf("First", "Second"), result.titles())
        assertEquals(listOf(listOf(first), listOf(second)), result.groups.map { it.rows })
    }

    @Test fun missingOrdinalsFollowKnownValuesThenTitleTrackIdOccurrenceIdAndInputOrdinal() {
        val a = track("a", title = "same")
        val input = listOf(row(track("disc-unknown", disc = null)),
            row(track("track-unknown", number = null)), row(track("disc-two", disc = 2)),
            row(track("z", title = "Same")), row(a, "z"), row(a, "a"),
            row(track("first", title = "aardvark")))
        val result = ordered(input, ascending = false)
        assertEquals(listOf("first", "a", "z", "z", "track-unknown", "disc-two", "disc-unknown"), result.rows.map { it.id })
        val sameKey = listOf(row(a, "same", "second-source"), row(a, "same", "first-source"))
        assertEquals(sameKey, ordered(sameKey).rows)
    }

    @Test fun inputPermutationDoesNotChangeDistinctOccurrenceOrdering() {
        val input = (1..20).map { n -> row(track("track-${n % 4}", if (n % 2 == 0) "A" else "B",
            year = 2000, disc = n % 3, number = n % 2, title = "Song"), "occurrence-$n") }
        val expected = ordered(input).rows
        repeat(12) { assertEquals(expected, ordered(input.shuffled(Random(it))).rows) }
    }

    @Test fun titleAndAlbumChoicesSortAlbumTitlesRatherThanSongTitles() {
        val input = listOf(row(track("one", "A", title = "Zulu")), row(track("two", "B", title = "Alpha")))
        for (sort in listOf(TrackSort.Title, TrackSort.Album)) {
            assertEquals(listOf("A", "B"), ordered(input, sort).titles())
            assertEquals(listOf("B", "A"), ordered(input, sort, false).titles())
        }
    }

    @Test fun everyCurrentChoiceIsExplicitlySupportedOrReturnedUnsupported() {
        val unsupported = setOf(TrackSort.Manual, TrackSort.Folder)
        assertEquals(TrackSort.entries.toSet() - unsupported, ArtistAlbumOrder.supportedSorts)
        for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
            val result = ArtistAlbumOrder.order(listOf(row(track("one"))), sort, ascending)
            if (sort in unsupported) assertEquals(ArtistAlbumOrder.Unsupported(sort), result)
            else assertTrue(result is ArtistAlbumOrder.Ordered)
        }
    }

    @Test fun countAndPlayCountDoNotMultiplyTheSameTrackForDuplicateOccurrences() {
        val repeated = track("a", "A").copy(playCount = 9)
        val input = listOf(row(repeated, "a1"), row(repeated, "a2"), row(repeated, "a3"),
            row(track("b1", "B").copy(playCount = 5)), row(track("b2", "B").copy(playCount = 5)))
        for (sort in listOf(TrackSort.Count, TrackSort.MostPlayed)) {
            assertEquals(listOf("A", "B"), ordered(input, sort).titles())
            assertEquals(listOf("B", "A"), ordered(input, sort, false).titles())
            assertEquals(5, ordered(input, sort).rows.size)
        }
    }

    @Test fun durationSumsKnownDistinctTracksAndKeepsAllUnknownLast() {
        val partial = track("p1", "Partial").copy(durationMs = 50)
        val input = listOf(row(track("missing", "Unknown")), row(partial, "p1-a"), row(partial, "p1-b"),
            row(track("p2", "Partial")), row(track("zero", "Zero").copy(durationMs = 0)),
            row(track("long1", "Long").copy(durationMs = 40)), row(track("long2", "Long").copy(durationMs = 60)))
        assertEquals(listOf("Zero", "Partial", "Long", "Unknown"), ordered(input, TrackSort.Duration).titles())
        assertEquals(listOf("Long", "Partial", "Zero", "Unknown"), ordered(input, TrackSort.Duration, false).titles())
    }

    @Test fun addedUsesEarliestAndModifiedUsesLatestWithUnknownAlwaysLast() {
        val input = listOf(row(track("a1", "A").copy(addedAt = 10, modifiedAt = 100)),
            row(track("a2", "A").copy(addedAt = 90, modifiedAt = 20)),
            row(track("b", "B").copy(addedAt = 30, modifiedAt = 50)), row(track("u", "Unknown")))
        assertEquals(listOf("A", "B", "Unknown"), ordered(input, TrackSort.Added).titles())
        assertEquals(listOf("B", "A", "Unknown"), ordered(input, TrackSort.Added, false).titles())
        assertEquals(listOf("B", "A", "Unknown"), ordered(input, TrackSort.Modified).titles())
        assertEquals(listOf("A", "B", "Unknown"), ordered(input, TrackSort.Modified, false).titles())
    }

    @Test fun knownZeroMetadataIsNotMistakenForUnknown() {
        val input = listOf(row(track("u", "Unknown", year = null)),
            row(track("z", "Zero", year = 0).copy(addedAt = 0, modifiedAt = 0, durationMs = 0)))
        for (sort in listOf(TrackSort.Year, TrackSort.Added, TrackSort.Modified, TrackSort.Duration)) {
            for (ascending in listOf(true, false)) assertEquals(listOf("Zero", "Unknown"), ordered(input, sort, ascending).titles())
        }
    }

    @Test fun textAggregatesAreExplicitAndBlankOnlyValuesAreUnknown() {
        for (sort in listOf(TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre)) {
            fun member(id: String, album: String, value: String?) = row(track(id, album, artist = null, albumArtist = null).let {
                when (sort) {
                    TrackSort.Artist -> it.copy(artist = value, albumArtist = "Band")
                    TrackSort.AlbumArtist -> it.copy(albumArtist = value)
                    TrackSort.Composer -> it.copy(composer = value)
                    else -> it.copy(genre = value)
                }
            })
            val input = listOf(member("u", "Unknown", " "), member("b", "B", "Beta"), member("a", "A", "Alpha"))
            assertEquals(listOf("A", "B", "Unknown"), ordered(input, sort).titles())
            assertEquals(listOf("B", "A", "Unknown"), ordered(input, sort, false).titles())
        }
    }

    @Test fun joinedMetadataUsesDistinctSortedValuesAndAlbumArtistHasArtistFallback() {
        val input = listOf(row(track("a1", "A", artist = "Zed")), row(track("a2", "A", artist = "Amy")),
            row(track("a3", "A", artist = "Amy")), row(track("b", "B", artist = "Bob")))
        assertEquals(listOf("A", "B"), ordered(input, TrackSort.Artist).titles())
        val fallback = listOf(row(track("a", "A", artist = "Alpha", albumArtist = null)),
            row(track("b", "B", albumArtist = "Beta")))
        assertEquals(listOf("A", "B"), ordered(fallback, TrackSort.AlbumArtist).titles())
    }

    @Test fun carouselAndFlattenedRowsComeFromTheSameGroupsWithoutInputMutation() {
        val input = mutableListOf(row(track("b", "B", 2020)), row(track("a", "A", 1990)))
        val original = input.toList()
        val result = ordered(input, ascending = false)
        assertEquals(result.groups.map { it.album }, result.albums)
        assertEquals(result.groups.flatMap { it.rows }, result.rows)
        assertEquals(original, input)
        input.clear()
        assertEquals(2, result.rows.size)
    }

    @Test fun emptyInputStillReportsUnsupportedPoliciesRatherThanChoosingAFallback() {
        assertEquals(emptyList<ArtistAlbumOrder.Group>(), ordered(emptyList()).groups)
        assertEquals(ArtistAlbumOrder.Unsupported(TrackSort.Folder), ArtistAlbumOrder.order(emptyList(), TrackSort.Folder, false))
    }

    @Test fun orderingIsIndependentOfTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("I", "J"), ordered(listOf(row(track("j", "J")), row(track("i", "I"))), TrackSort.Title).titles())
        } finally { Locale.setDefault(previous) }
    }

    @Test fun cancellationCheckPropagatesWithoutMutatingInput() {
        val input = listOf(row(track("one")))
        val failure = IllegalStateException("cancelled")
        try {
            ArtistAlbumOrder.order(input, TrackSort.Year, true) { throw failure }
            fail("Expected cancellation check")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals("one", input.single().id)
    }
}

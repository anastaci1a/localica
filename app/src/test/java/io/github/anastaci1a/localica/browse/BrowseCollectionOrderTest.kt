package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import java.util.Locale
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BrowseCollectionOrderTest {
    // Frozen pre-extraction comparator: retain its independent aggregate expressions.
    private fun original(items: List<BrowserItem>, query: LibraryQuery): List<BrowserItem> {
        fun <T : Comparable<T>> compare(a: T?, b: T?) = compareKnown(a, b, query.ascending)
        return items.sortedWith { a, b ->
            val primary = when (query.sort) {
                TrackSort.Count -> compare(a.songs.size, b.songs.size)
                TrackSort.Duration -> compare(a.duration(), b.duration())
                TrackSort.Year -> compare(a.songs.mapNotNull { it.track.year }.minOrNull(), b.songs.mapNotNull { it.track.year }.minOrNull())
                TrackSort.Modified -> compare(a.modifiedAt, b.modifiedAt)
                TrackSort.Added -> compare(a.addedAt, b.addedAt)
                TrackSort.MostPlayed -> compare(a.playCount, b.playCount)
                TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre ->
                    compare(a.metadataText(query.sort)?.lowercase(Locale.ROOT), b.metadataText(query.sort)?.lowercase(Locale.ROOT))
                else -> compare(a.title.lowercase(Locale.ROOT), b.title.lowercase(Locale.ROOT))
            }
            primary.takeIf { it != 0 } ?: a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
                ?: a.key.compareTo(b.key)
        }
    }
    private fun rows(index: Int): List<SongRow> = (0 until index % 7).map { member ->
        val id = "${index % 5}:${member % 2}"
        SongRow("$index:$member", Track(id, "Track $member", fileName = "$member.mp3", relativePath = "$member.mp3",
            artist = listOf(null, "", "alpha", "ALPHA", "İstanbul", "Zulu")[(index + member) % 6],
            albumArtist = if (member % 2 == 0) null else "Album $index",
            composer = if (index % 3 == 0) null else "Composer ${member % 2}",
            genre = if (index % 2 == 0) "Genre $member" else "",
            durationMs = when ((index + member) % 5) { 0 -> null; 1 -> Long.MAX_VALUE; else -> member.toLong() },
            year = if (member % 2 == 0) null else 1980 + index % 3,
            playCount = index.toLong()))
    }
    private fun items() = (0 until 150).map { i ->
        BrowserItem(MediaRef(MediaKind.Album, "${i % 13}"), listOf("Alpha", "alpha", "İstanbul", "", "Zulu")[i % 5],
            null, rows(i), addedAt = if (i % 3 == 0) null else i.toLong(),
            modifiedAt = if (i % 4 == 0) null else -i.toLong())
    }
    @Test fun everySortAndDirectionMatchesOriginalIncludingUnknownsDuplicatesAndOverflow() {
        val items = items().shuffled(kotlin.random.Random(428))
        for (sort in TrackSort.entries) for (ascending in listOf(true, false)) {
            val query = LibraryQuery(sort = sort, ascending = ascending)
            val expected = original(items, query)
            val actual = orderCollectionItems(items, query)
            assertEquals("$sort / $ascending", expected, actual)
            expected.indices.forEach { assertSame(expected[it], actual[it]) }
        }
        assertTrue(orderCollectionItems(emptyList(), LibraryQuery()).isEmpty())
    }
    @Test fun equalKeysKeepInputOrderAndEachRequestReadsItsCurrentMetadata() {
        val first = BrowserItem(MediaRef(MediaKind.Album, "same"), "Same", "first", rows(3))
        val second = first.copy(subtitle = "second", songs = rows(5))
        for (ascending in listOf(true, false)) {
            val query = LibraryQuery(sort = TrackSort.Title, ascending = ascending)
            val sorted = orderCollectionItems(listOf(second, first), query)
            assertSame(second, sorted[0]); assertSame(first, sorted[1])
        }
        val before = items()
        val after = before.mapIndexed { i, item -> item.copy(songs = rows(i + 2), title = "Changed ${149 - i}") }
        for (sort in TrackSort.entries) {
            val query = LibraryQuery(sort = sort)
            orderCollectionItems(before, query)
            assertEquals(original(after, query), orderCollectionItems(after, query))
        }
    }
    private class CountedRows(private val values: List<SongRow>) : AbstractList<SongRow>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): SongRow { reads++; return values[index] }
    }
    @Test fun aggregatePreparationReadsEachMemberOnceAndTitleSortDoesNotReadMembers() {
        val counted = (0 until 200).map { CountedRows(rows(it + 1)) }
        val items = counted.mapIndexed { i, songs -> BrowserItem(MediaRef(MediaKind.Album, "$i"), "Item ${200 - i}", null, songs) }
        for (sort in listOf(TrackSort.Duration, TrackSort.Year, TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre)) {
            counted.forEach { it.reads = 0 }
            val sorted = orderCollectionItems(items, LibraryQuery(sort = sort))
            assertEquals(items.size, sorted.size)
            counted.forEach { assertEquals("$sort must visit each member only during preparation", it.size, it.reads) }
        }
        counted.forEach { it.reads = 0 }
        orderCollectionItems(items, LibraryQuery(sort = TrackSort.Title))
        assertTrue(counted.all { it.reads == 0 })
    }
    @Test fun cancellationAtEveryCheckpointPropagatesAndRetryKeepsInputUntouched() {
        val input = items().shuffled(kotlin.random.Random(99))
        val originalOrder = input.toList()
        val query = LibraryQuery(sort = TrackSort.Duration)
        var checkpoints = 0
        val expected = orderCollectionItems(input, query) { checkpoints++ }
        assertTrue(checkpoints > 2)
        for (stop in 1..checkpoints) {
            var visited = 0
            val failure = CancellationException("stop $stop")
            try {
                orderCollectionItems(input, query) { if (++visited == stop) throw failure }
                fail("Cancellation must escape")
            } catch (actual: CancellationException) { assertSame(failure, actual) }
            input.indices.forEach { assertSame(originalOrder[it], input[it]) }
            assertEquals(expected, orderCollectionItems(input, query))
        }
    }
}

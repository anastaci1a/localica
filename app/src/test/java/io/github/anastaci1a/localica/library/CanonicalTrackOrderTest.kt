package io.github.anastaci1a.localica.library

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CanonicalTrackOrderTest {
    private fun track(id: String, title: String, path: String, uri: String) =
        Track(uri, title, fileName = "$id.mp3", relativePath = path, id = id)

    // Frozen source comparator: path and URI ties, not browse's track identity ties.
    private val original = compareBy<Track> { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.relativePath }.thenBy { it.uri }

    @Test fun preparedOrderMatchesOriginalForUnicodePathsAndEveryInputRotation() {
        val titles = listOf("", " ", "Alpha", "ALPHA", "İ", "i\u0307", "É", "e\u0301", "😀", "z")
        val rows = titles.flatMapIndexed { i, title ->
            listOf(track("z-$i", title, "A/file", "uri/z"), track("a-$i", title, "a/file", "uri/a"),
                track("b-$i", title, "A/file", "uri/A"))
        }
        for (offset in rows.indices) for (input in listOf(rows.drop(offset) + rows.take(offset), rows.reversed())) {
            val expected = input.sortedWith(original)
            val actual = input.inCanonicalOrder()
            assertEquals(expected, actual)
            expected.indices.forEach { assertSame(expected[it], actual[it]) }
            for (a in input) for (b in input) assertEquals(original.compare(a, b), trackOrder.compare(a, b))
        }
    }

    @Test fun completeTiesPreserveEncounterOrderAndNeverDeduplicateOrUseIds() {
        val first = track("z", "SAME", "same", "same")
        val second = first.copy(id = "a", title = "same", artist = "Different metadata")
        val rows = listOf(first, second, first)
        val actual = rows.inCanonicalOrder()
        assertEquals(3, actual.size)
        actual.indices.forEach { assertSame(rows[it], actual[it]) }
        assertEquals(0, trackOrder.compare(first, second))
        val reversed = rows.reversed().inCanonicalOrder()
        rows.indices.forEach { assertSame(rows.reversed()[it], reversed[it]) }
    }

    @Test fun eachReadPreparesCurrentMetadataAndHandlesEmptyOrSingleInput() {
        val a = track("a", "Alpha", "z", "uri/z")
        val b = track("b", "Zulu", "a", "uri/a")
        assertTrue(emptyList<Track>().inCanonicalOrder().isEmpty())
        assertSame(a, listOf(a).inCanonicalOrder().single())
        assertEquals(listOf(a, b), listOf(b, a).inCanonicalOrder())
        val freshA = a.copy(title = "Zulu", relativePath = "z")
        val freshB = b.copy(title = "Alpha", uri = "changed")
        assertEquals(listOf(freshB, freshA), listOf(freshA, freshB).inCanonicalOrder())
        assertEquals(listOf(a, b), listOf(b, a).inCanonicalOrder())
    }
}

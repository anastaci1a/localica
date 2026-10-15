package io.github.anastaci1a.localica.library

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CancellationException

class LibrarySearchIndexTest {
    private fun track(id: String) = Track(id, "Song $id", artist = "Artist", fileName = "$id.mp3", relativePath = "Main/$id.mp3",
        locations = listOf(TrackLocation("root", id, "Main/$id.mp3")))

    @Test fun unchangedTextSurvivesStatisticsSortMetadataAndSourceOrderChanges() {
        val builder = LibrarySearchIndexBuilder()
        val initial = builder.prepare(LibrarySnapshot(tracks = listOf(track("one"), track("two"))))
        val changed = builder.prepare(initial.copy(tracks = initial.tracks.reversed().map {
            it.copy(playCount = 42, durationMs = 1234, addedAt = 500, modifiedAt = 600, trackNumber = 8, discNumber = 2)
        }))
        assertSame(initial.searchIndex, changed.searchIndex)
        assertEquals(initial.searchIndex!!.revision, changed.searchIndex!!.revision)
    }

    @Test fun additionsEditsAndDeletionsOnlyReplaceDependentEntries() {
        val builder = LibrarySearchIndexBuilder()
        val first = builder.prepare(LibrarySnapshot(tracks = listOf(track("one"), track("two"))))
        val edited = builder.prepare(first.copy(tracks = listOf(track("one"), track("two").copy(comment = "new comment"), track("three"))))
        assertSame(first.searchIndex!!.entry("one"), edited.searchIndex!!.entry("one"))
        assertNotSame(first.searchIndex.entry("two"), edited.searchIndex.entry("two"))
        assertEquals(first.searchIndex.revision + 1, edited.searchIndex.revision)
        val removed = builder.prepare(edited.copy(tracks = listOf(track("one"))))
        assertNull(removed.searchIndex!!.entry("two"))
        assertNull(removed.searchIndex.entry("three"))
        assertEquals(1, removed.searchIndex.size)
    }

    @Test fun independentOrOutOfOrderLoadsAlwaysReturnSelfContainedText() {
        val builder = LibrarySearchIndexBuilder()
        val old = LibrarySnapshot(tracks = listOf(track("one").copy(title = "Old title")))
        val recent = old.copy(tracks = listOf(old.tracks.single().copy(title = "New title")))
        val first = builder.prepare(old)
        val second = builder.prepare(recent)
        val delayed = builder.prepare(old)
        assertEquals("old title", first.searchIndex!!.text(old.tracks.single()).lineSequence().first())
        assertEquals("new title", second.searchIndex!!.text(recent.tracks.single()).lineSequence().first())
        assertEquals("old title", delayed.searchIndex!!.text(old.tracks.single()).lineSequence().first())
        assertTrue(delayed.searchIndex.revision > second.searchIndex.revision)
        val restarted = LibrarySearchIndexBuilder().prepare(recent)
        assertEquals(second.searchIndex.text(recent.tracks.single()), restarted.searchIndex!!.text(recent.tracks.single()))
    }

    @Test fun everySearchableFieldInvalidatesAndCopiedSnapshotsCannotUseOldText() {
        val builder = LibrarySearchIndexBuilder()
        val song = track("one")
        val initial = builder.prepare(LibrarySnapshot(tracks = listOf(song)))
        val mutations = listOf(song.copy(title = "needle"), song.copy(artist = "needle"), song.copy(album = "needle"),
            song.copy(albumArtist = "needle"), song.copy(genre = "needle"), song.copy(comment = "needle"),
            song.copy(composer = "needle"), song.copy(lyricist = "needle"), song.copy(year = 1987),
            song.copy(fileName = "needle"), song.copy(relativePath = "needle"),
            song.copy(locations = listOf(TrackLocation("root", song.uri, "needle"))))
        mutations.forEach { edited ->
            val needle = if (edited.year == 1987) "1987" else "needle"
            assertTrue(initial.searchIndex!!.text(edited).contains(needle))
            val updated = builder.prepare(initial.copy(tracks = listOf(edited)))
            assertTrue(updated.searchIndex!!.text(edited).contains(needle))
            assertNotSame(initial.searchIndex.entry(song.id), updated.searchIndex.entry(song.id))
        }
    }

    @Test fun folderAndUnicodeTextRetainExistingLiteralSubstringSemantics() {
        val song = track("one").copy(title = "Beyoncé İSTANBUL Straße e\u0301 🎶 東京", comment = "100% a_b OR * \"quote\"",
            locations = listOf(TrackLocation("root", "one", "Main/one.mp3"), TrackLocation("other", "alias", "Hidden/one.mp3")))
        val prepared = LibrarySearchIndexBuilder().prepare(LibrarySnapshot(tracks = listOf(song)))
        val index = prepared.searchIndex!!
        val queries = listOf("beyoncé", "BÉY", "yonc", "beyonce", "istanbul", "i\u0307stanbul", "strasse", "straße",
            "é", "e\u0301", "🎶", "東京", "100%", "a_b", "OR", "*", "\"quote\"", "hidden song", "\u00a0")
        queries.forEach { query ->
            val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
            assertEquals(query, LibraryQueries.tracks(prepared, LibraryQuery(text = query)).isNotEmpty(),
                terms.all { it in index.text(song) })
        }
        val scoped = LibraryQueries.tracks(prepared, LibraryQuery(folderUri = "root")).single()
        assertFalse(index.text(scoped, "root").contains("hidden"))
        assertTrue(index.text(song).contains("hidden"))
    }

    @Test fun capturedPathsDoNotAliasCallerCollectionsAndCancellationDoesNotPublish() {
        val builder = LibrarySearchIndexBuilder()
        val locations = mutableListOf(TrackLocation("root", "one", "before"))
        val initial = builder.prepare(LibrarySnapshot(tracks = listOf(track("one").copy(locations = locations))))
        locations[0] = locations[0].copy(relativePath = "after")
        assertTrue(initial.searchIndex!!.entry("one")!!.fields.paths.contains("before"))
        assertFalse(initial.searchIndex.entry("one")!!.fields.paths.contains("after"))
        try {
            builder.prepare(initial.copy(tracks = listOf(track("two")))) { throw CancellationException("superseded") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        val after = builder.prepare(initial.copy(tracks = listOf(track("one").copy(locations = listOf(TrackLocation("root", "one", "before"))))))
        assertSame(initial.searchIndex, after.searchIndex)
    }
}

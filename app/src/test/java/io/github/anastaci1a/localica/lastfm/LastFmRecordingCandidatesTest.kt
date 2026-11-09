package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

class LastFmRecordingCandidatesTest {
    private fun song(id: String, artist: String, title: String, album: String?, duration: Long = 240_000) =
        Track(id, title, artist, album, duration, "$id.m4a", "$id.m4a")
    private fun match(remote: List<LastFmTrack>, library: List<Track>, choices: Map<String, String> = emptyMap(), history: Boolean = true) =
        LastFmMatcher.match(LastFmSnapshot("fixture", 1, remote, historyComplete = history), library, choices)

    @Test fun identicalCopiesWithUnknownAlbumAreOneCandidateAndNeverCompeteWithThemselves() {
        val remote = LastFmTrack("Kendrick Lamar", "Mirror", 117)
        val copies = listOf(song("one", remote.artist, "18 Mirror.m4a", null), song("two", remote.artist, "Mirror", null))
        for (history in listOf(false, true)) for (library in listOf(copies, copies.reversed())) {
            val result = match(listOf(remote), library, history = history)
            assertTrue(result.review.isEmpty()); assertEquals(1, result.clearCount)
            assertEquals(mapOf("one" to 117L, "two" to 117L), result.counts)
        }
    }

    @Test fun exactOperatorStudioCasesResolveWithoutAHiddenLiveCompetitor() {
        val remote = listOf(LastFmTrack("Kendrick Lamar", "Mirror", 117, "Mr. Morale & The Big Steppers"),
            LastFmTrack("Thad Jones-Mel Lewis Jazz Orchestra", "Mean What You Say", 103,
                "Presenting Thad Jones-Mel Lewis & The Jazz Orchestra"),
            LastFmTrack("Snarky Puppy", "Bad Kids to the Back", 74, "Immigrance"))
        val library = listOf(song("mirror", remote[0].artist, "18 Mirror.m4a", remote[0].album),
            song("mean-studio", remote[1].artist, "05 Mean What You Say.m4a", remote[1].album),
            song("mean-live", remote[1].artist, "12 Mean What You Say (Live).m4a", "All My Yesterdays (Live)"),
            song("mean-lab", "University Jazz Orchestra", "04 Mean What You Say.m4a", "Lab 2025"),
            song("bad-studio", remote[2].artist, "07 Bad Kids To The Back.m4a", remote[2].album),
            song("bad-live", remote[2].artist, "05 Bad Kids To The Back (Live).m4a", "Live at the Royal Albert Hall"))
        for (rows in listOf(library, library.reversed())) {
            val result = match(remote, rows)
            assertTrue(result.review.isEmpty()); assertEquals(3, result.clearCount)
            assertEquals(mapOf("mirror" to 117L, "mean-studio" to 103L, "bad-studio" to 74L), result.counts)
        }
        assertTrue(match(remote.map { it.copy(album = null) }, library, history = false).review.isEmpty())
    }

    @Test fun albumHistoryAllocatesCountsToTheEditionAndExplicitLiveAlbumCanSupplyItsVersion() {
        val artist = "Thad Jones-Mel Lewis Jazz Orchestra"
        val studioAlbum = "Presenting Thad Jones-Mel Lewis & The Jazz Orchestra"
        val liveAlbum = "All My Yesterdays (Live)"
        val remote = listOf(LastFmTrack(artist, "Mean What You Say", 103, studioAlbum),
            LastFmTrack(artist, "Mean What You Say", 7, liveAlbum))
        val library = listOf(song("studio", artist, "Mean What You Say", studioAlbum),
            song("live", artist, "Mean What You Say (Live)", liveAlbum))
        val result = match(remote, library)
        assertTrue(result.review.isEmpty()); assertEquals(mapOf("studio" to 103L, "live" to 7L), result.counts)
        val versions = listOf(song("album", "Artist", "Song", "Album"), song("deluxe", "Artist", "Song", "Album (Deluxe Edition)"))
        assertEquals(mapOf("album" to 20L, "deluxe" to 3L), match(listOf(LastFmTrack("Artist", "Song", 20, "Album"),
            LastFmTrack("Artist", "Song", 3, "Album (Deluxe Edition)")), versions).counts)
    }

    @Test fun realAmbiguityHasTwoRecordingsWhileExplicitUndoNeverSilentlyRematches() {
        val remote = LastFmTrack("Artist", "Song", 9)
        val library = listOf(song("one", "Artist", "Song", "First"), song("copy", "Artist", "Song", "First"),
            song("other", "Artist", "Song", "Second"))
        val result = match(listOf(remote), library)
        assertEquals(1, result.review.size); assertEquals(2, result.review.single().candidates.size)
        val only = library.take(2)
        assertTrue(match(listOf(remote), only).review.isEmpty())
        val undone = match(listOf(remote), only, mapOf(remote.key to LastFmMatcher.REVIEW))
        assertEquals("Automatic match undone by you", undone.review.single().reason)
        assertEquals(1, undone.review.single().candidates.size); assertTrue(undone.counts.isEmpty())
        assertEquals(1, match(listOf(remote), only.reversed(), mapOf(remote.key to LastFmMatcher.REVIEW)).review.size)
    }

    @Test fun anUnknownAlbumWithASharedRecordingIdCannotBridgeTwoEditionTotals() {
        val library = listOf(song("album", "Artist", "Song", "Album"), song("deluxe", "Artist", "Song", "Album (Deluxe Edition)"))
            .map { it.copy(musicBrainzId = "recording") }
        val remote = listOf(LastFmTrack("Artist", "Song", 20, "Album", "recording"),
            LastFmTrack("Artist", "Song", 3, "Album (Deluxe Edition)", "recording"),
            LastFmTrack("Artist", "Song", 7, "Unowned edition", "recording"))
        val result = match(remote, library)
        assertEquals(mapOf("album" to 20L, "deluxe" to 3L), result.counts)
        assertEquals(1, result.review.size); assertEquals(2, result.review.single().candidates.size)
        assertTrue(result.review.single().reason.contains("album edition"))
    }

    @Test fun aSingleWeakerCompatibleCandidateIsAJudgmentButHardContradictionsStayUnmatched() {
        val remote = LastFmTrack("Artist", "Song", 9, "Unavailable edition")
        val local = song("one", "Artist", "Song", "Owned edition")
        val result = match(listOf(remote), listOf(local))
        assertTrue(result.review.isEmpty()); assertEquals(1, result.judgments.size)
        assertTrue(result.judgments.single().reason.contains("album differs"))
        val conflicting = match(listOf(remote.copy(mbid = "recording-a")), listOf(local.copy(musicBrainzId = "recording-b")))
        assertTrue(conflicting.review.isEmpty()); assertTrue(conflicting.counts.isEmpty()); assertEquals(1, conflicting.notInLibrary)
    }
}

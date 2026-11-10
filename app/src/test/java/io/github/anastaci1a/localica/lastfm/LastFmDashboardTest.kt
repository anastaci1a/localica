package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

class LastFmDashboardTest {
    private val song = Track("local", "Cherry Juice", "Thad Jones", "Consummation", 1000, "song.flac", "song.flac")
    private val remote = LastFmTrack("Dark Boson", "Thad Jones - Cherry Juice", 98)
    @Test fun everyGroupRetainsRemoteIdentityCandidatesAndReasonForManualRepair() {
        val matches = LastFmMatches(emptyMap(), listOf(LastFmReview(remote, listOf(song), "Review reason")),
            editions = listOf(LastFmReview(remote, listOf(song, song.copy(id = "copy")), "Edition reason")),
            resolutions = listOf(LastFmResolution(remote, listOf(song), LastFmMatchTier.PROBABLE, "Artist parsed from title", 75),
                LastFmResolution(remote, listOf(song), LastFmMatchTier.CLEAR, "Exact recording", 100)),
            unmatched = listOf(remote))
        LastFmGroup.entries.forEach { group ->
            assertEquals(remote, matches.rows(group).single().remote)
            assertTrue(matches.rows(group).single().contains("cherry juice"))
        }
        assertEquals("Artist parsed from title", matches.rows(LastFmGroup.JUDGMENTS).single().reason)
        assertEquals(2, matches.rows(LastFmGroup.EDITIONS).single().candidates.size)
        assertFalse(matches.rows(LastFmGroup.ABSENT).single().contains("not present"))
    }
    @Test fun librarySearchIncludesUnrelatedSongsAndSearchesFilenameArtistAlbumAndAccents() {
        val unrelated = song.copy(id = "any", title = "Élan", artist = "Another Artist", album = "Other Album", fileName = "03-elan.flac")
        assertEquals(listOf(unrelated), libraryMatches(listOf(song, unrelated), "elan other"))
        assertEquals(listOf(unrelated), libraryMatches(listOf(song, unrelated), "03 flac"))
        assertEquals(2, libraryMatches(listOf(song, unrelated), "").size)
    }
    @Test fun archiveStatsNeverMultiplyPlaysByLocalEditionCount() {
        val archive = LastFmSnapshot("user", 1, listOf(remote))
        val stats = archive.accountStats()
        assertEquals(98L, stats.scrobbles)
        assertEquals(1, stats.songs)
        assertEquals(0, stats.albums)
        assertEquals("43% · 20 songs · 5 artists · 3 known albums", LastFmProgress(songs = 20, artists = 5, albums = 3, reportedPercent = 43).label)
    }
}

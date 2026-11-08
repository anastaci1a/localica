package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

/** Constructed public fixture modelled on #684's 15,451-row account; no private account data. */
class LastFmMatchingTest {
    private fun local(id: String, title: String, artist: String = "Artist", album: String = "Album") =
        Track(id, title, artist, album, 1000, "$id.m4a", "$id.m4a")
    private fun match(remote: List<LastFmTrack>, local: List<Track>) =
        LastFmMatcher.match(LastFmSnapshot("fixture", 1, remote), local)

    @Test fun absentSongsNeverBecomeReviewWork() {
        val result = match(listOf(LastFmTrack("Kendrick Lamar", "Mirror", 117),
            LastFmTrack("Dark Boson", "Thad Jones & Mel Lewis Orchestra - Cherry Juice", 98)),
            listOf(local("song", "Different song")))
        assertTrue(result.review.isEmpty()); assertTrue(result.counts.isEmpty())
    }

    @Test fun editionCopiesEachReceiveTheSameSongPlaysWithoutAChoice() {
        val result = match(listOf(LastFmTrack("Flying Lotus", "More (feat. Anderson .Paak)", 108)),
            listOf(local("original", "More (feat. Anderson .Paak)", "Flying Lotus", "Flamagra"),
                local("deluxe", "More (feat. Anderson .Paak)", "Flying Lotus", "Flamagra (Deluxe Edition)")))
        assertEquals(mapOf("original" to 108L, "deluxe" to 108L), result.counts)
        assertTrue(result.review.isEmpty())
    }

    @Test fun realisticAccountHasSmallReviewResidueAndZeroFalseMatches() {
        val songs = mutableListOf<Track>(); val remote = mutableListOf<LastFmTrack>()
        val expected = mutableMapOf<String, Long>()
        repeat(6000) { n ->
            songs += local("exact$n", "Song $n", "Artist $n")
            remote += LastFmTrack("Artist $n", "Song $n", n + 1L); expected["exact$n"] = n + 1L
        }
        repeat(300) { n ->
            val plays = n + 10L
            when (n % 3) {
                0 -> {
                    songs += local("variant$n", "More $n (feat. Anderson .Paak)", "Flying Lotus", "Flamagra")
                    songs += local("edition$n", "More $n (feat. Anderson .Paak)", "Flying Lotus", "Flamagra [Deluxe Edition]")
                    remote += LastFmTrack("Flying Lotus featuring Anderson Paak", "More $n", plays)
                    expected["edition$n"] = plays
                }
                1 -> {
                    songs += local("variant$n", "01. Déjà Vu $n - 2011 Remaster.m4a", "Jay-Z & Kanye West")
                    remote += LastFmTrack("JAY Z and KANYE WEST", "Deja Vu $n", plays)
                }
                else -> {
                    songs += local("variant$n", "Song $n [Live at Wembley]", "Alpha x Beta")
                    remote += LastFmTrack("Alpha & Beta", "Song $n - Live at Wembley", plays)
                }
            }
            expected["variant$n"] = plays
        }
        repeat(9148) { n -> remote += LastFmTrack("Not owned $n", "Absent $n", n + 1L) }
        listOf(7L, 900L, 80L).forEachIndexed { n, plays ->
            songs += local("ambiguous$n-a", "Ambiguous $n", album = "First album")
            songs += local("ambiguous$n-b", "Ambiguous $n", album = "Another album")
            remote += LastFmTrack("Artist", "Ambiguous $n", plays)
        }
        val result = match(remote, songs)
        assertEquals(15451, remote.size)
        assertEquals("Every count, no extra assignments", expected, result.counts)
        assertEquals(3, result.review.size)
        assertEquals(listOf(900L, 80L, 7L), result.review.map { it.remote.plays })
    }

    @Test fun distinctRecordingsArtistsAndSmallTitlesNeverCollapse() {
        val songs = listOf(local("studio", "Song"), local("live", "Song (Live)"),
            local("intro", "Intro"), local("interlude", "Interlude"), local("other", "Song", "Other Artist"),
            local("part2", "Song Part II"), local("remix", "Song (Remix)"))
        val result = match(listOf(LastFmTrack("Artist", "Song", 20), LastFmTrack("Artist", "Song - Live", 5),
            LastFmTrack("Artist", "Intro", 3), LastFmTrack("Artist", "Interlude", 2),
            LastFmTrack("Absent Artist", "Song", 100), LastFmTrack("Artist", "Song Part I", 100),
            LastFmTrack("Artist", "Song [Remix]", 8)), songs)
        assertEquals(mapOf("studio" to 20L, "live" to 5L, "intro" to 3L, "interlude" to 2L, "remix" to 8L), result.counts)
        assertTrue(result.review.isEmpty())
    }

    @Test fun conflictingIdsRequireReviewWhileIdenticalIdsResolve() {
        val first = local("first", "Song").copy(musicBrainzId = "recording-a")
        val other = local("other", "Song", album = "Album (Deluxe)").copy(musicBrainzId = "recording-b")
        val conflict = match(listOf(LastFmTrack("Artist", "Song", 9)), listOf(first, other))
        assertTrue(conflict.counts.isEmpty()); assertEquals(1, conflict.review.size)
        val strong = match(listOf(LastFmTrack("Old artist credit", "Old title", 9, mbid = "RECORDING-A")), listOf(first, other))
        assertEquals(mapOf("first" to 9L), strong.counts)
    }
    @Test fun titleConnectorsAndRemasterAfterFeaturedCreditMatchWithoutDroppingTheGuest() {
        val songs = listOf(local("song", "Rock & Roll (feat. Guest) - 2020 Remaster", "A & B"),
            local("other-guest", "Rock and Roll (feat. Other Guest)", "A and B"))
        val result = match(listOf(LastFmTrack("A x B ft Guest", "Rock and Roll", 10)), songs)
        assertEquals(mapOf("song" to 10L), result.counts); assertTrue(result.review.isEmpty())
    }

    @Test fun aSavedManualChoiceAppliesToItsEditionCopiesButNotOtherRecordings() {
        val remote = LastFmTrack("Older credit", "Older title", 42)
        val original = local("original", "Song")
        val deluxe = local("deluxe", "Song", album = "Album (Deluxe)")
        val other = local("other", "Song", album = "A different recording")
        val result = LastFmMatcher.match(LastFmSnapshot("u", 1, listOf(remote)), listOf(original, deluxe, other), mapOf(remote.key to original.id))
        assertEquals(mapOf("original" to 42L, "deluxe" to 42L), result.counts)
    }
}

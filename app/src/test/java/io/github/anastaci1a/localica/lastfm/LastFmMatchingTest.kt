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
        val result = match(listOf(LastFmTrack("Not owned", "Absent from this fixture", 117),
            LastFmTrack("Unknown channel", "Unowned artist - Unavailable recording", 98)),
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
        repeat(9148) { n -> when {
            n == 0 -> { songs += local("mirror", "18. Mirror [HD].m4a", "Kendrick Lamar"); remote += LastFmTrack("Kendrick Lamar", "Mirror", 117); expected["mirror"] = 117 }
            n == 1 -> { songs += local("six-sixteen", "6:16 in LA", "Kendrick Lamar"); remote += LastFmTrack("Kendrick Lamar", "6:16 In La (Drake Diss)", 103); expected["six-sixteen"] = 103 }
            n == 2 -> { songs += local("cherry", "Cherry Juice", "The Thad Jones/Mel Lewis Orchestra").copy(durationMs = 330_000)
                remote += LastFmTrack("Dark Boson", "Thad Jones & Mel Lewis Orchestra - Cherry Juice", 98, durationMs = 331_000); expected["cherry"] = 98 }
            n in 3..202 -> { songs += local("custom$n", "Walking Through City Night $n", "Custom artist $n", "City Sessions $n").copy(durationMs = 250_000)
                remote += LastFmTrack("Custom artist $n", "Walking Through the City at Night $n", n + 1L,
                    album = if (n % 2 == 0) "City Sessions $n" else null, durationMs = if (n % 2 == 1) 251_000 else null)
                expected["custom$n"] = n + 1L }
            n == 203 -> { songs += local("uncertain-title", "An Unusual Nightingale Waltz", "Known artist"); remote += LastFmTrack("Unknown uploader", "An Unusual Nightingale Waltz", 1500) }
            n == 204 -> { songs += local("uncertain-artist", "Ocean View", "Faint Signal"); remote += LastFmTrack("Faint Signal", "Ocean View After Midnight", 250) }
            else -> remote += LastFmTrack("Not owned $n", "Absent $n", n + 1L)
        } }
        listOf(7L, 900L, 80L).forEachIndexed { n, plays ->
            songs += local("ambiguous$n-a", "Ambiguous $n", album = "First album")
            songs += local("ambiguous$n-b", "Ambiguous $n", album = "Another album")
            remote += LastFmTrack("Artist", "Ambiguous $n", plays)
        }
        val result = match(remote, songs)
        assertEquals(15451, remote.size)
        println("FIXTURE clear=" + result.clearCount + " probable=" + result.judgments.size + " review=" + result.review.size + " absent=" + result.notInLibrary)
        println("REVIEW sample=" + result.review.take(3).map { it.remote.title to it.reason })
        expected.forEach { (id, plays) -> assertEquals("Count for $id", plays, result.counts[id]) }
        assertTrue("Unexpected assignments: ${result.counts.keys.minus(expected.keys).take(10)}", result.counts.keys.minus(expected.keys).isEmpty())
        assertEquals(5, result.review.size)
        assertEquals(6302, result.clearCount); assertEquals(201, result.judgments.size); assertEquals(8943, result.notInLibrary)
        assertEquals(listOf(1500L, 900L, 250L, 80L, 7L), result.review.map { it.remote.plays })
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
        assertTrue(result.review.all { it.tier == LastFmMatchTier.UNCERTAIN })
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
    @Test fun exactRecordingIdIncludesUntaggedEditionCopiesButNeverAConflictingKnownId() {
        val original = local("original", "Song").copy(musicBrainzId = "recording-a")
        val deluxe = local("deluxe", "Song", album = "Album (Deluxe)")
        val compilation = local("compilation", "Song", album = "Collection").copy(musicBrainzId = "recording-a")
        val compilationEdition = local("compilation-edition", "Song", album = "Collection (Remastered)")
        val different = local("different", "Song", album = "Another recording").copy(musicBrainzId = "recording-b")
        val result = match(listOf(LastFmTrack("Old credit", "Old title", 42, "Album", "RECORDING-A")),
            listOf(original, deluxe, compilation, compilationEdition, different))
        assertEquals(mapOf("original" to 42L, "deluxe" to 42L, "compilation" to 42L, "compilation-edition" to 42L), result.counts)
        assertTrue(result.review.isEmpty())
        val uncertain = match(listOf(LastFmTrack("Artist", "Song", 9)), listOf(original, deluxe, different))
        assertTrue(uncertain.counts.isEmpty()); assertEquals(1, uncertain.review.size)
    }
    @Test fun exactOperatorExamplesAreFoundAndUploaderInferenceIsAuditableAndReversible() {
        val remote = listOf(LastFmTrack("Kendrick Lamar", "Mirror", 117),
            LastFmTrack("Kendrick Lamar", "6:16 In La (Drake Diss)", 103),
            LastFmTrack("Dark Boson", "Thad Jones & Mel Lewis Orchestra - Cherry Juice", 98, durationMs = 331_000))
        val songs = listOf(local("mirror", "18. Mirror [HD].m4a", "Kendrick Lamar"),
            local("six", "6:16 in LA", "Kendrick Lamar"),
            local("cherry", "Cherry Juice", "The Thad Jones/Mel Lewis Orchestra").copy(durationMs = 330_000))
        val result = match(remote, songs)
        assertEquals(mapOf("mirror" to 117L, "six" to 103L, "cherry" to 98L), result.counts)
        assertEquals(2, result.clearCount); assertEquals(1, result.judgments.size)
        assertTrue(result.judgments.single().reason.contains("upload")); assertTrue(result.judgments.single().reason.contains("duration"))
        val undone = LastFmMatcher.match(LastFmSnapshot("u", 1, remote), songs, mapOf(remote.last().key to LastFmMatcher.REVIEW))
        assertFalse(undone.counts.containsKey("cherry")); assertEquals(remote.last(), undone.review.single().remote)
        assertEquals(LastFmMatchTier.UNCERTAIN, undone.review.single().tier)
    }

    @Test fun contextualCluesNeverOverrideDifferentVersionsPartsSongsArtistsOrKnownIds() {
        val songs = listOf(local("mirror", "Mirror", "Artist", "Album").copy(durationMs = 200_000),
            local("live-a", "Long Song (Live at Wembley)", "Artist", "Album").copy(durationMs = 200_000),
            local("part-two", "Suite Part II", "Artist", "Album").copy(durationMs = 200_000),
            local("intro", "Intro", "Artist", "Album").copy(durationMs = 200_000),
            local("interlude", "Interlude", "Artist", "Album").copy(durationMs = 200_000),
            local("known", "Known Song", "Artist", "Album").copy(durationMs = 200_000, musicBrainzId = "id-a"))
        val remote = listOf(LastFmTrack("Artist", "Mirror Mirror", 10, "Album", durationMs = 200_000),
            LastFmTrack("Different artist", "Mirror", 10, "Album", durationMs = 200_000),
            LastFmTrack("Artist", "Long Song [Live at Montreux]", 10, "Album", durationMs = 200_000),
            LastFmTrack("Artist", "Suite Part I", 10, "Album", durationMs = 200_000),
            LastFmTrack("Different artist", "Intro", 10, durationMs = 200_000),
            LastFmTrack("Artist", "Known Song", 10, "Album", "id-b", 200_000))
        val result = match(remote, songs)
        assertTrue("Zero automatic false assignments, including PROBABLE", result.counts.isEmpty())
        assertTrue(result.resolutions.isEmpty()); assertTrue(result.review.all { it.tier == LastFmMatchTier.UNCERTAIN })
    }
}

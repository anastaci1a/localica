package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LastFmMatchingReportTest {
    private fun song(id: String, album: String) = Track("content://private-uri/$id", "Mirror", "Kendrick Lamar", album,
        256_000, "private-file.m4a", "private-directory/private-file.m4a", id)

    @Test fun ambiguousCandidatesExportTheActualScoresAndReasonsWithoutAccountOrPathData() {
        val remote = LastFmTrack("Kendrick Lamar", "Mirror", 117)
        val library = listOf(song("private-id-1", "First album"), song("private-id-2", "Second album"))
        val snapshot = LastFmSnapshot("PRIVATE_ACCOUNT", 123, listOf(remote), mapOf("private-counter-id" to 9))
        val json = LastFmMatchingReport.json(snapshot, library, emptyMap())
        val root = JSONObject(json)
        assertEquals("localica.lastfm-matching", root.getString("format")); assertEquals(1, root.getInt("version"))
        assertEquals(1, root.getInt("reviewCount"))
        val row = root.getJSONArray("review").getJSONObject(0)
        assertEquals("Several plausible local recordings", row.getString("reason"))
        assertEquals(2, row.getInt("candidateCount")); assertFalse(row.getBoolean("explicitlyUndone"))
        val candidates = row.getJSONArray("candidates")
        val ids = (0 until candidates.length()).map { candidates.getJSONObject(it).getString("id") }
        assertEquals(2, ids.distinct().size); assertTrue(ids.all { it.matches(Regex("[0-9a-f]{64}")) })
        // Document URIs embed folder and file names; an unsalted digest could be confirmed by guessing a path.
        val plain = library.map { track -> java.security.MessageDigest.getInstance("SHA-256")
            .digest(track.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
        assertTrue(ids.none { it in plain })
        val again = JSONObject(LastFmMatchingReport.json(snapshot, library, emptyMap())).getJSONArray("review")
            .getJSONObject(0).getJSONArray("candidates")
        assertTrue((0 until again.length()).map { again.getJSONObject(it).getString("id") }.none { it in ids })
        for (index in 0 until candidates.length()) {
            val candidate = candidates.getJSONObject(index)
            assertEquals(75, candidate.getInt("score")); assertEquals("CLEAR", candidate.getString("tier"))
            assertEquals("artist + title", candidate.getString("reason"))
            assertEquals("kendrick lamar", candidate.getString("normalizedArtist"))
        }
        listOf("PRIVATE_ACCOUNT", "private-uri", "private-id", "private-file", "private-directory", "private-counter-id", "api_key", "username", "mappings")
            .forEach { assertFalse("Excluded field: $it", json.contains(it)) }
        assertEquals(1, LastFmMatcher.match(snapshot, library).review.size)
        assertTrue(LastFmMatcher.match(snapshot, library).counts.isEmpty())
    }

    @Test fun explicitlyUndoneSingleSuggestionKeepsItsActualReasonAndScore() {
        val remote = LastFmTrack("Kendrick Lamar", "Mirror", 117, "Mr. Morale & The Big Steppers")
        val snapshot = LastFmSnapshot("PRIVATE_ACCOUNT", 1, listOf(remote))
        val row = JSONObject(LastFmMatchingReport.json(snapshot, listOf(song("copy", remote.album!!)),
            mapOf(remote.key to LastFmMatcher.REVIEW))).getJSONArray("review").getJSONObject(0)
        assertTrue(row.getBoolean("explicitlyUndone")); assertEquals(1, row.getInt("candidateCount"))
        assertEquals("Automatic match undone by you", row.getString("reason"))
        assertEquals(90, row.getJSONArray("candidates").getJSONObject(0).getInt("score"))
    }

    @Test fun emptyArchiveExportsAnEmptyReport() {
        val json = JSONObject(LastFmMatchingReport.json(null, emptyList(), emptyMap()))
        assertEquals(0, json.getInt("reviewCount")); assertEquals(0, json.getJSONArray("review").length())
    }
}

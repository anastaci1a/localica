package io.github.anastaci1a.localica.artistphoto

import org.junit.Assert.*
import org.junit.Test

class ArtistPhotoTest {
    @Test fun sameNameArtistNeedsLocalReleaseOverlap() {
        val local = ArtistPhotoRequest("artist:1", "  BÉYONCÉ ", setOf("Lemonade"), "album:latest")
        assertFalse(ArtistPhotoMatching.accepts(local, "Béyoncé", listOf("Unrelated")))
        assertTrue(ArtistPhotoMatching.accepts(local, "béyoncé", listOf(" LEMONADE ")))
        assertFalse(ArtistPhotoMatching.accepts(local, "Another artist", listOf("Lemonade")))
        assertFalse(ArtistPhotoMatching.accepts(local.copy(localReleases = emptySet()), "béyoncé", emptyList()))
    }
}

package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArtistPhotoReadinessTest {
    @Test fun aLoadingArtistCannotSpendItsRetryWindowBeforeLibraryRowsArrive() = runBlocking {
        val root = Files.createTempDirectory("artist-photo-readiness").toFile()
        val consent = object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }
        val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "matches"), 8L * 1024 * 1024)
        var requests = 0
        val photos = ArtistPhotos(consent, disk, PhotoTransport { url, _ ->
            requests++
            if (url.contains("deezer")) """{"data":[]}""".toByteArray() else """{"artists":[]}""".toByteArray()
        }, { 100_000L })
        try {
            for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
                artistPhotoRequest(MediaRef(kind, "fixture"), "Fixture Artist", emptyList(), "album")?.let {
                    photos.observe(it).toList()
                }
            }
            assertEquals("An initial empty BrowseResult must not trigger provider requests", 0, requests)
            for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist))
                assertNull("Empty loading rows must not persist a 24-hour retry throttle", disk.choice("$kind:fixture").attemptedAt)
        } finally { root.deleteRecursively() }
    }

    @Test fun knownRowsStillAllowManualPhotoChoicesWithoutAlbumTags() {
        val track = Track("fixture", "Song", "Fixture Artist", fileName = "song.mp3", relativePath = "song.mp3")
        for (kind in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
            val request = artistPhotoRequest(MediaRef(kind, "fixture"), "Fixture Artist", listOf(track), "fallback")!!
            assertEquals("$kind:fixture", request.artistKey)
            assertEquals("fallback", request.latestAlbumArt)
            assertTrue(request.localReleases.isEmpty())
        }
        assertNull(artistPhotoRequest(MediaRef(MediaKind.Album, "fixture"), "Album", listOf(track), "fallback"))
    }
}

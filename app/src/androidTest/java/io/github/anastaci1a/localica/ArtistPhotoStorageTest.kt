package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic protocol envelopes only. Exercises Android's real filesystem and offline reopen. */
class ArtistPhotoStorageTest {
    @Test fun atomicPhotoCacheAndManualClearSurviveReopenWithoutNetworkConsent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "artist-photo-test-${java.util.UUID.randomUUID()}")
        root.mkdirs()
        try {
            val consent = OnlineMetadataConsent(File(root, "consent"))
            consent.load(); assertFalse(consent.allowed.value)
            consent.setAllowed(true)
            val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "matches"), 8L * 1024 * 1024)
            var calls = 0
            val transport = PhotoTransport { url, _ ->
                calls++
                when {
                    url.contains("/search/artist?") -> """{"data":[{"id":8,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/fixture.jpg"}]}""".toByteArray()
                    url.contains("/top?") -> """{"data":[{"album":{"title":"Fixture Album"}}]}""".toByteArray()
                    url.contains("cdn-images") -> byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
                    else -> error("Unexpected fixture request")
                }
            }
            val request = ArtistPhotoRequest("artist:fixture", "Fixture Artist", setOf("Fixture Album"), "album:fixture")
            val photo = ArtistPhotos(consent, disk, transport).observe(request).last().photo!!
            assertTrue(photo.file.isFile); assertEquals(3, calls)
            consent.setAllowed(false)
            val restored = OnlineMetadataConsent(File(root, "consent")); restored.load()
            val offline = ArtistPhotos(restored, disk, PhotoTransport { _, _ -> error("No network allowed") })
            assertEquals(photo, offline.observe(request).last().photo)
            offline.clear(request.artistKey)
            assertEquals(ArtistPhotoState("album:fixture"), ArtistPhotos(restored, disk, transport).observe(request).last())
            assertEquals(3, calls)
        } finally { root.deleteRecursively() }
    }
}

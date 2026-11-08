package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArtistPhotoCacheControlsTest {
    @Test fun refreshAndCacheClearPreserveManualAndClearedChoicesAndRespectConsent() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-controls-${java.util.UUID.randomUUID()}")
        val consent = OnlineMetadataConsent(File(root, "consent"))
        val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024)
        var calls = 0
        val transport = PhotoTransport { url, _ ->
            calls++
            when {
                url.contains("/artist/8/top?") -> """{"data":[{"album":{"title":"Fixture Album"}}]}""".toByteArray()
                url.contains("/artist/8") -> """{"id":8,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/fixture.jpg"}""".toByteArray()
                url.contains("cdn-images") -> byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
                else -> error("Unexpected request $url")
            }
        }
        val photos = ArtistPhotos(consent, disk, transport)
        val chosen = ArtistPhotoRequest("Artist:chosen", "Fixture Artist", setOf("Fixture Album"), "album")
        val cleared = chosen.copy(artistKey = "Artist:cleared")
        try {
            consent.load(); consent.setAllowed(true)
            photos.pick(chosen.artistKey, ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"))
            photos.clear(cleared.artistKey)
            photos.refreshAll(listOf(chosen, cleared))
            assertEquals(3, calls)
            assertTrue(photos.cacheBytes() > 0)
            assertEquals("manual", disk.choice(chosen.artistKey).mode)
            assertEquals("cleared", disk.choice(cleared.artistKey).mode)
            // Explicit refresh bypasses the 30-day cache lifetime while preserving the chosen provider.
            photos.refreshAll(listOf(chosen, cleared))
            assertEquals(6, calls)
            photos.clearCache()
            assertEquals(0L, photos.cacheBytes())
            assertEquals(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), disk.choice(chosen.artistKey).choice)
            assertEquals("cleared", disk.choice(cleared.artistKey).mode)
            consent.setAllowed(false)
            photos.refreshAll(listOf(chosen, cleared))
            assertEquals(6, calls)
            val reopened = ArtistPhotos(consent, disk, transport)
            assertNull(reopened.observe(cleared).last().photo)
            assertEquals(6, calls)
        } finally { root.deleteRecursively() }
    }
}

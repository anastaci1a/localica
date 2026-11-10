package io.github.anastaci1a.localica.artistphoto

import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtistPhotoIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val consent = object : NetworkConsent {
        override val allowed = MutableStateFlow(false)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private fun disk() = ArtistPhotoDisk(File(temporary.root, "cache"), File(temporary.root, "choices"), 8L * 1024 * 1024)
    private fun repo(disk: ArtistPhotoDisk) = ArtistPhotos(consent, disk, PhotoTransport { _, _ -> error("Index must not fetch") })
    private val remote = RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), "Artist", emptyList())
    private val bytes = byteArrayOf(-1, -40, -1, 0)

    @Test fun startupIndexesLegacyHashedAutomaticAndDurablePhotosAndDistinguishesUnknownFromMissing() = runBlocking {
        val disk = disk()
        val automatic = disk.savePhoto("Artist:auto", remote, bytes, 1)
        disk.saveChoice("AlbumArtist:manual", SavedPhotoChoice("manual", remote.choice))
        val manual = disk.savePhoto("AlbumArtist:manual", remote, bytes, 2)
        val photos = repo(disk())
        val missing = artistPhotoCacheKey("Artist:missing")
        assertNull(photos.peekKey(missing))
        assertFalse(photos.indexReady.value)
        photos.loadIndex()
        assertTrue(photos.indexReady.value)
        assertEquals(automatic, photos.peekKey(artistPhotoCacheKey("Artist:auto"))?.photo)
        assertEquals(manual, photos.peekKey(artistPhotoCacheKey("AlbumArtist:manual"))?.photo)
        assertEquals(ArtistPhotoState(null), photos.peekKey(missing))
        // A memory lookup must not stat/reopen either the metadata or the image.
        automatic.file.delete()
        assertEquals(automatic, photos.peekKey(artistPhotoCacheKey("Artist:auto"))?.photo)
    }

    @Test fun clearResetAndCacheEvictionInvalidateTheIndexButPreserveManualChoices() = runBlocking {
        val disk = disk()
        disk.savePhoto("auto", remote, bytes, 1)
        disk.saveChoice("manual", SavedPhotoChoice("manual", remote.choice))
        disk.savePhoto("manual", remote, bytes, 2)
        val photos = repo(disk)
        photos.loadIndex()
        photos.clearCache()
        assertNull(photos.peekKey(artistPhotoCacheKey("auto"))?.photo)
        assertNotNull(photos.peekKey(artistPhotoCacheKey("manual"))?.photo)
        photos.clear("manual")
        assertNull(photos.peekKey(artistPhotoCacheKey("manual"))?.photo)
        val reopened = repo(disk())
        reopened.loadIndex()
        assertNull(reopened.peekKey(artistPhotoCacheKey("manual"))?.photo)
        disk.savePhoto("auto", remote, bytes, 3)
        photos.ready(ArtistPhotoRequest("auto", "Artist", emptySet()))
        assertNotNull(photos.peekKey(artistPhotoCacheKey("auto"))?.photo)
        photos.reset("auto")
        assertNull(photos.peekKey(artistPhotoCacheKey("auto"))?.photo)
    }
}

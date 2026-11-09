package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.anastaci1a.localica.artistphoto.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ArtistPhotoWorkTest {
    @Test fun productionWorkerPromotesCachedPickAndRetriesDecodeFailureKeepingThePreviousPhoto() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalicaApplication
        app.onlineMetadataConsent.load()
        val previousConsent = app.onlineMetadataConsent.allowed.value
        val request = ArtistPhotoRequest("Artist:worker-${UUID.randomUUID()}", "Fixture Artist", setOf("Fixture Album"))
        val disk = ArtistPhotoDisk(File(app.cacheDir, "artist-photos"), File(app.filesDir, "artist-photo-matches"), 512L * 1024 * 1024)
        val jobs = ArtistPhotoJobs(File(app.filesDir, "artist-photo-work"))
        val first = ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8")
        val second = ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "9")
        fun pixels(color: Int): PreparedArtistPhoto {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            return ArtistPhotoEncoding.encode(bytes)
        }
        suspend fun waitFor(predicate: suspend () -> Boolean) = withTimeout(30_000) {
            while (!predicate()) delay(50)
        }
        try {
            app.onlineMetadataConsent.setAllowed(false)
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(app).cancelUniqueWork("artist-photo-acquisition").result.get()
                disk.savePreview(RemoteArtist(first, request.name, request.localReleases.toList()), pixels(android.graphics.Color.GREEN), 1)
            }
            app.artistPhotos.pick(request.artistKey, first)
            app.onlineMetadataConsent.setAllowed(true)
            app.artistPhotoWork.enqueue(request, refresh = true)
            waitFor { app.artistPhotos.selection(request.artistKey).let { it.current == first && it.pending == null } }
            val previous = requireNotNull(app.artistPhotos.ready(request).photo)
            val previousBytes = withContext(Dispatchers.IO) { previous.file.readBytes() }
            withContext(Dispatchers.IO) {
                disk.savePreview(RemoteArtist(second, request.name, request.localReleases.toList()),
                    PreparedArtistPhoto(byteArrayOf(-1, -40, -1, 0)), 2)
            }
            app.artistPhotos.pick(request.artistKey, second)
            app.artistPhotoWork.enqueue(request, refresh = true)
            waitFor { request.artistKey in app.artistPhotoWork.status.value.failed }
            assertArrayEquals(previousBytes, withContext(Dispatchers.IO) { requireNotNull(app.artistPhotos.ready(request).photo).file.readBytes() })
            assertEquals(second, app.artistPhotos.selection(request.artistKey).pending)
            withContext(Dispatchers.IO) {
                disk.savePreview(RemoteArtist(second, request.name, request.localReleases.toList()), pixels(android.graphics.Color.RED), 3)
            }
            app.artistPhotoWork.retry()
            waitFor { app.artistPhotos.selection(request.artistKey).let { it.current == second && it.pending == null } }
            waitFor { request.artistKey !in app.artistPhotoWork.status.value.failed }
            app.artistPhotos.clearCache()
            assertNotNull("Chosen ready pixels survive Clear cache", app.artistPhotos.ready(request).photo)
        } finally {
            app.onlineMetadataConsent.setAllowed(false)
            withContext(Dispatchers.IO) { WorkManager.getInstance(app).cancelUniqueWork("artist-photo-acquisition").result.get() }
            app.artistPhotos.clear(request.artistKey)
            withContext(Dispatchers.IO) { jobs.all().filter { it.request.artistKey == request.artistKey }.forEach { jobs.complete(it, true) } }
            app.onlineMetadataConsent.setAllowed(previousConsent)
        }
    }
}

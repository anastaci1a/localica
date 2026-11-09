package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.anastaci1a.localica.artistphoto.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Host-driven seed → real SIGKILL during commit → restart → verify on a disposable emulator. */
class ArtistPhotoProcessDeathFixture {
    @Test fun persistentWorkerSurvivesHostControlledProcessDeath(): Unit = runBlocking(Dispatchers.IO) {
        val phase = InstrumentationRegistry.getArguments().getString("artistPhotoProcessDeathPhase")
        assumeTrue("Requires the separate disposable-emulator process-death driver", phase != null)
        require(phase in listOf("seed", "verify"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalicaApplication
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val request = ArtistPhotoRequest("Artist:process-death-712", "Process Death Fixture", setOf("Fixture Album"))
        val choice = ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "712")
        val root = File(app.filesDir, "artist-photo-matches")
        val disk = ArtistPhotoDisk(File(app.cacheDir, "artist-photos"), root, 512L * 1024 * 1024)
        val jobs = ArtistPhotoJobs(File(app.filesDir, "artist-photo-work"))
        val witness = File(app.filesDir, "artist-photo-process-death-712.json")
        if (phase == "seed") {
            app.onlineMetadataConsent.setAllowed(false)
            WorkManager.getInstance(app).cancelUniqueWork("artist-photo-acquisition").result.get()
            app.artistPhotos.clear(request.artistKey)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            disk.savePreview(RemoteArtist(choice, request.name, request.localReleases.toList()), ArtistPhotoEncoding.encode(bytes), 1)
            app.artistPhotos.pick(request.artistKey, choice)
            val selection = root.listFiles().orEmpty().single { file ->
                file.extension == "json" && JSONObject(file.readText()).optJSONObject("pendingChoice")?.optString("id") == "712"
            }
            val pending = File(root, selection.name + ".pending")
            check(!pending.exists())
            Os.mkfifo(pending.absolutePath, 384)
            jobs.enqueue(request, refresh = true)
            witness.writeText(JSONObject().put("selection", selection.name)
                .put("fifo", pending.absolutePath).put("seedPid", android.os.Process.myPid()).toString())
            // Prepare next-process consent without launching work inside this instrumentation process.
            File(app.filesDir, "online-metadata-consent").writeText("allowed\n")
        } else {
            val selection = app.artistPhotos.selection(request.artistKey)
            assertEquals(choice, selection.current)
            assertNull(selection.pending)
            assertFalse(jobs.all().any { it.request.artistKey == request.artistKey })
            val photo = requireNotNull(app.artistPhotos.ready(request).photo)
            assertTrue(photo.file.absolutePath.startsWith(File(root, "photos").absolutePath))
            val bitmap = android.graphics.BitmapFactory.decodeFile(photo.file.absolutePath)
            assertTrue(android.graphics.Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 240)
            bitmap.recycle()
            app.onlineMetadataConsent.setAllowed(false)
            app.artistPhotos.clear(request.artistKey)
            witness.delete()
        }
    }
}

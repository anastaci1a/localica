package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ArtworkSaveResult
import io.github.anastaci1a.localica.library.ArtworkSaver
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.ArtworkViewerContent
import io.github.anastaci1a.localica.ui.LocalicaTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Saves real original bytes to the public Downloads folder and reads them back from the platform. */
class ArtworkSaveTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tag = "T${System.nanoTime()}"
    private val created = mutableListOf<File>()
    private val saved = mutableListOf<String>()

    @Before fun grant() {
        if (Build.VERSION.SDK_INT < 29) instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.WRITE_EXTERNAL_STORAGE").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } }
    }

    @After fun cleanup() {
        created.forEach { it.delete() }
        saved.forEach { name ->
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name))
            else @Suppress("DEPRECATION") File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).delete()
        }
    }

    private fun cover(name: String, format: Bitmap.CompressFormat, color: Int): Pair<String, ByteArray> {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(format, 95, it) }.toByteArray()
        val dir = File(context.filesDir, "playlist-covers").apply { mkdirs() }
        val file = File(dir, name).also { it.writeBytes(bytes); created += it }
        return Uri.fromFile(file).toString() to bytes
    }

    private fun readBack(name: String): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) @Suppress("DEPRECATION") return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).takeIf { it.exists() }?.readBytes()
        val resolver = context.contentResolver
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null)?.use {
            if (it.moveToFirst()) return resolver.openInputStream(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)))?.use { s -> s.readBytes() }
        }
        return null
    }

    private fun save(uri: String, artist: String?, album: String?) = runBlocking { ArtworkSaver.save(context, uri, artist, album) }

    @Test fun pngKeepsOriginalBytesFormatAndSanitizedName() {
        val (uri, bytes) = cover("$tag.png", Bitmap.CompressFormat.PNG, android.graphics.Color.RED)
        val result = save(uri, "$tag Ar/tist", "Al:bum?")
        assertTrue(result.toString(), result is ArtworkSaveResult.Saved)
        val name = (result as ArtworkSaveResult.Saved).name.also { saved += it }
        assertEquals("$tag Ar_tist - Al_bum_.png", name)
        assertArrayEquals(bytes, readBack(name))
    }

    @Test fun jpegKeepsOriginalBytesAndSecondSaveIsDeduplicated() {
        val (uri, bytes) = cover("$tag.jpg", Bitmap.CompressFormat.JPEG, android.graphics.Color.BLUE)
        val first = save(uri, "$tag Artist", "Album") as ArtworkSaveResult.Saved
        val second = save(uri, "$tag Artist", "Album") as ArtworkSaveResult.Saved
        saved += first.name; saved += second.name
        assertEquals("$tag Artist - Album.jpg", first.name)
        assertNotEquals("A repeated save never overwrites", first.name, second.name)
        assertTrue(second.name.endsWith(".jpg"))
        assertArrayEquals(bytes, readBack(first.name))
        assertArrayEquals(bytes, readBack(second.name))
    }

    @Test fun missingArtworkFailsWithoutWritingAFile() {
        val missing = Uri.fromFile(File(File(context.filesDir, "playlist-covers"), "$tag-none.png")).toString()
        assertEquals(ArtworkSaveResult.NoArtwork, save(missing, "$tag Artist", "Nothing"))
        assertNull(readBack("$tag Artist - Nothing.jpg"))
    }

    @Test fun viewerShowsSaveInPlaceOfPlusAndReportsTheTap() {
        var taps = 0
        val art = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 80, 40)) }
        compose.setContent { LocalicaTheme {
            Box(Modifier.size(260.dp, 360.dp).background(Color.Black)) { ArtworkViewerContent(art.asImageBitmap(), false, {}, onSave = { taps++ }) }
        } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("Zoom in").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Save artwork").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        val shot = compose.onNodeWithTag("artwork-viewer").captureToImage().asAndroidBitmap()
        val dir = nativeEvidenceDirectory("artwork-save", File(context.cacheDir, "artwork-save").apply { mkdirs() })
        File(dir, "viewer-api${Build.VERSION.SDK_INT}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun saveIsDisabledWhileArtworkIsMissing() {
        compose.setContent { LocalicaTheme { Box(Modifier.size(260.dp, 360.dp)) { ArtworkViewerContent(null, false, {}) } } }
        compose.onNodeWithContentDescription("Save artwork").assertIsNotEnabled()
    }
}

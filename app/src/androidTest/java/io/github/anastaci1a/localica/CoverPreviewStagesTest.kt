package io.github.anastaci1a.localica

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real chooser/default crop loading; no replacement loader or platform permission mutation. */
class CoverPreviewStagesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realPreviewAndDefaultCropReportStagesAndFreshChooserReloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("cover-images", 0)
        val originalRecent = preferences.getString("recent", null)
        val input = File.createTempFile("preview-stages-", ".png", context.cacheDir)
        val firstPreviewEntries = AtomicInteger()
        val secondPreviewEntries = AtomicInteger()
        val chosen = AtomicInteger()
        val cropEvents = CopyOnWriteArrayList<CropLoadEvent>()
        val firstPreviewObserver: () -> Unit = { firstPreviewEntries.incrementAndGet(); Unit }
        val secondPreviewObserver: () -> Unit = { secondPreviewEntries.incrementAndGet(); Unit }
        val cropObserver: (CropLoadEvent) -> Unit = { cropEvents.add(it); Unit }
        var visible by mutableStateOf(true)
        var secondOwner by mutableStateOf(false)
        var primary: Throwable? = null
        try {
            val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            val originalBytes = input.readBytes()
            assertTrue(preferences.edit().remove("recent").commit())
            CoverImages(context).remember(Uri.fromFile(input).toString())
            compose.setContent {
                val host = LocalContext.current
                val deniedPhotos = remember(host) { object : ContextWrapper(host) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> PackageManager.PERMISSION_DENIED
                        else -> super.checkPermission(permission, pid, uid)
                    }
                } }
                CompositionLocalProvider(LocalContext provides deniedPhotos,
                    LocalImagePreviewDecodeObserver provides if (secondOwner) secondPreviewObserver else firstPreviewObserver,
                    LocalCropLoadObserver provides cropObserver) {
                    LocalicaTheme {
                        if (visible) ImageChooser({ visible = false }, { chosen.incrementAndGet() })
                    }
                }
            }
            fun awaitPreview(entries: AtomicInteger) {
                compose.waitUntil(10_000) {
                    entries.get() > 0 &&
                        compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size == 1
                }
            }
            awaitPreview(firstPreviewEntries)
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
            compose.onNodeWithContentDescription("Choose recent image").performClick()
            compose.waitUntil(10_000) {
                cropEvents.any { it.phase == CropLoadPhase.Ready } &&
                    compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Use square crop").assertIsEnabled()
            compose.runOnIdle {
                val starts = cropEvents.filter { it.phase == CropLoadPhase.Started }
                assertEquals(1, starts.size)
                val generation = starts.single().generation
                for (phase in listOf(CropLoadPhase.Reading, CropLoadPhase.Decoding, CropLoadPhase.Ready))
                    assertEquals("Default crop stage $phase belongs to its one generation", 1,
                        cropEvents.count { it.generation === generation && it.phase == phase })
                assertFalse(cropEvents.any { it.phase == CropLoadPhase.Failed || it.phase == CropLoadPhase.Cancelled })
                assertTrue(firstPreviewEntries.get() > 0)
                assertEquals(0, secondPreviewEntries.get())
                assertEquals(0, chosen.get())
                visible = false
            }
            compose.waitUntil(10_000) {
                cropEvents.any { it.phase == CropLoadPhase.Disposed } &&
                    compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().isEmpty()
            }
            compose.runOnIdle { secondOwner = true; visible = true }
            awaitPreview(secondPreviewEntries)
            compose.runOnIdle {
                assertTrue(firstPreviewEntries.get() > 0)
                assertTrue(secondPreviewEntries.get() > 0)
                assertEquals(1, cropEvents.count { it.phase == CropLoadPhase.Started })
                assertEquals(0, chosen.get())
            }
            assertArrayEquals(originalBytes, input.readBytes())
            Log.i("CoverPreviewWork", "firstOwnerDecoderEntries=${firstPreviewEntries.get()} secondOwnerDecoderEntries=${secondPreviewEntries.get()} cropReading=" +
                "${cropEvents.count { it.phase == CropLoadPhase.Reading }} cropDecoding=" +
                "${cropEvents.count { it.phase == CropLoadPhase.Decoding }} cropReady=" +
                "${cropEvents.count { it.phase == CropLoadPhase.Ready }} cropDisposed=" +
                "${cropEvents.count { it.phase == CropLoadPhase.Disposed }} chosen=${chosen.get()}")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            val bodyFailure = primary
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (bodyFailure != null) { if (bodyFailure !== failure) bodyFailure.addSuppressed(failure) }
                    else if (cleanupFailure == null) cleanupFailure = failure
                    else if (cleanupFailure !== failure) cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { compose.runOnIdle { visible = false }; compose.waitForIdle() }
            cleanup {
                val edit = preferences.edit()
                if (originalRecent == null) edit.remove("recent") else edit.putString("recent", originalRecent)
                check(edit.commit()) { "Could not restore original recent-cover preference" }
            }
            cleanup { check(!input.exists() || input.delete()) { "Could not remove preview fixture" } }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }
}

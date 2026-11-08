package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real editor replacement while the previous recent-preview file read remains blocked. */
class ArtworkChooserPendingReadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get() = (context.applicationContext as LocalicaApplication).tagEditing
    private fun track(name: String) = Track("file:///missing-pending-preview.mp3", name,
        fileName = "missing-pending-preview.mp3", relativePath = "missing-pending-preview.mp3", id = name)

    @Test fun replacingEditorWithAnOldPreviewReadPendingLoadsTheNewRecentImage() {
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val png = ByteArrayOutputStream().also { bytes ->
            val bitmap = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.CYAN)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            } finally { bitmap.recycle() }
        }.toByteArray()
        val pipe = File(context.cacheDir, "pending-preview-${System.nanoTime()}.png")
        Os.mkfifo(pipe.path, 384)
        val replacement = File.createTempFile("replacement-preview-", ".png", context.cacheDir)
        replacement.writeBytes(png)
        val readerConnected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerFinished = AtomicBoolean(false)
        val writer = thread(isDaemon = true, name = "pending-preview-writer") {
            try {
                runCatching {
                    FileOutputStream(pipe).use { output ->
                        readerConnected.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                        output.write(png)
                    }
                }
            } finally { writerFinished.set(true) }
        }
        var primary: Throwable? = null
        try {
            assertTrue(preferences.edit().remove("recent").commit())
            CoverImages(context).remember(Uri.fromFile(pipe).toString())
            compose.runOnUiThread { controller.open(listOf(track("Pending old session"))) }
            compose.onNode(hasSetTextAction() and hasText("Pending old session"))
                .performScrollTo().performTextReplacement("Discard this pending draft")
            compose.onNodeWithContentDescription("Choose artwork").performScrollTo().performClick()
            compose.waitUntil(10_000) { readerConnected.count == 0L }
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
            assertEquals(1L, release.count)
            compose.onAllNodesWithContentDescription("Choose recent image").assertCountEquals(0)
            android.util.Log.i("Chooser660Session", "old reader connected; replacing real editor while read is held")
            assertTrue(preferences.edit().remove("recent").commit())
            val uri = Uri.fromFile(replacement).toString()
            CoverImages(context).remember(uri)
            val before = controller.state.value.sessionId
            compose.runOnUiThread { controller.close(); controller.openArtwork(track("Replacement session")) }
            assertNotEquals(before, controller.state.value.sessionId)
            val tag = "recent-artwork-$uri"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
            assertEquals("New session loads before the old read is released", 1L, release.count)
            assertFalse("The old read still has its connected writer", writerFinished.get())
            android.util.Log.i("Chooser660Session", "replacement recent visible while old read remains held")
            release.countDown()
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag(tag))
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().size == 1 }
            compose.runOnUiThread { controller.openArtwork(track("Third session")) }
            compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
            assertEquals("Third session", controller.state.value.tracks.single().id)
            assertTrue(controller.state.value.results.isEmpty())
            compose.onAllNodesWithContentDescription("New artwork preview").assertCountEquals(0)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            release.countDown()
            val bodyFailure = primary
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (bodyFailure != null) bodyFailure.addSuppressed(failure)
                    else if (cleanupFailure == null) cleanupFailure = failure
                    else cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { compose.runOnUiThread { controller.close() }; compose.waitForIdle() }
            cleanup { Os.close(Os.open(pipe.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)) }
            cleanup { writer.join(5000); check(!writer.isAlive) }
            cleanup {
                val edit = preferences.edit()
                if (original == null) edit.remove("recent") else edit.putString("recent", original)
                check(edit.commit())
            }
            cleanup { check(pipe.delete()) }
            cleanup { check(replacement.delete()) }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }
}

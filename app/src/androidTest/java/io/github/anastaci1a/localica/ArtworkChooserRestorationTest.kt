package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * #660: MainActivity recreated with the artwork chooser open, while its recent image is still being
 * read. The acceptance hit restored the chooser with 'Open gallery' and 'Browse files' at zero height.
 */
class ArtworkChooserRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get() = (context.applicationContext as LocalicaApplication).tagEditing

    /**
     * A recent image backed by a named pipe: the chooser's real decoder opens it like any file, and
     * the open waits until this writer serves the PNG. Nothing in the app is replaced.
     */
    private class HeldImage(directory: File, private val png: ByteArray) : AutoCloseable {
        val file = File(directory, "held-artwork-${System.nanoTime()}.png").also { Os.mkfifo(it.path, 384) }
        private val lock = Object()
        private var released = true
        private var closed = false
        private val writer = thread(name = "held-artwork-writer") {
            while (true) {
                synchronized(lock) {
                    while (!released && !closed) lock.wait()
                    if (closed) return@thread
                }
                // Blocks until a reader opens the pipe.
                val output = runCatching { FileOutputStream(file) }.getOrNull() ?: return@thread
                output.use { stream ->
                    if (synchronized(lock) { released && !closed }) runCatching { stream.write(png) }
                }
            }
        }
        fun hold() { synchronized(lock) { released = false }; wakeWriter() }
        fun release() { synchronized(lock) { released = true; lock.notifyAll() } }
        override fun close() {
            synchronized(lock) { closed = true; lock.notifyAll() }
            wakeWriter()
            writer.join(5000)
            file.delete()
        }
        // A writer waiting for a reader returns once anyone opens the read end.
        private fun wakeWriter() {
            runCatching { Os.close(Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)) }
        }
    }

    private fun recentRows() = compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size

    private fun assertActionsReachable(cycle: Int) {
        for (label in listOf("Open gallery", "Browse files")) {
            // GlassActionButton merges its label, so the button node carries text and OnClick.
            val action = compose.onNode(hasText(label) and hasClickAction())
            action.performScrollTo()
            action.assertIsDisplayed().assertHasClickAction()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("Restore $cycle: '$label' keeps a tappable height after the late recent image: $bounds",
                bounds.height > 0f)
        }
    }

    @Test fun restoredChooserKeepsActionsReachableWhenItsRecentImageArrivesLate() {
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val png = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.rgb(40, 120, 200))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } finally { bitmap.recycle() }
        }.toByteArray()
        val image = HeldImage(context.cacheDir, png)
        try {
            assertTrue(preferences.edit().remove("recent").commit())
            CoverImages(context).remember(Uri.fromFile(image.file).toString())
            compose.runOnUiThread {
                controller.open(listOf(Track("file:///missing-chooser-restoration.mp3", "Chooser restoration song",
                    fileName = "missing-chooser-restoration.mp3", relativePath = "missing-chooser-restoration.mp3",
                    id = "Chooser restoration song")))
            }
            compose.onNodeWithContentDescription("Choose artwork").performScrollTo().performClick()
            compose.waitUntil(10_000) { recentRows() >= 1 }
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
            repeat(2) { index ->
                val cycle = index + 1
                image.hold()
                compose.activityRule.scenario.recreate()
                compose.waitForIdle()
                // The restored chooser settles before its recent image can be read.
                compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
                assertEquals("Restore $cycle: recent image is still held", 0, recentRows())
                image.release()
                compose.waitUntil(10_000) { recentRows() >= 1 }
                compose.waitForIdle()
                assertActionsReachable(cycle)
            }
        } finally {
            image.release()
            runCatching { compose.runOnUiThread { controller.close() }; compose.waitForIdle() }
            image.close()
            preferences.edit().apply { if (original == null) remove("recent") else putString("recent", original) }.commit()
        }
    }
}

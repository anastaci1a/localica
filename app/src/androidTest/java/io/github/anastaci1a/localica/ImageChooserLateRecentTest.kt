package io.github.anastaci1a.localica

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #660: a recent image that arrives after the chooser's first layout must not squeeze its
 * gallery and files actions to zero height. The real decoder is held at its observer until the
 * chooser has settled without the recent row, then released.
 */
class ImageChooserLateRecentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recentArrivingAfterFirstLayoutKeepsGalleryAndFilesReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("cover-images", 0)
        val originalRecent = preferences.getString("recent", null)
        val input = File.createTempFile("late-recent-", ".png", context.cacheDir)
        val release = CountDownLatch(1)
        // Runs on the decoder's IO thread; bounded so a broken test can never hang the run.
        val held: () -> Unit = { release.await(20, TimeUnit.SECONDS); Unit }
        var visible by mutableStateOf(true)
        var primary: Throwable? = null
        try {
            val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            assertTrue(preferences.edit().remove("recent").commit())
            CoverImages(context).remember(Uri.fromFile(input).toString())
            compose.setContent {
                val host = LocalContext.current
                // As in the API 26 hit: no photo permission, so the permission prompt is shown too.
                val deniedPhotos = remember(host) { object : ContextWrapper(host) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> PackageManager.PERMISSION_DENIED
                        else -> super.checkPermission(permission, pid, uid)
                    }
                } }
                CompositionLocalProvider(LocalContext provides deniedPhotos, LocalImagePreviewDecodeObserver provides held) {
                    LocalicaTheme { if (visible) ImageChooser({ visible = false }, {}) }
                }
            }
            // First layout without the recent row: both actions are laid out and the window is ready.
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
            compose.onNodeWithText("Allow recent photos").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().isEmpty())
            release.countDown()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size == 1
            }
            compose.waitForIdle()
            for (label in listOf("Open gallery", "Browse files")) {
                // GlassActionButton merges its label, so the button node itself carries text and OnClick.
                val action = compose.onNode(hasText(label) and hasClickAction())
                action.performScrollTo()
                action.assertIsDisplayed().assertHasClickAction()
                val bounds = action.fetchSemanticsNode().boundsInRoot
                assertTrue("$label keeps a tappable height after late recent content: $bounds", bounds.height > 0f)
                assertTrue("$label exposes OnClick", action.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            release.countDown()
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

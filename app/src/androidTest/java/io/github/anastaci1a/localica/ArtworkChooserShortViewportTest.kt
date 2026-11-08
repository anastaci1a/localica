package io.github.anastaci1a.localica

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real chooser body under the short viewport observed in #660, without a native IME race. */
class ArtworkChooserShortViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacingChooserDuringDecodeKeepsLateRecentAndActionsReachable() = replaceChooser(inline = true)

    @Test fun replacingNativeChooserWhileOldDecodeIsPendingLoadsNewRecentImage() = replaceChooser(inline = false)

    private fun replaceChooser(inline: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val first = File.createTempFile("short-chooser-first-", ".png", context.cacheDir)
        val second = File.createTempFile("short-chooser-second-", ".png", context.cacheDir)
        val oldDecode = CountDownLatch(1)
        val newDecode = CountDownLatch(1)
        val started = AtomicInteger()
        var visible by mutableStateOf(true)
        var target by mutableStateOf("first")
        var primary: Throwable? = null
        try {
            for ((file, color) in listOf(first to android.graphics.Color.MAGENTA, second to android.graphics.Color.CYAN)) {
                val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            assertTrue(preferences.edit().remove("recent").commit())
            CoverImages(context).remember(Uri.fromFile(first).toString())
            compose.setContent {
                val host = LocalContext.current
                val denied = remember(host) { object : ContextWrapper(host) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> PackageManager.PERMISSION_DENIED
                        else -> super.checkPermission(permission, pid, uid)
                    }
                } }
                CompositionLocalProvider(LocalContext provides denied,
                    LocalImagePreviewDecodeObserver provides {
                        val gate = if (started.incrementAndGet() == 1) oldDecode else newDecode
                        check(gate.await(20, TimeUnit.SECONDS)) { "Preview decode gate was not released" }
                    }) {
                    LocalicaTheme {
                        // The native hit's surface was approximately 388 x 353 dp. Inline mode
                        // retains ImageChooser/GlassAlertDialog/DialogViewport, controlling only space.
                        Box(Modifier.width(388.dp).height(353.dp)) {
                            CompositionLocalProvider(LocalInlineGlassDialog provides inline) {
                                if (visible) key(target) {
                                    ImageChooser(rememberArtworkWorkflow(target, startChoosing = true), { visible = false }, {})
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { started.get() > 0 }
            val priorStarts = started.get()
            compose.onAllNodesWithContentDescription("Choose recent image").assertCountEquals(0)
            if (inline) {
                compose.runOnIdle { visible = false }
                compose.onNodeWithText("Choose artwork").assertDoesNotExist()
            }
            assertTrue(preferences.edit().remove("recent").commit())
            CoverImages(context).remember(Uri.fromFile(second).toString())
            compose.runOnIdle { target = "replacement"; visible = true }
            compose.waitUntil(10_000) { started.get() > priorStarts }
            oldDecode.countDown()
            newDecode.countDown()
            val expected = "recent-artwork-${Uri.fromFile(second)}"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(expected).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("recent-artwork-${Uri.fromFile(first)}").assertDoesNotExist()
            for (label in listOf("Open gallery", "Browse files")) {
                val action = compose.onNode(hasText(label) and hasClickAction())
                action.performScrollTo().assertIsDisplayed().assertHasClickAction()
                val bounds = action.getUnclippedBoundsInRoot()
                assertTrue("$label keeps its full touch target in the short chooser",
                    bounds.bottom - bounds.top >= 48.dp)
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            oldDecode.countDown(); newDecode.countDown()
            val cleanup = runCatching {
                compose.runOnIdle { visible = false }
                compose.waitForIdle()
                val edit = preferences.edit()
                if (original == null) edit.remove("recent") else edit.putString("recent", original)
                check(edit.commit())
                check(first.delete()); check(second.delete())
            }.exceptionOrNull()
            if (cleanup != null) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}

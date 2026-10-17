package io.github.anastaci1a.localica

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Deterministic saved-composition test of the real editor; not Activity recreation. */
class ArtworkReviewReadinessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredConfirmationCannotSaveUntilReloadedCoverProducesReplacePatch() = restoredReview(false)
    @Test fun failedCoverReloadLeavesRestoredConfirmationUnableToSave() = restoredReview(true)

    private fun restoredReview(failReload: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as LocalicaApplication).tagEditing
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val input = File.createTempFile("review-readiness-", ".png", context.cacheDir)
        val crops = File(context.cacheDir, "artwork-crops")
        val existing = crops.listFiles()?.toSet().orEmpty()
        val reload = CompletableDeferred<CoverImage>()
        var initialCover: CoverImage? = null
        var reads = 0
        val saves = mutableListOf<TagPatch>()
        try {
            Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            preferences.edit().remove("recent").commit()
            CoverImages(context).remember(Uri.fromFile(input).toString())
            val state = mutableStateOf(TagEditorState(tracks = listOf(Track("file:///unused-review.mp3", "Review fixture",
                fileName = "unused-review.mp3", relativePath = "unused-review.mp3")), artworkOnly = true, sessionId = "review-fixture"))
            val restore = StateRestorationTester(compose)
            restore.setContent {
                val host = LocalContext.current
                val deniedPhotos = object : ContextWrapper(host) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> PackageManager.PERMISSION_DENIED
                        else -> super.checkPermission(permission, pid, uid)
                    }
                }
                CompositionLocalProvider(LocalContext provides deniedPhotos) { LocalicaTheme {
                    TagEditorDialog(state.value, controller, readCover = { uri ->
                        reads++
                        if (reads == 1) CoverImages(context).read(uri).also { initialCover = it }
                        else reload.await()
                    }, onSave = { saves += it })
                } }
            }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size == 1 }
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
            compose.onNodeWithContentDescription("Choose recent image").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().isNotEmpty() }
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("artwork-crop"))
            compose.onNodeWithText("Use square crop").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("New artwork preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Review changes").performClick()
            restore.emulateSavedInstanceStateRestore()
            compose.waitUntil(10000) { reads == 2 }
            compose.onNodeWithText("Save changes to 1 files?").assertIsDisplayed()
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Save changes"))
            compose.onNodeWithText("Save changes").assertIsNotEnabled().performClick()
            compose.runOnIdle { assertTrue(saves.isEmpty()) }
            if (failReload) {
                compose.runOnIdle { reload.completeExceptionally(IllegalStateException("Fixture cover unavailable")) }
                compose.waitForIdle()
                compose.onNodeWithText("Save changes").assertIsNotEnabled().performClick()
                compose.runOnIdle { assertTrue(saves.isEmpty()) }
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithText("Fixture cover unavailable").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Review changes").assertIsNotEnabled()
            } else {
                compose.runOnIdle { reload.complete(requireNotNull(initialCover)) }
                compose.waitForIdle()
                compose.runOnIdle { state.value = state.value.copy(busy = true) }
                compose.onNodeWithText("Save changes").assertIsNotEnabled().performClick()
                compose.runOnIdle {
                    assertTrue(saves.isEmpty())
                    state.value = state.value.copy(busy = false, recovery = "fixture original")
                }
                compose.onNodeWithText("Save changes").assertIsNotEnabled().performClick()
                compose.runOnIdle {
                    assertTrue(saves.isEmpty())
                    state.value = state.value.copy(recovery = null)
                }
                compose.onNodeWithText("Save changes").assertIsEnabled().performClick()
                compose.runOnIdle {
                    assertEquals(1, saves.size)
                    val artwork = saves.single().artwork
                    assertTrue(artwork is ArtworkChange.Replace)
                    assertArrayEquals(requireNotNull(initialCover).bytes, (artwork as ArtworkChange.Replace).image.bytes)
                    assertTrue(saves.single().fields.isEmpty())
                }
            }
        } finally {
            reload.cancel()
            preferences.edit().putString("recent", original).commit()
            input.delete()
            crops.listFiles()?.filterNot { it in existing }?.forEach { it.delete() }
        }
    }
}

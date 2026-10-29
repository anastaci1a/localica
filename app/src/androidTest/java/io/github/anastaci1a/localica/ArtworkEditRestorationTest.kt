package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** These tests recreate MainActivity, including its real TagEditorHost and dialog windows. */
class ArtworkEditRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get() = (context.applicationContext as LocalicaApplication).tagEditing
    private fun track(name: String = "Restoration song") = Track("file:///missing-restoration-song.mp3", name,
        fileName = "missing-restoration-song.mp3", relativePath = "missing-restoration-song.mp3", id = name)

    private fun withImage(block: () -> Unit) {
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val input = File.createTempFile("restoration-source-", ".png", context.cacheDir)
        val crops = File(context.cacheDir, "artwork-crops")
        val existing = crops.listFiles()?.toSet().orEmpty()
        try {
            Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).let { bitmap ->
                measureNativeSuiteCost("artwork-pixel-initialization") {
                    // Preserve every opaque fixture pixel, avoiding per-pixel JNI writes.
                    val pixels = IntArray(bitmap.width * bitmap.height) { index ->
                        val x = index % bitmap.width
                        val y = index / bitmap.width
                        android.graphics.Color.rgb(x * 255 / 600, y * 255 / 300, 80)
                    }
                    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            preferences.edit().remove("recent").commit()
            val fixtureUri = Uri.fromFile(input).toString()
            fixtureTag = "recent-artwork-$fixtureUri"
            CoverImages(context).remember(fixtureUri)
            fixtureIndex = kotlinx.coroutines.runBlocking {
                CoverImages(context).recent(includeDevicePhotos = true).indexOf(fixtureUri)
            }
            // The fixture is last in the remembered-only list; device photos, if permitted,
            // precede it. Use the actual permission state for the chooser's list shape.
            val photoPermission = if (android.os.Build.VERSION.SDK_INT >= 33)
                android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
            val canReadPhotos = androidx.core.content.ContextCompat.checkSelfPermission(context, photoPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                (android.os.Build.VERSION.SDK_INT >= 34 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            if (!canReadPhotos) fixtureIndex = 0
            block()
        } finally {
            compose.runOnUiThread { controller.close() }
            compose.waitForIdle()
            preferences.edit().putString("recent", original).commit()
            input.delete()
            crops.listFiles()?.filterNot { it in existing }?.forEach { it.delete() }
        }
    }

    private var fixtureIndex = 0
    private var fixtureTag = ""
    private fun chooseRecent() {
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.awaitExpectedWindowFocus(compose.onNodeWithText("Open gallery"))
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasContentDescription("Choose recent image")))
            .performScrollToIndex(fixtureIndex)
        compose.waitUntil(10000) { compose.onAllNodesWithTag(fixtureTag).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(fixtureTag).performClick()
        awaitCrop()
    }
    private fun awaitCrop() {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("artwork-crop"))
    }
    private fun recreate() { compose.activityRule.scenario.recreate(); compose.waitForIdle() }
    private fun assertZoom(value: Float) {
        assertEquals(value, compose.onNodeWithContentDescription("Crop zoom").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
    }

    @Test fun normalEditorRestoresChooserAndAdjustedCropRepeatedlyWithTextDraft() = withImage {
        compose.runOnUiThread { controller.open(listOf(track())) }
        compose.onNode(hasSetTextAction() and hasText("Restoration song"))
            .performScrollTo().performTextReplacement("Unsaved title")
        compose.onNodeWithContentDescription("Choose artwork").performScrollTo().performClick()
        repeat(2) {
            recreate()
            compose.onNodeWithText("Open gallery").assertIsDisplayed()
        }
        chooseRecent()
        compose.onNodeWithContentDescription("Crop zoom").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithTag("artwork-crop").performTouchInput { swipe(center, center.copy(x = center.x + width / 8f)) }
        repeat(2) { recreate(); awaitCrop(); assertZoom(2f) }
        val crops = File(context.cacheDir, "artwork-crops")
        val before = crops.listFiles()?.toSet().orEmpty()
        compose.onNodeWithText("Use square crop").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("New artwork preview").fetchSemanticsNodes().isNotEmpty() }
        val output = requireNotNull(crops.listFiles()).single { it !in before }
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(output.path))
        try {
            assertEquals("Restored 2x zoom determines output geometry", 150, bitmap.width)
            assertEquals(bitmap.width, bitmap.height)
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            // At default center this gradient is red=127. The rightward pan moves the
            // source center left; losing center state on either recreation returns 127.
            assertTrue("Restored pan must move the source center left", android.graphics.Color.red(pixel) in 112..123)
            assertTrue(android.graphics.Color.green(pixel) in 125..130)
        } finally { bitmap.recycle() }
        compose.onNodeWithContentDescription("Choose artwork").performScrollTo().performClick()
        chooseRecent()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open gallery").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasSetTextAction() and hasText("Unsaved title")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        assertTrue(controller.state.value.results.isEmpty())
    }

    @Test fun artworkOnlyReturnsToReviewAfterRecreationAndCancelKeepsChosenImage() = withImage {
        compose.runOnUiThread { controller.openArtwork(track()) }
        chooseRecent()
        compose.onNodeWithText("Use square crop").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("New artwork preview").fetchSemanticsNodes().isNotEmpty() }
        repeat(2) {
            recreate()
            compose.onNodeWithText("Change artwork").assertIsDisplayed()
            compose.onNodeWithText("Open gallery").assertDoesNotExist()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("New artwork preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("New artwork preview").assertIsDisplayed()
            compose.onNodeWithText("Review changes").assertIsEnabled()
        }
        compose.onNodeWithText("Review changes").performClick()
        recreate()
        compose.onNodeWithText("Save changes to 1 files?").assertIsDisplayed()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Save changes") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save changes").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("New artwork preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose artwork").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("New artwork preview").assertIsDisplayed()
        compose.onNodeWithText("Close editor").performClick()
        assertTrue(controller.state.value.tracks.isEmpty())
        assertTrue(controller.state.value.results.isEmpty())
    }

    @Test fun closingReopeningAndReplacingTargetDiscardPreviousSession() = withImage {
        compose.runOnUiThread { controller.open(listOf(track())) }
        val firstSession = controller.state.value.sessionId
        compose.onNode(hasSetTextAction() and hasText("Restoration song"))
            .performScrollTo().performTextReplacement("Discard this draft")
        compose.onNodeWithContentDescription("Choose artwork").performScrollTo().performClick()
        chooseRecent()
        compose.runOnUiThread { controller.close(); controller.open(listOf(track())) }
        assertNotEquals(firstSession, controller.state.value.sessionId)
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Restoration song")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Discard this draft").assertDoesNotExist()
        compose.onNodeWithText("Review changes").assertIsNotEnabled()
        compose.runOnUiThread { controller.openArtwork(track()) }
        chooseRecent()
        compose.runOnUiThread { controller.openArtwork(track("Replacement song")) }
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        recreate()
        compose.onNodeWithText("Open gallery").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(controller.state.value.tracks.isEmpty())
    }
}

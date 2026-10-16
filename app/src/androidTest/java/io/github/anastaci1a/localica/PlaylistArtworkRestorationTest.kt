package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Saved-composition restoration of the production playlist action, not Activity recreation. */
class PlaylistArtworkRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedChooserAndAdjustedCropRestoreAndCancellationNeverAppliesCover() = withPlaylistActions { restore, controller, _ ->
        openChooser()
        repeat(2) {
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Open gallery").assertIsDisplayed()
        }
        chooseRecent()
        compose.onNodeWithContentDescription("Crop zoom").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        repeat(2) {
            restore.emulateSavedInstanceStateRestore()
            awaitCrop()
            assertEquals(3f, compose.onNodeWithContentDescription("Crop zoom").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open gallery").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open gallery").assertDoesNotExist()
        compose.runOnIdle { assertTrue(controller.state.value.playlists.all { it.coverUri == null }) }
        openChooser()
        chooseRecent()
        // A new selection starts with fresh geometry after cancellation.
        assertEquals(1f, compose.onNodeWithContentDescription("Crop zoom").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        restore.emulateSavedInstanceStateRestore()
        awaitCrop()
        val targetId = controller.state.value.playlists.first().id
        compose.onNodeWithText("Use square crop").performClick()
        compose.waitUntil(10000) { controller.state.value.playlists.first { it.id == targetId }.coverUri != null }
        val cover = controller.state.value.playlists.first { it.id == targetId }.coverUri!!
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(Uri.parse(cover).path))
        try { assertEquals(300, bitmap.width); assertEquals(bitmap.width, bitmap.height) }
        finally { bitmap.recycle() }
        assertTrue(controller.state.value.playlists.filter { it.id != targetId }.all { it.coverUri == null })
    }

    @Test fun replacingPlaylistTargetDropsOldCropAndDoesNotApplyEitherCover() = withPlaylistActions { restore, controller, target ->
        openChooser()
        chooseRecent()
        compose.runOnIdle { target.value = controller.state.value.playlists.last().id }
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        compose.onNodeWithText("Open gallery").assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("artwork-crop").assertDoesNotExist()
        openChooser()
        compose.onNodeWithText("Open gallery").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(controller.state.value.playlists.all { it.coverUri == null }) }
    }

    @Test fun savedWorkflowForAnotherTargetIsRejectedBeforeMountingCrop() {
        val saved = listOf("Playlist:old", ArtworkStage.Crop.name, "file:///old-crop-source.png", "")
        assertNull(ArtworkWorkflow.saver("Playlist:new").restore(saved))
        val restored = requireNotNull(ArtworkWorkflow.saver("Playlist:old").restore(saved))
        assertEquals(ArtworkStage.Crop, restored.stage)
        assertEquals("file:///old-crop-source.png", restored.cropUri)
    }

    private fun openChooser() {
        compose.onNodeWithText("Open collection").performClick()
        compose.onNodeWithText("Set playlist image").performScrollTo().performClick()
    }
    private var fixtureIndex = 0
    private var fixtureTag = ""
    private fun chooseRecent() {
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().isNotEmpty() }
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

    private fun withPlaylistActions(block: (StateRestorationTester, PlaylistController, MutableState<String>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val input = File.createTempFile("playlist-restoration-", ".png", context.cacheDir)
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(database.playlists(), scope)
        val covers = File(context.filesDir, "playlist-covers")
        val oldCovers = covers.listFiles()?.toSet().orEmpty()
        val crops = File(context.cacheDir, "artwork-crops")
        val oldCrops = crops.listFiles()?.toSet().orEmpty()
        try {
            Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.BLUE)
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
            runBlocking {
                withTimeout(5000) { controller.state.first { !it.loading } }
                for (name in listOf("First restoration playlist", "Second restoration playlist")) {
                    val created = CompletableDeferred<Boolean>()
                    controller.create(name) { created.complete(it) }
                    assertTrue(withTimeout(5000) { created.await() })
                }
            }
            val target = mutableStateOf(controller.state.value.playlists.first().id)
            val restore = StateRestorationTester(compose)
            restore.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                val playlist = state.playlists.first { it.id == target.value }
                var open by remember { mutableStateOf(false) }
                val anchor = rememberGlassAnchor()
                ActionTextButton({ open = true }, modifier = Modifier.glassAnchor(anchor)) { Text("Open collection") }
                CollectionActions(open, anchor, BrowserItem(MediaRef(MediaKind.Playlist, playlist.id), playlist.name,
                    null, emptyList()), state, controller, null, emptyList(), {}, { open = false })
            } }
            block(restore, controller, target)
            // Inspect committed storage too, rather than trusting only the visible state.
            runBlocking {
                assertEquals(controller.state.value.playlists.associate { it.id to it.coverUri },
                    database.playlists().read().associate { it.id to it.coverUri })
            }
        } finally {
            scope.cancel()
            database.close()
            preferences.edit().putString("recent", original).commit()
            input.delete()
            covers.listFiles()?.filterNot { it in oldCovers }?.forEach { it.delete() }
            crops.listFiles()?.filterNot { it in oldCrops }?.forEach { it.delete() }
        }
    }
}

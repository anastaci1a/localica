package io.github.anastaci1a.localica

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import io.github.anastaci1a.localica.ui.ImageChooser
import io.github.anastaci1a.localica.ui.LocalicaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageChooserTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readablePreviousChoiceRemainsAvailableWithoutPhotoPermissionAndStillRequiresCrop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        val input = File(context.cacheDir, "remembered-artwork.png")
        var chosen: String? = null
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.runOnUiThread {
            registry = androidx.lifecycle.LifecycleRegistry(owner)
            registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        try {
            Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).let { bitmap ->
                input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            preferences.edit().remove("recent").commit()
            CoverImages(context).apply {
                remember(Uri.fromFile(input).toString())
                remember(Uri.fromFile(File(context.cacheDir, "missing-artwork.png")).toString())
            }
            compose.setContent {
                val hostContext = LocalContext.current
                val deniedContext = object : ContextWrapper(hostContext) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> PackageManager.PERMISSION_DENIED
                        else -> super.checkPermission(permission, pid, uid)
                    }
                }
                CompositionLocalProvider(LocalContext provides deniedContext, androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                    LocalicaTheme { ImageChooser({}, { chosen = it }) }
                }
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size == 1
            }
            val unavailable = "Some recent images are unavailable. Choose them again using gallery or files."
            compose.waitUntil(10000) { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(unavailable).assertIsDisplayed()
            // Resume changes the chooser revision even when the remembered URI list is unchanged.
            compose.runOnIdle {
                registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText(unavailable).assertIsDisplayed()
            compose.onNodeWithText("Allow recent photos").assertIsDisplayed()
            compose.onNodeWithText("Open gallery").assertIsDisplayed()
            compose.onNodeWithText("Browse files").assertIsDisplayed()
            compose.onNodeWithContentDescription("Choose recent image").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().isNotEmpty() }
            assertNull(chosen)
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("Choose recent image").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("Choose recent image").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use square crop").performClick()
            compose.waitUntil(10000) { chosen != null }
            assertNotEquals(Uri.fromFile(input).toString(), chosen)
        } finally {
            preferences.edit().putString("recent", original).commit()
            input.delete()
            chosen?.let { File(Uri.parse(it).path!!).delete() }
        }
    }
}

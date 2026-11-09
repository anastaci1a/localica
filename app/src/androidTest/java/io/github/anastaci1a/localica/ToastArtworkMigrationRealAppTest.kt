package io.github.anastaci1a.localica

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.toast.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Both migrated callers on API26; real save caller on API29+, where legacy permission is absent. */
class ToastArtworkMigrationRealAppTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LocalicaApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= end) throw AssertionError("Timed out: $label")
            SystemClock.sleep(16)
        }
    }
    private fun visible(selector: BySelector): UiObject2 = requireNotNull(device.wait(Until.findObject(selector), 15000)) { "Missing native node: $selector" }
    private fun screenshot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "toast-art713").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
        device.dumpWindowHierarchy(File(folder, "$name.xml"))
    }
    private fun openViewer() {
        visible(By.desc("Collapse player"))
        var cover: UiObject2? = null
        await("native artwork hold target") {
            cover = device.findObjects(By.longClickable(true)).filter { it.visibleBounds.width() > 100 && it.visibleBounds.height() > 100 }
                .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            cover != null
        }
        requireNotNull(cover).longClick()
        visible(By.desc("Close artwork"))
        await("artwork decoded before save") { device.findObject(By.desc("Save artwork"))?.isEnabled == true }
    }
    @Test fun actualSaveAndLegacyDenialUseRootToastAfterViewerModalRetires() {
        val prefix = "toast-art713-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$prefix.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 190)) }
        val bytes = java.io.ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }.toByteArray()
        bitmap.recycle()
        TagCodec.edit(file, TagPatch(fields = mapOf(EditField.Artist to "Toast713", EditField.Album to prefix, EditField.Title to "Toast artwork song"), artwork = ArtworkChange.Replace(CoverImage(bytes, "image/jpeg", 128, 128))))
        val track = Track(Uri.fromFile(file).toString(), "Toast artwork song", artist = "Toast713", album = prefix,
            fileName = file.name, relativePath = file.name)
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        var playback: PlaybackConnection? = null
        var savedName: String? = null
        var ownedQueues = emptySet<String>()
        try {
            preferences.write(InterfaceSettings(artworkHold = ArtworkAction.View, expandOnPlay = true, reducedMotion = true))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
                val connection = requireNotNull(playback)
                await("real media connection") { connection.state.value.connected && !app.queues.state.value.loading }
                val previousQueues = app.queues.state.value.library.queues.map { it.id }.toSet()
                instrumentation.runOnMainSync { connection.clear() }
                await("empty native player") { connection.state.value.trackUri == null }
                instrumentation.runOnMainSync { connection.playFromList(listOf(track), track.id) }
                await("native player and embedded cover") { connection.state.value.trackUri == track.uri && connection.state.value.durationMs != null }
                ownedQueues = app.queues.state.value.library.queues.map { it.id }.toSet() - previousQueues
                instrumentation.runOnMainSync { app.queues.playbackRequested(false) }
                openViewer()
                if (Build.VERSION.SDK_INT < 29) {
                    assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    visible(By.desc("Save artwork")).click()
                    visible(By.res("com.android.packageinstaller", "permission_deny_button")).click()
                    await("legacy denial outcome") { app.toasts.state.value.current?.content?.message == "Storage permission is needed to save artwork" }
                    assertTrue(ToastPause.Modal in app.toasts.state.value.pauses)
                    assertNull(device.findObject(By.res("toast713-body")))
                    screenshot("legacy-denial-modal")
                    visible(By.desc("Close artwork")).click()
                    visible(By.res("toast713-body"))
                    assertEquals(ToastKind.Error, app.toasts.state.value.current!!.content.kind)
                    screenshot("legacy-denial-root-toast")
                    visible(By.res("toast713-body")).swipe(Direction.LEFT, 1f)
                    await("denial toast actual retirement") { app.toasts.state.value.current == null }
                    openViewer()
                }
                visible(By.desc("Save artwork")).click()
                if (Build.VERSION.SDK_INT < 29) visible(By.res("com.android.packageinstaller", "permission_allow_button")).click()
                await("saved original artwork outcome") { app.toasts.state.value.current?.content?.message?.startsWith("Saved to Downloads: ") == true }
                val outcome = app.toasts.state.value.current!!
                assertEquals(ToastKind.Success, outcome.content.kind)
                savedName = outcome.content.message.removePrefix("Saved to Downloads: ")
                assertTrue(requireNotNull(savedName).contains(prefix))
                assertTrue(ToastPause.Modal in app.toasts.state.value.pauses)
                assertNull(device.findObject(By.res("toast713-body")))
                screenshot("saved-outcome-stays-under-viewer-modal")
                visible(By.desc("Close artwork")).click()
                visible(By.res("toast713-body"))
                assertEquals(outcome.id, app.toasts.state.value.current!!.id)
                screenshot("saved-root-toast-after-viewer")
                val saved = if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = context.contentResolver
                    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(requireNotNull(savedName)), null)!!.use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                        resolver.openInputStream(uri)!!.use { input -> input.readBytes() }
                    }
                } else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), requireNotNull(savedName)).readBytes()
                assertArrayEquals("Migration preserves original artwork bytes", bytes, saved)
            }
        } finally {
            playback?.let { connection ->
                instrumentation.runOnMainSync { connection.clear() }
                await("release fixture playback") { connection.state.value.trackUri == null }
                instrumentation.runOnMainSync { app.queues.removeQueues(ownedQueues); connection.close() }
            }
            savedName?.takeIf { it.contains(prefix) }?.let { name ->
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
                else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).delete()
            }
            preferences.write(original)
            file.delete()
        }
    }
}

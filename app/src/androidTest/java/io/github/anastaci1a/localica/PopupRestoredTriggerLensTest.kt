package io.github.anastaci1a.localica

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect as AndroidRect
import android.view.View
import android.view.Window
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** #847: once an ordinary return restores the browse options pill, the popup window no longer draws over it. */
class PopupRestoredTriggerLensTest {
    @get:Rule val compose = localicaComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences = InterfacePreferences(instrumentation.targetContext)
    private val previous = preferences.read()

    @After fun restorePreferences() = preferences.write(previous)

    @Test fun retractEndNeverLensesTheRestoredTrigger() {
        val output = File(nativeEvidenceDirectory("popup-restored-trigger-lens", instrumentation.targetContext.getExternalFilesDir(null)),
            "browse-options").apply { mkdirs() }
        preferences.write(InterfaceSettings(reducedMotion = false, fasterMotion = false))
        val track = Track("file:///missing-847.mp3", "One", artist = "Test", album = "One",
            fileName = "missing-847.mp3", relativePath = "missing-847.mp3")
        lateinit var host: View
        var popup: Window? = null
        compose.setContent { LocalicaTheme {
            host = LocalView.current
            CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, _ ->
                if (kind == "dialog" && window != null && popup == null) popup = window
            }) {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                    PlaybackState(connected = true, trackId = track.id, trackUri = track.id,
                        title = track.title, artist = track.artist, durationMs = 125000),
                    {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
            }
        } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("browse-options-trigger", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val activity = compose.runOnUiThread {
            var owner = host.context
            while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
            (owner as Activity).window
        }
        fun crop(window: Window): AndroidRect = compose.runOnUiThread {
            val hostAt = IntArray(2).also { host.getLocationOnScreen(it) }
            val windowAt = IntArray(2).also { window.decorView.getLocationOnScreen(it) }
            val dx = hostAt[0] - windowAt[0]; val dy = hostAt[1] - windowAt[1]
            AndroidRect(bounds.left.roundToInt() + dx, bounds.top.roundToInt() + dy,
                bounds.right.roundToInt() + dx, bounds.bottom.roundToInt() + dy)
        }
        // Icon ink: opaque dark pixels, the Sort and View glyphs drawn by the pill or by the popup's face and lens.
        fun ink(window: Window): Int {
            val rect = crop(window)
            val image = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            copyNativeWindowOnce(window, image, "PixelCopy timed out", rect) { "PixelCopy failed: $it" }
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            image.recycle()
            return pixels.count { p ->
                AndroidColor.alpha(p) > 128 && .2126f * AndroidColor.red(p) + .7152f * AndroidColor.green(p) + .0722f * AndroidColor.blue(p) < 80f
            }
        }
        // The test clock drives composition; both windows still present on real vsyncs.
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); Thread.sleep(64) }
        val samples = JSONArray()
        val report = JSONObject().put("source", BuildConfig.SOURCE_SHA).put("samples", samples)
        fun save() = File(output, "frames.json").writeText(report.toString(2))
        try {
            val restingInk = ink(activity)
            report.put("restingActivityInk", restingInk)
            assertTrue("The resting pill draws its icons ($restingInk)", restingInk > 20)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Sort").performClick()
            compose.waitUntil(10_000) {
                frame()
                popup?.decorView?.isAttachedToWindow == true && compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isNotEmpty()
            }
            repeat(90) { frame() }
            val window = requireNotNull(popup)
            compose.waitUntil(5000) { window.decorView.hasWindowFocus() }
            UiDevice.getInstance(instrumentation).pressBack()
            var restoredFrames = 0
            var frames = 0
            while (window.decorView.isAttachedToWindow && frames++ < 240) {
                frame()
                if (!window.decorView.isAttachedToWindow) break
                val modal = ink(window)
                val shell = ink(activity)
                val hidden = compose.onAllNodesWithContentDescription("Sort").fetchSemanticsNodes().isEmpty()
                samples.put(JSONObject().put("frame", frames).put("clockMs", compose.mainClock.currentTime)
                    .put("triggerHidden", hidden).put("dialogInk", modal).put("activityInk", shell)); save()
                if (hidden) continue
                restoredFrames++
                assertTrue("Frame $frames: the popup window draws over the restored pill ($modal ink pixels)", modal <= 20)
            }
            report.put("restoredFramesSampled", restoredFrames).put("retiredAfterFrames", frames); save()
            assertFalse("The popup window retires after its return", window.decorView.isAttachedToWindow)
            assertTrue("Frames with the pill restored while the popup window presents were sampled", restoredFrames >= 1)
            val finalInk = ink(activity)
            report.put("finalActivityInk", finalInk); save()
            assertTrue("The restored pill draws its icons ($finalInk)", finalInk > 20)
        } finally {
            save()
        }
    }
}

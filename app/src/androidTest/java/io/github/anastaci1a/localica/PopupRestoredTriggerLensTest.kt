package io.github.anastaci1a.localica

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect as AndroidRect
import android.view.View
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** #847: once an ordinary return restores its trigger, the popup window no longer lenses the trigger's pixels. */
class PopupRestoredTriggerLensTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun retractEndNeverLensesTheRestoredTrigger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(nativeEvidenceDirectory("popup-restored-trigger-lens", context.getExternalFilesDir(null)), "ordinary").apply { mkdirs() }
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var host: View
        var popup: Window? = null
        compose.setContent { LocalicaTheme {
            host = LocalView.current
            anchor = rememberGlassAnchor()
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize()) {
                // The trigger sits inside the recorded shell, as BrowseOptionsPill does in LocalicaScreen.
                Box(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFFB4D8C8)) }
                    LiquidGlassSurface(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 24.dp)
                        .size(CompactControlSize).glassAnchor(anchor).testTag("trigger")) {
                        Box(Modifier.fillMaxSize().padding(10.dp).background(Color.Black))
                    }
                }
                CompositionLocalProvider(LocalModalBackdrop provides source,
                    LocalGlassMorphWorkObserver provides { kind, window, _ -> if (kind == "dialog" && window != null) popup = window }) {
                    GlassMorphPopup(open, anchor, { open = false }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Sort")
                            Spacer(Modifier.height(80.dp))
                            Text("View")
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        assertNull("The trigger has no lens of its own, like the browse options pill", anchor.source)
        val bounds = compose.onNodeWithTag("trigger").fetchSemanticsNode().boundsInRoot
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
        fun ink(window: Window): Int {
            val rect = crop(window)
            val image = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            copyNativeWindowOnce(window, image, "PixelCopy timed out", rect) { "PixelCopy failed: $it" }
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            image.recycle()
            return pixels.count { p ->
                AndroidColor.alpha(p) > 128 && .2126f * AndroidColor.red(p) + .7152f * AndroidColor.green(p) + .0722f * AndroidColor.blue(p) < 50f
            }
        }
        val samples = JSONArray()
        val report = JSONObject().put("source", BuildConfig.SOURCE_SHA).put("samples", samples)
        fun save() = File(output, "frames.json").writeText(report.toString(2))
        compose.mainClock.autoAdvance = false
        try {
            val restingInk = ink(activity)
            report.put("restingActivityInk", restingInk)
            assertTrue("The resting trigger draws its high-contrast ink ($restingInk)", restingInk > 20)
            compose.runOnIdle { open = true }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                popup?.decorView?.isAttachedToWindow == true && anchor.hidden && anchor.modalPresent
            }
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            val window = requireNotNull(popup)
            compose.runOnIdle { open = false }
            var restored = 0
            var frame = 0
            while (window.decorView.isAttachedToWindow && frame++ < 240) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                if (anchor.hidden || !window.decorView.isAttachedToWindow) continue
                val modal = ink(window)
                val shell = ink(activity)
                samples.put(JSONObject().put("frame", frame).put("clockMs", compose.mainClock.currentTime)
                    .put("dialogInk", modal).put("activityInk", shell)); save()
                restored++
                assertTrue("Frame $frame: the popup window lenses the restored trigger ($modal dark pixels)", modal <= 20)
            }
            report.put("restoredFramesSampled", restored).put("retiredAfterFrames", frame); save()
            assertFalse("The popup window retires after its return", window.decorView.isAttachedToWindow)
            assertTrue("The return has frames with the trigger restored while the popup window still presents", restored >= 1)
            val finalInk = ink(activity)
            report.put("finalActivityInk", finalInk); save()
            assertTrue("The restored trigger draws its ink ($finalInk)", finalInk > 20)
        } finally {
            save()
        }
    }
}

package io.github.anastaci1a.localica

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Native frame identity and one original PixelCopy; shared by both popup pixel proofs. */
internal class PopupWindowEvidence(private val compose: ComposeContentTestRule) {
    private val markers = IdentityHashMap<View, Marker>()
    private var stage = 0
    /** Open the real control without moving an anchor deliberately overlapped by chrome. */
    fun openReferenceTrigger(description: String) {
        val node = compose.onNodeWithContentDescription(description)
        if (InstrumentationRegistry.getArguments().getString("popupReferenceOpeningMutant") == "covered-touch") {
            // Negative control: restore the occluded physical touch that cannot reach the trigger.
            node.performClick()
        } else node.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    fun dispose() = compose.runOnUiThread { markers.forEach { (view, marker) -> view.overlay.remove(marker) } }
    fun capture(window: Window, output: File, name: String): Bitmap {
        val view = window.decorView
        val ready = CountDownLatch(1)
        val token = stage % 24 + 1; stage = token
        lateinit var tree: ViewTreeObserver
        var draws = 0
        val committed = Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated
        val watch = ViewTreeObserver.OnDrawListener {
            if (!committed) {
                draws++
                if (draws == 1) view.postOnAnimation { view.invalidate() }
                else view.post { ready.countDown() }
            }
        }
        compose.runOnUiThread {
            tree = view.viewTreeObserver
            tree.addOnDrawListener(watch)
            if (Build.VERSION.SDK_INT >= 29 && committed) tree.registerFrameCommitCallback { ready.countDown() }
            val marker = markers.getOrPut(view) { Marker().also { view.overlay.add(it) } }
            marker.stage = token; marker.invalidateSelf()
            view.invalidate()
        }
        try { assertTrue("Native draw receipt for $name", ready.await(2, TimeUnit.SECONDS)) }
        finally { compose.runOnUiThread { if (tree.isAlive) tree.removeOnDrawListener(watch) } }
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // API 26 passes Window crop coordinates directly to the padded Surface.
        // API 28+ adds these insets inside PixelCopy; compensate only on the old path.
        val crop = android.graphics.Rect(0, 0, view.width, view.height)
        val insets = if (Build.VERSION.SDK_INT < 28) compose.runOnUiThread {
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(view)
            val attributes = root.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }.get(root)
            android.graphics.Rect(android.view.WindowManager.LayoutParams::class.java
                .getField("surfaceInsets").get(attributes) as android.graphics.Rect)
        } else android.graphics.Rect()
        crop.offset(insets.left, insets.top)
        File(output, "$name-copy.json").writeText(JSONObject().put("api", Build.VERSION.SDK_INT)
            .put("crop", crop.toShortString()).put("surfaceInsets", insets.toShortString()).toString(2))
        val result = copyNativeWindowOnce(window, image, "#667 original $name copy timed out",
            sourceBounds = crop,
            failureMessage = { "#667 original $name copy failed: $it; no retry" })
        try {
            File(output, "$name.png").outputStream().use { assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val marker = result.getPixel(8, 104)
            assertTrue("Original $name carries requested frame token $token, actual RGBA=${Color.red(marker)},${Color.green(marker)},${Color.blue(marker)},${Color.alpha(marker)} crop=$crop",
                abs(Color.red(marker) - token * 8) <= 1 && Color.green(marker) >= 254 && abs(Color.blue(marker) - 128) <= 1)
            return result
        } catch (failure: Throwable) { result.recycle(); throw failure }
    }

    private class Marker : Drawable() {
        var stage = 0
        private val paint = Paint()
        init { setBounds(4, 100, 12, 108) }
        override fun draw(canvas: Canvas) { paint.color = Color.rgb(stage * 8, 255, 128); canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { }
        @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

package io.github.anastaci1a.localica

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Native-resolution node crop; flush draw notifications without advancing the Compose clock. */
@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.captureNativeFrame(): ImageBitmap {
    val node = fetchSemanticsNode("Failed to capture a native frame")
    check(generateSequence(node) { it.parent }.none {
        it.config.contains(SemanticsProperties.IsPopup) || it.config.contains(SemanticsProperties.IsDialog)
    }) { "Use the popup/dialog capture harness for separate windows" }
    val view = (node.root as ViewRootForTest).view
    val bounds = node.boundsInRoot
    val crop = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
        bounds.right.roundToInt(), bounds.bottom.roundToInt())
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val drawn = CountDownLatch(1)
    val enabled = HardwareRendererCompat.isDrawingEnabled()
    if (!enabled) HardwareRendererCompat.setDrawingEnabled(true)
    var listener: ViewTreeObserver.OnDrawListener? = null
    try {
        instrumentation.runOnMainSync {
            val origin = IntArray(2)
            view.getLocationInWindow(origin)
            crop.offset(origin[0], origin[1])
            listener = ViewTreeObserver.OnDrawListener {
                // Posting runs after traversal has submitted this draw to the renderer.
                view.post { drawn.countDown() }
            }
            view.viewTreeObserver.addOnDrawListener(listener)
            Snapshot.sendApplyNotifications()
            view.invalidate()
        }
        check(drawn.await(5, TimeUnit.SECONDS)) { "Native frame draw timed out: $crop" }
        val window = view.context.activityWindow()
        return copyNativeWindowOnce(window,
            Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888),
            "Native frame copy timed out: $crop", crop
        ) { code -> "Native frame copy failed: $code ($crop)" }.asImageBitmap()
    } finally {
        instrumentation.runOnMainSync {
            listener?.let { view.viewTreeObserver.removeOnDrawListener(it) }
        }
        if (!enabled) HardwareRendererCompat.setDrawingEnabled(false)
    }
}

private fun Context.activityWindow(): Window = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> error("Native frame capture requires an Activity window")
}

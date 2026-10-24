package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import kotlin.math.floor

/** API 26 cannot capture Dialog roots through Compose. Keep the same pixel
 * assertions by cropping the display using that popup's own LocalView origin.
 * PixelMap owns a copied pixel array; temporary screenshots are always released. */
internal fun capturePopupPixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction, popupView: () -> View): PixelMap =
    capturePopupPixelsObserved(rule, node, popupView, null)

private fun capturePopupPixelsObserved(rule: ComposeContentTestRule, node: SemanticsNodeInteraction,
    popupView: () -> View, retain: ((android.graphics.Bitmap, android.graphics.Rect, View) -> Unit)?): PixelMap {
    if (Build.VERSION.SDK_INT >= 28) {
        val bitmap = node.captureToImage().asAndroidBitmap()
        return try { bitmap.asImageBitmap().toPixelMap() } finally { bitmap.recycle() }
    }
    rule.waitForIdle()
    val bounds = node.fetchSemanticsNode().boundsInRoot
    val origin = IntArray(2)
    lateinit var view: View
    rule.runOnIdle { view = popupView(); view.getLocationOnScreen(origin) }
    val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    var retained = false
    try {
        val left = origin[0] + floor(bounds.left).toInt()
        val top = origin[1] + floor(bounds.top).toInt()
        val right = origin[0] + ceil(bounds.right).toInt()
        val bottom = origin[1] + ceil(bounds.bottom).toInt()
        require(left >= 0 && top >= 0 && right <= screen.width && bottom <= screen.height) {
            "Popup crop must be inside the display: $left,$top–$right,$bottom in ${screen.width}×${screen.height}"
        }
        val crop = android.graphics.Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        return try {
            val pixels = crop.asImageBitmap().toPixelMap()
            retain?.invoke(screen, android.graphics.Rect(left, top, right, bottom), view)
            retained = retain != null
            pixels
        } finally { if (crop !== screen) crop.recycle() }
    } finally { if (!retained) screen.recycle() }
}


/** An unrelated opaque marker proves which compositor frame a screenshot contains. */
@androidx.compose.runtime.Composable
internal fun PresentationMarker(modifier: Modifier = Modifier, stage: () -> Int) {
    androidx.compose.foundation.Canvas(modifier.size(8.dp)) {
        drawRect(androidx.compose.ui.graphics.Color(stage() * 8 / 255f, 1f, 0f))
    }
}

internal fun capturePresentedPopupPixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction,
    stage: Int, sample: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset(2, 2),
    popupView: () -> View): PixelMap {
    lateinit var pixels: PixelMap
    var observed = androidx.compose.ui.graphics.Color.Transparent
    var lastScreen: android.graphics.Bitmap? = null
    var lastCrop: android.graphics.Rect? = null
    var lastView: View? = null
    try {
        // Wait for frame identity, never for the optical result being tested.
        rule.waitUntil(5000) {
            pixels = capturePopupPixelsObserved(rule, node, popupView) { screen, crop, view ->
                if (lastScreen !== screen) lastScreen?.recycle()
                lastScreen = screen; lastCrop = crop; lastView = view
            }
            observed = pixels[sample.x, sample.y]
            observed.green > .9f && observed.blue < .1f &&
                kotlin.math.abs(observed.red - stage * 8 / 255f) < .01f
        }
    } catch (failure: Throwable) {
        android.util.Log.e("PopupCapture", "Presentation timeout: requestedStage=$stage sample=$sample observed=$observed")
        runCatching { recordPresentationFailure(rule, "popup-stage-$stage", lastView, lastScreen, lastCrop) }
            .onFailure { failure.addSuppressed(it) }
        throw failure
    } finally { lastScreen?.recycle() }
    return pixels
}

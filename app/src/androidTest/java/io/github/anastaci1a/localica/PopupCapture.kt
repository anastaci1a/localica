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
internal fun capturePopupPixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction, popupView: () -> View): PixelMap {
    if (Build.VERSION.SDK_INT >= 28) {
        val bitmap = node.captureToImage().asAndroidBitmap()
        return try { bitmap.asImageBitmap().toPixelMap() } finally { bitmap.recycle() }
    }
    rule.waitForIdle()
    val bounds = node.fetchSemanticsNode().boundsInRoot
    val origin = IntArray(2)
    rule.runOnIdle { popupView().getLocationOnScreen(origin) }
    val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    try {
        val left = origin[0] + floor(bounds.left).toInt()
        val top = origin[1] + floor(bounds.top).toInt()
        val right = origin[0] + ceil(bounds.right).toInt()
        val bottom = origin[1] + ceil(bounds.bottom).toInt()
        require(left >= 0 && top >= 0 && right <= screen.width && bottom <= screen.height) {
            "Popup crop must be inside the display: $left,$top–$right,$bottom in ${screen.width}×${screen.height}"
        }
        val crop = android.graphics.Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        return try { crop.asImageBitmap().toPixelMap() } finally { if (crop !== screen) crop.recycle() }
    } finally { screen.recycle() }
}


/** An unrelated opaque marker proves which compositor frame a screenshot contains. */
@androidx.compose.runtime.Composable
internal fun PresentationMarker(stage: () -> Int) {
    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
        drawRect(androidx.compose.ui.graphics.Color(stage() * 8 / 255f, 1f, 0f))
    }
}

internal fun capturePresentedPopupPixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction,
    stage: Int, popupView: () -> View): PixelMap {
    lateinit var pixels: PixelMap
    var observed = androidx.compose.ui.graphics.Color.Transparent
    try {
        // Wait for frame identity, never for the optical result being tested.
        rule.waitUntil(5000) {
            pixels = capturePopupPixels(rule, node, popupView)
            observed = pixels[2, 2]
            observed.green > .9f && observed.blue < .1f &&
                kotlin.math.abs(observed.red - stage * 8 / 255f) < .01f
        }
    } catch (failure: Throwable) {
        android.util.Log.e("PopupCapture", "Presentation timeout: requestedStage=$stage observed=$observed")
        throw failure
    }
    return pixels
}

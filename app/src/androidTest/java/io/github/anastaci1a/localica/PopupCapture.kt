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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import kotlin.math.floor

/** API 26 cannot capture Dialog roots through Compose. Keep the same pixel
 * assertions by cropping the display using that popup's own LocalView origin.
 * PixelMap owns a copied pixel array; temporary screenshots are always released. */
internal fun capturePopupPixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction,
    captureFromDisplay: Boolean = false, popupView: () -> View): PixelMap {
    if (Build.VERSION.SDK_INT >= 28 && !captureFromDisplay) {
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
internal fun PresentationMarker(modifier: Modifier = Modifier, stage: () -> Int) {
    androidx.compose.foundation.Canvas(modifier.size(8.dp)) {
        drawRect(androidx.compose.ui.graphics.Color(stage() * 8 / 255f, 1f, 0f))
    }
}

internal fun capturePresentedPopupPixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction,
    stage: Int, sample: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset(2, 2),
    captureFromDisplay: Boolean = false, popupView: () -> View): PixelMap {
    lateinit var pixels: PixelMap
    var observed = androidx.compose.ui.graphics.Color.Transparent
    try {
        // Wait for frame identity, never for the optical result being tested.
        rule.waitUntil(5000) {
            pixels = capturePopupPixels(rule, node, captureFromDisplay, popupView)
            observed = pixels[sample.x, sample.y]
            observed.green > .9f && observed.blue < .1f &&
                kotlin.math.abs(observed.red - stage * 8 / 255f) < .01f
        }
    } catch (failure: Throwable) {
        android.util.Log.e("PopupCapture", "Presentation timeout: requestedStage=$stage sample=$sample observed=$observed")
        throw failure
    }
    return pixels
}

/** Capture an existing popup caller without inserting a probe into production content.
 * The semantics owner supplies this window's root-to-screen mapping. An unrelated
 * native overlay marker, outside the tested node, confirms presentation before crop. */
internal fun capturePresentedPopupNodePixels(rule: ComposeContentTestRule, node: SemanticsNodeInteraction,
    stage: Int): PixelMap {
    rule.waitForIdle()
    val semantics = node.fetchSemanticsNode()
    val owner = requireNotNull(semantics.root as? androidx.compose.ui.platform.ViewRootForTest)
    val root = rule.onNode(androidx.compose.ui.test.SemanticsMatcher("owner of popup under test") {
        it.isRoot && it.root === owner
    })
    val rootBounds = root.fetchSemanticsNode().boundsInRoot
    val targetBounds = semantics.boundsInRoot
    val edge = 8f
    val candidates = listOf(
        androidx.compose.ui.geometry.Offset(rootBounds.left + 12f, rootBounds.center.y),
        androidx.compose.ui.geometry.Offset(rootBounds.right - 20f, rootBounds.center.y),
        androidx.compose.ui.geometry.Offset(rootBounds.center.x, rootBounds.top + 48f),
        androidx.compose.ui.geometry.Offset(rootBounds.center.x, rootBounds.bottom - 64f)
    ).map { androidx.compose.ui.geometry.Rect(it, androidx.compose.ui.geometry.Size(edge, edge)) }
    val markerBounds = requireNotNull(candidates.firstOrNull {
        it.left >= rootBounds.left && it.top >= rootBounds.top &&
            it.right <= rootBounds.right && it.bottom <= rootBounds.bottom && !it.overlaps(targetBounds)
    }) { "Presentation marker needs space outside the tested popup node" }
    val marker = android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(stage * 8, 255, 0))
    rule.runOnIdle {
        marker.setBounds(markerBounds.left.toInt(), markerBounds.top.toInt(), markerBounds.right.toInt(), markerBounds.bottom.toInt())
        owner.view.overlay.add(marker)
        owner.view.postInvalidateOnAnimation()
    }
    try {
        // The synthetic semantics root is above the dialog marker, so Compose cannot
        // infer its native window. Use the owner-to-display mapping on every API.
        val pixels = capturePresentedPopupPixels(rule, root, stage, androidx.compose.ui.unit.IntOffset(
            (markerBounds.left - rootBounds.left).toInt() + 2,
            (markerBounds.top - rootBounds.top).toInt() + 2), captureFromDisplay = true) { owner.view }
        // Crop the very frame whose marker was verified, never a second screenshot.
        val left = floor(targetBounds.left - rootBounds.left).toInt()
        val top = floor(targetBounds.top - rootBounds.top).toInt()
        val right = ceil(targetBounds.right - rootBounds.left).toInt()
        val bottom = ceil(targetBounds.bottom - rootBounds.top).toInt()
        require(left >= 0 && top >= 0 && right <= pixels.width && bottom <= pixels.height)
        val width = right - left
        val height = bottom - top
        return PixelMap(IntArray(width * height) { index ->
            pixels[left + index % width, top + index / width].toArgb()
        }, width, height, 0, width)
    } finally {
        rule.runOnIdle { owner.view.overlay.remove(marker); owner.view.postInvalidateOnAnimation() }
    }
}

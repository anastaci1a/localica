package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Color as NativeColor
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** #887: original, generation-bound pixels, not the movable title's surviving semantics.
 * Samples each 1/120 s Engine state, including admission at phase zero. Native capture
 * verifies both windows at that fixed state; this is not a display-refresh/timing claim.
 */
class PopupSharedTitleHandoffTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun titleInkSurvivesAndFollowsTheBodyInEveryOpeningFrame() {
        lateinit var harness: EngineHarness
        lateinit var anchor: GlassAnchor
        var window: Window? = null
        var open by mutableStateOf(false)
        var control = Rect.Zero
        val draws = IdentityHashMap<View, Int>()
        val firstRecording = IdentityHashMap<View, Int>()
        val firstAdmittedDraw = IdentityHashMap<View, Int>()
        val listeners = IdentityHashMap<View, ViewTreeObserver.OnDrawListener>()
        val markers = IdentityHashMap<View, FirstDrawMarker>()
        data class FirstCopy(val draw: Int, val bitmap: Bitmap, var result: Int = -1)
        val firstCopies = mutableListOf<FirstCopy>()
        val admissionReceipts = mutableListOf<String>()
        var observing = true
        fun observe(native: Window) {
            val root = native.decorView.rootView
            if (listeners.containsKey(root)) return
            val marker = FirstDrawMarker().also { it.setBounds(2, 96, 18, 112) }
            markers[root] = marker
            root.overlay.add(marker)
            val listener = ViewTreeObserver.OnDrawListener {
                val draw = (draws[root] ?: 0) + 1
                draws[root] = draw
                if (anchor.hidden && anchor.modalPresent && !firstAdmittedDraw.containsKey(root)) {
                    firstAdmittedDraw[root] = draw
                    marker.color = FirstDrawMarker.FIRST
                    marker.invalidateSelf()
                    root.post {
                        if (observing) {
                            val recorded = firstRecording[root]
                            val receipt = "window=${System.identityHashCode(root)} firstAdmittedDraw=$draw firstTitleRecording=$recorded"
                            admissionReceipts += receipt
                            val copy = FirstCopy(draw, Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888))
                            firstCopies += copy
                            PixelCopy.request(native, copy.bitmap, { result -> copy.result = result }, Handler(Looper.getMainLooper()))
                        }
                    }
                } else if (firstAdmittedDraw[root]?.let { draw == it + 1 } == true) {
                    // A later buffer cannot be accepted as the first admitted draw.
                    marker.color = FirstDrawMarker.LATER
                    marker.invalidateSelf()
                }
            }
            listeners[root] = listener
            root.viewTreeObserver.addOnDrawListener(listener)
        }
        val label = "|".repeat(24)
        compose.setContent { EngineTheme(clear = true) {
            val spec = LocalUiSpec.current.spec
            val owned = remember(spec) { EngineHarness(spec = spec) }
            SideEffect { harness = owned }
            DisposableEffect(owned) { onDispose { owned.close() } }
            CompositionLocalProvider(LocalClockOwner provides owned.clock, LocalUiClock provides owned.clock,
                LocalGlassMorphWorkObserver provides { kind, native, _ ->
                    if (kind == "dialog" && native != null) { window = native; observe(native) }
                }) {
                anchor = rememberGlassAnchor()
                val sourceDensity = LocalDensity.current
                val shared = rememberPopupSharedContent(anchor) {
                    // Equal endpoint typography isolates the flight and body scale from font interpolation.
                    CompositionLocalProvider(LocalContentColor provides Color.Blue) {
                        val titleRoot = LocalView.current.rootView
                        Box(Modifier.drawWithContent {
                            drawContent()
                            // This observes actual recording, not composition/semantics. A later
                            // draw cannot satisfy an earlier native draw's admission receipt.
                            if (!firstRecording.containsKey(titleRoot)) firstRecording[titleRoot] = draws[titleRoot] ?: 0
                        }) {
                        MorphText(label, androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                            androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
                        }
                    }
                }
                val source = rememberFrostedBackdrop(Unit)
                val view = LocalView.current
                Box(Modifier.fillMaxSize().safeDrawingPadding().background(Color.White).testTag("title-scene")) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(source))
                    CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalContentColor provides Color.Blue) {
                        LiquidGlassSurface(Modifier.padding(36.dp).width(220.dp)
                            .glassAnchor(anchor, face = { shared.CompactCopy(Modifier.fillMaxWidth()) })) {
                            Box(Modifier.fillMaxWidth().height(56.dp).testTag("title-trigger")
                                .clickable { open = true }.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart) { shared.Compact() }
                        }
                        GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Center,
                            darkOverride = false, sharedContent = shared) {
                            val actualDensity = LocalDensity.current
                            check(actualDensity.density == sourceDensity.density && actualDensity.fontScale == sourceDensity.fontScale) {
                                "Native popup density/font scale must match the measured endpoint profile"
                            }
                            CompositionLocalProvider(LocalContentColor provides Color.Blue) {
                                Box(Modifier.fillMaxWidth().height(160.dp).padding(24.dp),
                                    contentAlignment = Alignment.CenterStart) { shared.Expanded(Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                    // Independent native control validates the pixel detector, screen origin,
                    // fractional translation and unequal scale. It stays outside the popup.
                    Box(Modifier.align(Alignment.BottomStart).padding(16.dp).size(44.dp, 12.dp)
                        .onGloballyPositioned { coordinates ->
                            val origin = IntArray(2).also(view::getLocationInWindow)
                            val screen = IntArray(2).also(view::getLocationOnScreen)
                            val p = coordinates.positionInWindow() + Offset(
                                (screen[0] - origin[0]).toFloat(), (screen[1] - origin[1]).toFloat())
                            control = Rect(p + Offset(7.25f, 3.5f),
                                p + Offset(7.25f + coordinates.size.width * .75f,
                                    3.5f + coordinates.size.height * .625f))
                        }) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = .75f; scaleY = .625f; translationX = 7.25f; translationY = 3.5f
                        }.background(Color.Red))
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val output = nativeEvidenceDirectory("popup-title-handoff",
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val log = StringBuilder("Engine cadence=120; native display cadence not claimed\n")
        val failures = mutableListOf<String>()
        var sequence = 0

        fun step() {
            compose.runOnUiThread { harness.advance(1.0 / 120) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        fun settled(expanded: Boolean) {
            repeat(300) {
                step()
                if (compose.runOnUiThread {
                    anchor.contact.expansion.value == (if (expanded) 1f else 0f)
                        && anchor.contact.state.momentum.settled && anchor.hidden == expanded
                        && anchor.modalPresent == expanded && (expanded || window?.decorView?.isAttachedToWindow != true)
                }) return
            }
            error("Title calibration did not settle: expanded=$expanded")
        }
        fun image(): Bitmap {
            // Read the native root directly from the popup's semantics. No production seam.
            val node = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()
            val native = node?.let { semantics ->
                val view = (semantics.root as androidx.compose.ui.platform.ViewRootForTest).view
                (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            } ?: window?.takeIf { it.decorView.isAttachedToWindow }
            val stamp = compose.runOnUiThread { harness.clock.stampNow.sequence }
            fun state() = ShadowDrawState(stamp, 0)
            val extras = listOfNotNull(native?.let { popup ->
                ShadowNativeWindow(popup, popup.decorView, IntOffset(18, 1), popup = {
                    val p = anchor.contact.expansion.value.coerceIn(0f, 1f)
                    PopupShadowComposition(if (anchor.modalPresent) PopupShadowScrim(p, (.16f * p * 255f).roundToInt()) else null)
                }) { state() }
            })
            val capture = ShadowNativeCapture(compose, compose.onNodeWithTag("title-scene"), extras) { stamp }.use {
                it.capture(popup = native?.let { popup -> { PopupShadowComposition(higherWindows = listOf(popup)) } }) { state() }
            }
            checkNativeShadowMarker(capture)
            return capture.image.asAndroidBitmap()
        }
        fun sample(name: String): Rect? {
            val bitmap = image()
            try {
                val actualControl = coloredBounds(bitmap, blue = false, control.inflate(4f))
                assertNotNull("Native control is visible in $name", actualControl)
                assertEdges("Native translated/scaled control in $name", control, requireNotNull(actualControl), 1f)
                val scene = compose.onNodeWithTag("title-scene").fetchSemanticsNode()
                val view = (scene.root as androidx.compose.ui.platform.ViewRootForTest).view
                val origin = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
                val bounds = scene.boundsInRoot.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
                val title = coloredBounds(bitmap, blue = true,
                    Rect(bounds.left, bounds.top, bounds.right, control.top - 8f))
                File(output, "${sequence.toString().padStart(3, '0')}-$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                log.append("${sequence++} $name title=$title control=$actualControl\n")
                return title
            } finally { bitmap.recycle() }
        }
        try {
            val source = requireNotNull(sample("source-calibration")) { "Source title must draw before opening" }
            compose.runOnUiThread { open = true }
            settled(true)
            val destination = requireNotNull(sample("destination-calibration")) { "Expanded title must draw" }
            assertEquals("Equal font endpoints retain the same glyph height", source.height, destination.height, 1f)
            assertEquals("Endpoint slots do not clip the controlled title", source.width, destination.width, 1f)
            compose.runOnUiThread { open = false }
            settled(false)
            val restored = requireNotNull(sample("restored-calibration"))
            assertEdges("Source title returns to its measured native bounds", source, restored, 1f)

            compose.onNodeWithTag("title-trigger").performTouchInput { down(center) }
            repeat(4) { frame ->
                step()
                val actual = sample("held-$frame")
                val shape = compose.runOnUiThread { anchor.contact.body.value }
                val expected = transformed(source, anchor.bounds.center,
                    shape.scaleX(anchor.contact.vertical), shape.scaleY(anchor.contact.vertical))
                if (actual == null) failures += "held-$frame: title ink missing"
                else edgeFailure(expected, actual, 2f)?.let { failures += "held-$frame: $it" }
            }
            compose.onNodeWithTag("title-trigger").performTouchInput { up() }
            var complete = false
            var admitted = false
            for (frame in 0 until 180) {
                step()
                val p = compose.runOnUiThread { anchor.contact.expansion.value.coerceIn(0f, 1f) }
                val hidden = compose.runOnUiThread { anchor.hidden }
                admitted = admitted || hidden
                val actual = sample("opening-$frame")
                val body = if (hidden) requireNotNull(sharedBodyBounds()) else null
                val expected = compose.runOnUiThread {
                    val center = body?.center ?: anchor.bounds.center
                    val shape = anchor.contact.body.value
                    transformed(androidx.compose.ui.geometry.lerp(source, destination, p), center,
                        shape.scaleX(anchor.contact.vertical), shape.scaleY(anchor.contact.vertical))
                }
                log.append("frame=$frame phase=$p hidden=$hidden expected=$expected actual=$actual\n")
                if (actual == null) failures += "opening-$frame phase=$p hidden=$hidden: title ink missing"
                else edgeFailure(expected, actual, 2f)?.let { failures += "opening-$frame phase=$p: $it" }
                if (p == 1f && hidden && anchor.contact.state.momentum.settled) { complete = true; break }
            }
            assertTrue("Native admission must be sampled", admitted)
            assertTrue("Opening must reach expanded rest", complete)
            compose.waitUntil(5000) { compose.runOnUiThread { firstCopies.size >= 2 && firstCopies.all { it.result != -1 } } }
            val nativeCopies = compose.runOnUiThread { firstCopies.toList() }
            nativeCopies.forEachIndexed { index, copy ->
                assertEquals("First admitted native PixelCopy completes", PixelCopy.SUCCESS, copy.result)
                File(output, "first-admitted-$index.png").outputStream().use {
                    check(copy.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                assertEquals("First admitted capture must not substitute a later native draw",
                    FirstDrawMarker.FIRST, copy.bitmap.getPixel(10, 104))
                val ink = coloredBounds(copy.bitmap, blue = true, Rect(0f, 0f, copy.bitmap.width.toFloat(), copy.bitmap.height.toFloat()))
                assertNotNull("Title ink must be present in the first admitted native draw: opening=$index draw=${copy.draw}", ink)
            }
            assertTrue("Both calibration and probe openings need first-draw receipts",
                compose.runOnUiThread { admissionReceipts.size } >= 2)
            assertTrue("Title ink must exist and follow its shared flight/body scale in every frame: $failures", failures.isEmpty())
        } finally {
            val receipts = compose.runOnUiThread {
                observing = false
                listeners.forEach { (root, listener) ->
                    if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener)
                    markers[root]?.let { root.overlay.remove(it) }
                }
                admissionReceipts.toList()
            }
            log.append("first-admitted-draw receipts=$receipts\n")
            File(output, "frames.txt").writeText(log.toString())
            compose.runOnUiThread { open = false }
            compose.mainClock.autoAdvance = true
            // Completed requests own their bitmaps. An outstanding callback retains its image.
            compose.runOnUiThread { firstCopies.filter { it.result != -1 }.forEach { it.bitmap.recycle() } }
        }
    }

    private class FirstDrawMarker : Drawable() {
        var color = NativeColor.BLACK
        private val paint = Paint()
        override fun draw(canvas: Canvas) { paint.color = color; canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
        companion object {
            val FIRST = NativeColor.rgb(190, 20, 0)
            val LATER = NativeColor.rgb(220, 20, 0)
        }
    }

    private fun sharedBodyBounds(): Rect? {
        // The material's current logical rectangle is independent of title pixels.
        val node = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull() ?: return null
        val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
        val location = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
        return node.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
    }

    private fun transformed(rect: Rect, center: Offset, sx: Float, sy: Float) = Rect(
        center.x + (rect.left - center.x) * sx, center.y + (rect.top - center.y) * sy,
        center.x + (rect.right - center.x) * sx, center.y + (rect.bottom - center.y) * sy)

    private fun edgeFailure(expected: Rect, actual: Rect, limit: Float): String? {
        val delta = listOf(abs(expected.left - actual.left), abs(expected.top - actual.top),
            abs(expected.right - actual.right), abs(expected.bottom - actual.bottom)).max()
        // Two pixels cover the independently rounded endpoint edge and current placement edge.
        return if (delta <= limit) null else "bounds expected=$expected actual=$actual edgeError=$delta limit=$limit"
    }

    private fun assertEdges(message: String, expected: Rect, actual: Rect, limit: Float) {
        assertNull(message, edgeFailure(expected, actual, limit))
    }

    private fun coloredBounds(bitmap: Bitmap, blue: Boolean, region: Rect): Rect? {
        val left = region.left.toInt().coerceIn(0, bitmap.width)
        val top = region.top.toInt().coerceIn(0, bitmap.height)
        val right = region.right.toInt().coerceIn(left, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top, bitmap.height)
        val pixels = IntArray((right - left) * (bottom - top))
        if (pixels.isEmpty()) return null
        bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        var x0 = right; var y0 = bottom; var x1 = left; var y1 = top
        for (y in top until bottom) for (x in left until right) {
            val c = pixels[(y - top) * (right - left) + x - left]
            val channel = if (blue) NativeColor.blue(c) else NativeColor.red(c)
            val other = if (blue) NativeColor.red(c) else NativeColor.blue(c)
            if (channel - maxOf(other, NativeColor.green(c)) > 128) {
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x + 1); y1 = maxOf(y1, y + 1)
            }
        }
        return if (x1 <= x0 || y1 <= y0) null else Rect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
    }
}

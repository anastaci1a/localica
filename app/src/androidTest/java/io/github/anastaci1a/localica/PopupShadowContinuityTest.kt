package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real composed-display frames, including adjacent shadows and nested native windows.
 * Compare against one field at the current outline, not just registration counts. */
class PopupShadowContinuityTest {
    @get:Rule val compose = createComposeRule()
    private var open by mutableStateOf(false)
    private var strength by mutableFloatStateOf(.8f)
    private lateinit var anchor: GlassAnchor
    private var popupView: View? = null
    private var popupWindow: Window? = null
    private var marker: FrameMarker? = null
    private lateinit var pageView: View
    private var pageMarker: FrameMarker? = null
    private var stage = 0
    private lateinit var output: File
    private var neighboringBody = Rect.Zero
    private var sweptBounds = Rect.Zero

    @Test fun utilityShadowHasOneUnclippedFieldThroughoutRepeatedReversals() = verify(nested = false)
    @Test fun nestedShadowHasOneUnclippedFieldThroughoutRepeatedReversals() = verify(nested = true)

    private fun verify(nested: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        output = File(root, "popup-shadow-continuity/${if (nested) "nested" else "utility"}-api${Build.VERSION.SDK_INT}")
        assertTrue(output.isDirectory || output.mkdirs())
        File(output, "profiles.txt").writeText("scene=${if (nested) "nested" else "utility"} api=${Build.VERSION.SDK_INT} density=${compose.density.density}\n")
        compose.setContent { Fixture(nested) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun advance(time: Long) {
            repeat((time / 16).toInt()) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        }
        try {
            compose.runOnIdle { open = true }
            advance(1200)
            val window = popupWindow
            assertNotNull(window)
            verifyField("first-open")
            // Return and reopen before either spring can finish. Each sampled frame
            // must have the current single gradient, never a trail of old silhouettes.
            repeat(6) { cycle ->
                compose.runOnIdle { open = false }
                advance(96)
                assertTrue(anchor.modalPresent && anchor.hidden)
                verifyField("closing-$cycle")
                compose.runOnIdle { open = true }
                advance(96)
                assertSame(window, popupWindow)
                verifyField("reopening-$cycle")
            }
            compose.runOnIdle { open = false }
            var returned = false
            repeat(180) {
                if (!returned) {
                    advance(16)
                    if (!anchor.hidden && anchor.restoredDrawn && anchor.modalPresent) {
                        assertEquals(0f, anchor.contact.expansion.value, 0f)
                        verifyField("restored-draw-ack")
                        returned = true
                    }
                }
            }
            assertTrue("Capture the final popup-owned shadow before native disposal", returned)
            repeat(12) { if (popupWindow != null) advance(16) }
            assertNull("The returning native window must dispose", popupWindow)
            if (!nested) verifyField("first-trigger-owned-frame", popup = false)
            assertFalse(anchor.modalPresent)
            assertFalse(anchor.hidden)
            assertFalse(anchor.shadowInModal)
            // A complete close/reopen uses a new window, not an old retained shadow.
            compose.runOnIdle { open = true }
            advance(1200)
            assertNotSame(window, popupWindow)
            verifyField("replacement-window")
        } finally {
            compose.runOnIdle { open = false; strength = .8f }
            compose.mainClock.autoAdvance = true
        }
    }

    @Composable private fun Fixture(nested: Boolean) {
        LocalicaTheme {
            val page = LocalView.current
            DisposableEffect(page) {
                val next = FrameMarker()
                pageView = page; pageMarker = next
                page.rootView.overlay.add(next)
                onDispose { page.rootView.overlay.remove(next); pageMarker = null }
            }
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(
                shadowStrength = strength, shadowSpread = .6f, shapeDamping = 2f), LocalReducedMotion provides false) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                val modal = rememberFrostedBackdrop(Unit, dark = false)
                val group = remember { GlassShadowGroup() }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides modal,
                    LocalBottomShadows provides group) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(modal).bottomGlassShadowHost(group)) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(androidx.compose.ui.graphics.Color.White) }
                        if (nested) {
                            GlassMorphPopup(true, rememberGlassAnchor(), {}, GlassPlacement.Center, fitContent = false) {
                                Box(Modifier.fillMaxWidth().height(400.dp).background(androidx.compose.ui.graphics.Color.White),
                                    contentAlignment = Alignment.Center) {
                                    anchor = rememberGlassAnchor()
                                    LiquidGlassSurface(Modifier.align(Alignment.CenterEnd).padding(end = 80.dp)
                                        .size(104.dp, 64.dp).glassAnchor(anchor)) {}
                                    Menu()
                                }
                            }
                        } else {
                            anchor = rememberGlassAnchor()
                            Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                                .padding(horizontal = 56.dp, vertical = 72.dp).fillMaxWidth().height(64.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiquidGlassSurface(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                                    neighboringBody = Rect(it.backdropPosition(page), androidx.compose.ui.geometry.Size(
                                        it.size.width.toFloat(), it.size.height.toFloat()))
                                }.bottomGlassShadow(),
                                    source = source, shadow = false) {}
                                NavigationUtilityPill(anchor, source, Modifier.width(104.dp).fillMaxHeight(),
                                    onSearch = {}, onMore = { open = true })
                            }
                            Menu()
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Menu() {
        GlassMenu(open, anchor, { open = false }) {
            val view = LocalView.current
            val window = (view.parent as DialogWindowProvider).window
            DisposableEffect(window) {
                val next = FrameMarker()
                popupView = view; popupWindow = window; marker = next
                window.decorView.overlay.add(next)
                onDispose {
                    window.decorView.overlay.remove(next)
                    if (popupWindow === window) { popupView = null; popupWindow = null; marker = null }
                }
            }
            ActionMenuItem("Scan music", onClick = {})
            ActionMenuItem("Settings", onClick = {})
        }
    }

    private fun screenshot(): Bitmap {
        compose.waitForIdle()
        stage = stage % 30 + 1
        val token = stage
        val target = popupWindow?.decorView ?: pageView.rootView
        compose.runOnIdle {
            requireNotNull(if (popupWindow != null) marker else pageMarker).apply {
                color = AndroidColor.rgb(token * 8, 255, 0)
                setBounds(96, 96, 112, 112)
                invalidateSelf()
            }
            // Invalidate only the token's bounds. A full-window redraw could
            // conceal stale out-of-bounds shadow damage in the capture itself.
        }
        val origin = IntArray(2)
        compose.runOnIdle { target.getLocationOnScreen(origin) }
        var screenshot: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                screenshot?.recycle()
                val next = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                screenshot = next
                next.getPixel(origin[0] + 104, origin[1] + 104) == AndroidColor.rgb(token * 8, 255, 0)
            }
            return requireNotNull(screenshot)
        } catch (failure: Throwable) { screenshot?.recycle(); throw failure }
    }

    private fun verifyField(name: String, popup: Boolean = true) {
        // This is the highest (tested) native window, including for a nested menu.
        val nodes = if (popup) compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes() else emptyList()
        val bounds = if (popup) nodes.minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot else anchor.bounds
        val origin = IntArray(2)
        if (popup) compose.runOnIdle { requireNotNull(popupView).getLocationOnScreen(origin) }
        val screen = bounds.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
        val shape = anchor.contact.body.value
        val sx = shape.scaleX(anchor.contact.vertical)
        val sy = shape.scaleY(anchor.contact.vertical)
        val d = compose.density.density
        val right = screen.center.x + screen.width * sx / 2f
        // This strip and its unshadowed reference are over the same flat scene:
        // white beside the utility, or the flat content inside the enclosing panel.
        // The clock stays frozen, including the last frame before window disposal.
        val on = screenshot()
        try {
            var maximumError = 0f
            var expectedTotal = 0f
            var actualTotal = 0f
            var count = 0
            val p = anchor.contact.expansion.value.coerceIn(0f, 1f)
            val radius = (anchor.bounds.height / 2 * (1f - p) + 28f * d * p).coerceAtMost(minOf(bounds.width, bounds.height) / 2)
            for (offset in 4..12) for (dy in -2..2) {
                val x = (right + offset * d * sx).roundToInt()
                val y = (screen.center.y + dy * d * sy).roundToInt()
                assertTrue("Entire padded sample must be on screen", x in 0 until on.width && y in 0 until on.height)
                val localX = (x + .5f - screen.center.x) / sx + screen.width / 2
                val localY = (y + .5f - screen.center.y) / sy + screen.height / 2
                var transmittance = 1f
                for (ring in 16 downTo 1) {
                    val t = ring / 16f
                    val expansion = (4f + .6f * 28f) * d * t
                    val corner = radius + expansion
                    val cx = localX.coerceIn(-expansion + corner, bounds.width + expansion - corner)
                    val cy = localY.coerceIn(-expansion + 2f*d + corner, bounds.height + expansion + 2f*d - corner)
                    if ((localX - cx)*(localX - cx) + (localY - cy)*(localY - cy) < corner*corner)
                        transmittance *= 1f - .8f * .07f * (1f - t)*(1f - t)
                }
                val expected = 1f - transmittance
                val referenceX = (right + 36f*d*sx).roundToInt()
                assertTrue("The unshadowed reference must remain on screen", referenceX in 0 until on.width)
                val background = AndroidColor.red(on.getPixel(referenceX, y)).toFloat()
                val actual = 1f - AndroidColor.red(on.getPixel(x, y)) / background.coerceAtLeast(1f)
                maximumError = maxOf(maximumError, abs(actual - expected))
                expectedTotal += expected; actualTotal += actual; count++
            }
            // Also check the entire swept exterior. A right-aligned menu can leave
            // its right edge nearly stationary while old top/left silhouettes trail.
            val drawn = Rect(screen.center.x - screen.width*sx/2, screen.center.y - screen.height*sy/2,
                right, screen.center.y + screen.height*sy/2)
            val padded = drawn.inflate(24f*d*maxOf(sx, sy))
            sweptBounds = if (sweptBounds == Rect.Zero) padded else Rect(
                minOf(sweptBounds.left, padded.left), minOf(sweptBounds.top, padded.top),
                maxOf(sweptBounds.right, padded.right), maxOf(sweptBounds.bottom, padded.bottom))
            val parentInterior = nodes.maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
                ?.takeIf { nodes.size > 1 }?.boundsInRoot?.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))?.deflate(32*d)
            val background = AndroidColor.red(on.getPixel((right + 36f*d*sx).roundToInt(), screen.center.y.roundToInt())).toFloat()
            var exteriorError = 0f
            var exteriorMaximum = 0f
            var exteriorCount = 0
            for (y in sweptBounds.top.toInt().coerceAtLeast(0) until sweptBounds.bottom.toInt().coerceAtMost(on.height) step 3)
                for (x in sweptBounds.left.toInt().coerceAtLeast(0) until sweptBounds.right.toInt().coerceAtMost(on.width) step 3) {
                    val point = Offset(x + .5f, y + .5f)
                    if (parentInterior != null && !parentInterior.contains(point)) continue
                    if (neighboringBody != Rect.Zero && neighboringBody.inflate(26*d).contains(point)) continue
                    val lx = (point.x - screen.center.x)/sx + screen.width/2
                    val ly = (point.y - screen.center.y)/sy + screen.height/2
                    fun inside(expansion: Float, shift: Float): Boolean {
                        val dx = (abs(lx - screen.width/2) - (screen.width/2 - radius)).coerceAtLeast(0f)
                        val dy = (abs(ly - screen.height/2 - shift) - (screen.height/2 - radius)).coerceAtLeast(0f)
                        return dx*dx + dy*dy <= (radius + expansion)*(radius + expansion)
                    }
                    if (inside(2f/minOf(sx, sy), 0f)) continue // Exclude antialiased material edge.
                    var transmission = 1f
                    for (ring in 16 downTo 1) {
                        val t = ring/16f
                        if (inside((4f + .6f*28f)*d*t, 2f*d))
                            transmission *= 1f - .8f*.07f*(1f-t)*(1f-t)
                    }
                    val expected = 1f - transmission
                    val actual = 1f - AndroidColor.red(on.getPixel(x,y))/background.coerceAtLeast(1f)
                    if (expected < .015f && abs(actual) < .015f) continue
                    val error = abs(actual - expected)
                    exteriorError += error; exteriorMaximum = maxOf(exteriorMaximum, error); exteriorCount++
                }
            assertTrue("The swept exterior must include a measurable shadow", exteriorCount > 20)
            File(output, "profiles.txt").appendText("$name sweptCount=$exteriorCount sweptMean=${exteriorError/exteriorCount} sweptMax=$exteriorMaximum\n")
            // Save the entire synthetic popup region so off-axis trails are reviewable.
            val fullLeft = sweptBounds.left.roundToInt().coerceIn(0, on.width-1)
            val fullTop = sweptBounds.top.roundToInt().coerceIn(0, on.height-1)
            val fullWidth = (sweptBounds.right.roundToInt()-fullLeft).coerceIn(1, on.width-fullLeft)
            val fullHeight = (sweptBounds.bottom.roundToInt()-fullTop).coerceIn(1, on.height-fullTop)
            val fullCrop = Bitmap.createBitmap(on, fullLeft, fullTop, fullWidth, fullHeight)
            try { File(output, "$name-field.png").outputStream().use { fullCrop.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { fullCrop.recycle() }
            assertTrue("$name must not leave an old shadow or material silhouette anywhere outside the current outline: max=$exteriorMaximum mean=${exteriorError/exteriorCount}",
                exteriorMaximum < .055f && exteriorError/exteriorCount < .015f)
            // Keep a complete perpendicular shadow profile as well.
            val left = (right + 2*d*sx).roundToInt().coerceIn(0, on.width - 1)
            val top = (screen.center.y - 8*d*sy).roundToInt().coerceIn(0, on.height - 1)
            val width = (38*d*sx).roundToInt().coerceIn(1, on.width - left)
            val height = (16*d*sy).roundToInt().coerceIn(1, on.height - top)
            val crop = Bitmap.createBitmap(on, left, top, width, height)
            try { File(output, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { crop.recycle() }
            File(output, "profiles.txt").appendText("$name progress=$p scale=$sx,$sy expected=${expectedTotal/count} actual=${actualTotal/count} maximumError=$maximumError\n")
            assertTrue("$name must retain one complete shadow field: expected=${expectedTotal/count}, actual=${actualTotal/count}, max=$maximumError",
                maximumError < .035f && abs(actualTotal - expectedTotal) / count < .015f)
        } finally { on.recycle() }
    }

    private class FrameMarker : Drawable() {
        var color = AndroidColor.GREEN
        private val paint = Paint()
        override fun draw(canvas: AndroidCanvas) { paint.color = color; canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }
}

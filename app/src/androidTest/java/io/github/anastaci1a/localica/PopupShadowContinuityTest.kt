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

    @Test fun capsuleReferenceKeepsItsZeroWidthCenterRangeOrdered() {
        // Reproduce the API36 64dp capsule at density 1.75 and its outermost ring.
        val height = 112f
        val radius = height / 2f
        val shift = 3.5f
        val expansion = (4f + .6f * 28f) * 1.75f
        val expandedRadius = radius + expansion
        val oldMinimum = -expansion + shift + expandedRadius
        val oldMaximum = height + expansion + shift - expandedRadius
        assertTrue("Independent expansion cancellation inverted the capsule range", oldMinimum > oldMaximum)
        for (coordinate in listOf(-100f, 0f, radius, radius + shift, height, 200f)) {
            assertEquals("A horizontal capsule has one vertical center", radius + shift,
                shadowAxisCenter(coordinate, height, radius, shift), 0f)
            assertEquals("A vertical capsule has one horizontal center", radius,
                shadowAxisCenter(coordinate, height, radius, 0f), 0f)
        }
        assertEquals(20f, shadowAxisCenter(-10f, 100f, 20f, 0f), 0f)
        assertEquals(50f, shadowAxisCenter(50f, 100f, 20f, 0f), 0f)
        assertEquals(80f, shadowAxisCenter(110f, 100f, 20f, 0f), 0f)
    }

    private fun shadowAxisCenter(coordinate: Float, extent: Float, radius: Float, shift: Float): Float =
        // Expanding both the rect and corner leaves the corner centers unchanged.
        // Cancel algebraically before Float arithmetic: a capsule's equal endpoints
        // otherwise round in opposite directions and make coerceIn reject the range.
        coordinate.coerceIn(radius + shift, (extent - radius) + shift)

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
        // Batch only the two unobserved endpoints; native drawing still gets a
        // yield within four frames. Reversals and draw-ack/disposal stay per-frame.
        fun advanceEndpoint(time: Long) {
            val start = compose.mainClock.currentTime
            while (compose.mainClock.currentTime - start < time) {
                val remaining = time - (compose.mainClock.currentTime - start)
                compose.mainClock.advanceTimeBy(minOf(64L, remaining))
                compose.waitForIdle()
            }
        }
        try {
            compose.runOnIdle { open = true }
            advanceEndpoint(1200)
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
            advanceEndpoint(1200)
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
        val exports = PopupShadowFrameExports(output, PopupShadowFrameExports.enabled(
            InstrumentationRegistry.getArguments().getString("popupShadowFrameExports")))
        val on = screenshot()
        exports.verify(on, name, { if (sweptBounds == Rect.Zero) screen else sweptBounds }) {
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
                    val cx = shadowAxisCenter(localX, bounds.width, radius, 0f)
                    val cy = shadowAxisCenter(localY, bounds.height, radius, 2f*d)
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
            exports.success(on, "$name-field", fullLeft, fullTop, fullWidth, fullHeight)
            assertTrue("$name must not leave an old shadow or material silhouette anywhere outside the current outline: max=$exteriorMaximum mean=${exteriorError/exteriorCount}",
                exteriorMaximum < .055f && exteriorError/exteriorCount < .015f)
            // Keep a complete perpendicular shadow profile as well.
            val left = (right + 2*d*sx).roundToInt().coerceIn(0, on.width - 1)
            val top = (screen.center.y - 8*d*sy).roundToInt().coerceIn(0, on.height - 1)
            val width = (38*d*sx).roundToInt().coerceIn(1, on.width - left)
            val height = (16*d*sy).roundToInt().coerceIn(1, on.height - top)
            exports.success(on, name, left, top, width, height)
            File(output, "profiles.txt").appendText("$name progress=$p scale=$sx,$sy expected=${expectedTotal/count} actual=${actualTotal/count} maximumError=$maximumError\n")
            assertTrue("$name must retain one complete shadow field: expected=${expectedTotal/count}, actual=${actualTotal/count}, max=$maximumError",
                maximumError < .035f && abs(actualTotal - expectedTotal) / count < .015f)
        }
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

/** Native bitmap/file contracts only; no Compose rule, window or screenshot is needed. */
class PopupShadowFrameExportTest {
    @Test fun successfulExportsRequireExplicitOptInAndKeepTheCapturedBitmapUsable() = withOutput { output ->
        for (argument in listOf(null, "false", "TRUE", "1")) {
            assertFalse(PopupShadowFrameExports.enabled(argument))
        }
        assertTrue(PopupShadowFrameExports.enabled("true"))
        // An immutable whole-image crop may alias its source. Exporting it must
        // not recycle the captured frame before the remaining assertions/profile.
        val bitmap = fixture()
        val expected = pixels(bitmap)
        var disabledEncodes = 0
        val disabled = PopupShadowFrameExports(output, false) { _, _ -> disabledEncodes++; true }
        val enabled = PopupShadowFrameExports(output, PopupShadowFrameExports.enabled("true"))
        enabled.verify(bitmap, "opt-in", { Rect.Zero }) {
            disabled.success(bitmap, "disabled", 0, 0, bitmap.width, bitmap.height)
            assertEquals(0, disabledEncodes)
            assertTrue(requireNotNull(output.listFiles()).isEmpty())
            enabled.success(bitmap, "opt-in-field", 0, 0, bitmap.width, bitmap.height)
            assertFalse(bitmap.isRecycled)
            assertArrayEquals(expected, pixels(bitmap))
            enabled.success(bitmap, "opt-in", 2, 1, 3, 2)
            assertFalse(bitmap.isRecycled)
        }
        assertTrue(bitmap.isRecycled)
        assertEquals(setOf("opt-in-field.png", "opt-in.png"), requireNotNull(output.list()).toSet())
        assertPng(File(output, "opt-in-field.png"), 8, 6, expected)
        assertPng(File(output, "opt-in.png"), 3, 2,
            intArrayOf(expected[10], expected[11], expected[12], expected[18], expected[19], expected[20]))
    }

    @Test fun failureExportsOnlyTheBoundedRegionOfTheSameCapturedBitmap() = withOutput { output ->
        val bitmap = fixture()
        val expected = pixels(bitmap)
        val primary = AssertionError("original optical assertion")
        val exports = PopupShadowFrameExports(output, false)
        val caught = runCatching {
            exports.verify(bitmap, "failed-frame", { Rect(-2f, 1f, 3f, 20f) }) { throw primary }
        }.exceptionOrNull()
        assertSame(primary, caught)
        assertTrue(primary.suppressed.isEmpty())
        assertTrue(bitmap.isRecycled)
        assertEquals(setOf("failed-frame-failure-field.png"), requireNotNull(output.list()).toSet())
        assertPng(File(output, "failed-frame-failure-field.png"), 3, 5,
            IntArray(15) { index -> expected[(index / 3 + 1) * 8 + index % 3] })
    }

    @Test fun failedEvidenceEncodingCannotReplaceTheOriginalAssertion() = withOutput { output ->
        for (throws in listOf(false, true)) {
            val bitmap = fixture()
            val primary = AssertionError("original optical assertion")
            val encoderFailure = java.io.IOException("synthetic encoder failure")
            var encoded: Bitmap? = null
            var encodes = 0
            val exports = PopupShadowFrameExports(output, false) { crop, _ ->
                encoded = crop; encodes++
                if (throws) throw encoderFailure else false
            }
            val caught = runCatching {
                exports.verify(bitmap, "encoder-$throws", { Rect(1f, 1f, 4f, 3f) }) { throw primary }
            }.exceptionOrNull()
            assertSame(primary, caught)
            assertEquals(1, encodes)
            assertEquals(1, primary.suppressed.size)
            if (throws) assertSame(encoderFailure, primary.suppressed.single())
            else assertTrue(primary.suppressed.single() is IllegalStateException)
            assertTrue(requireNotNull(encoded).isRecycled)
            assertTrue(bitmap.isRecycled)
        }
    }

    @Test fun repeatedEncoderExceptionDoesNotSelfSuppressOrReplaceThePrimaryFailure() = withOutput { output ->
        val bitmap = fixture()
        val primary = java.io.IOException("same encoder exception instance")
        val crops = mutableListOf<Bitmap>()
        val exports = PopupShadowFrameExports(output, true) { crop, _ -> crops += crop; throw primary }
        val caught = runCatching {
            exports.verify(bitmap, "repeat-error", { Rect(1f, 1f, 4f, 3f) }) {
                exports.success(bitmap, "repeat-error", 2, 1, 3, 2)
            }
        }.exceptionOrNull()
        assertSame(primary, caught)
        assertTrue(primary.suppressed.isEmpty())
        assertEquals(2, crops.size)
        assertTrue(crops.all { it.isRecycled })
        assertTrue(bitmap.isRecycled)
    }

    @Test fun evidenceFileOpenFailureCannotReplaceTheOriginalAssertion() = withOutput { output ->
        val notDirectory = File(output, "not-a-directory").apply { writeText("occupied") }
        val bitmap = fixture()
        val primary = AssertionError("original optical assertion")
        var encodes = 0
        val exports = PopupShadowFrameExports(notDirectory, false) { _, _ -> encodes++; true }
        val caught = runCatching {
            exports.verify(bitmap, "file-error", { Rect(1f, 1f, 4f, 3f) }) { throw primary }
        }.exceptionOrNull()
        assertSame(primary, caught)
        assertEquals(0, encodes)
        assertEquals(1, primary.suppressed.size)
        assertTrue(primary.suppressed.single() is java.io.IOException)
        assertTrue(bitmap.isRecycled)
        assertEquals("occupied", notDirectory.readText())
    }

    private fun fixture(): Bitmap {
        val mutable = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try {
            mutable.setPixels(IntArray(48) { index ->
                AndroidColor.rgb(index % 8 * 31, index / 8 * 41, index * 5)
            }, 0, 8, 0, 0, 8, 6)
            requireNotNull(mutable.copy(Bitmap.Config.ARGB_8888, false))
        } finally { mutable.recycle() }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertPng(file: File, width: Int, height: Int, expected: IntArray) {
        val decoded = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(width, decoded.width)
            assertEquals(height, decoded.height)
            assertArrayEquals(expected, pixels(decoded))
        } finally { decoded.recycle() }
    }

    private fun withOutput(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("popup-shadow-exports-", ".tmp", context.cacheDir)
        try {
            assertTrue(output.delete() && output.mkdir())
            block(output)
        } finally { output.deleteRecursively() }
    }
}

/** Export from the owned capture only: never acquire or advance a frame here. */
private class PopupShadowFrameExports(
    private val output: File,
    private val successfulFrames: Boolean,
    private val encode: (Bitmap, java.io.OutputStream) -> Boolean = { bitmap, stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
) {
    companion object {
        fun enabled(argument: String?): Boolean = argument == "true"
    }

    fun verify(bitmap: Bitmap, name: String, failureRegion: () -> Rect, assertions: () -> Unit) {
        try {
            assertions()
        } catch (failure: Throwable) {
            try {
                val region = failureRegion()
                val left = region.left.roundToInt().coerceIn(0, bitmap.width - 1)
                val top = region.top.roundToInt().coerceIn(0, bitmap.height - 1)
                val width = (region.right.roundToInt() - left).coerceIn(1, bitmap.width - left)
                val height = (region.bottom.roundToInt() - top).coerceIn(1, bitmap.height - top)
                write(bitmap, "$name-failure-field", left, top, width, height)
            } catch (evidenceFailure: Throwable) {
                if (evidenceFailure !== failure) failure.addSuppressed(evidenceFailure)
            }
            throw failure
        } finally { bitmap.recycle() }
    }

    fun success(bitmap: Bitmap, name: String, left: Int, top: Int, width: Int, height: Int) {
        if (successfulFrames) write(bitmap, name, left, top, width, height)
    }

    private fun write(bitmap: Bitmap, name: String, left: Int, top: Int, width: Int, height: Int) {
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        try {
            File(output, "$name.png").outputStream().use {
                check(encode(crop, it)) { "Unable to encode $name evidence" }
            }
        } finally {
            // Android may return the immutable source for a whole-frame crop.
            // Its owner is verify(), including when an opt-in export succeeds.
            if (crop !== bitmap) crop.recycle()
        }
    }
}

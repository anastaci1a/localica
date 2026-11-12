package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Color as NativeColor
import android.os.Build
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** #887: original Window buffers, not the movable title's surviving semantics.
 * Samples each 1/120 s Engine state, including admission at phase zero. Every native
 * copy carries an opaque frame token; this is not a display-refresh/timing claim.
 */
class PopupSharedTitleHandoffTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun titleInkSurvivesAndFollowsTheBodyInEveryOpeningFrame() = verify(false)

    @Test fun titleInkSurvivesWhileTheFirstAdmittedNativeDrawIsDeferred() = verify(true)

    private fun verify(deferFirstNativeDraw: Boolean) {
        lateinit var harness: EngineHarness
        lateinit var anchor: GlassAnchor
        var window: Window? = null
        var open by mutableStateOf(false)
        var control = Rect.Zero
        val deferred = if (deferFirstNativeDraw) AdmissionDrawHold(
            { anchor.hidden && anchor.modalPresent }, { harness.clock.stampNow.sequence }) else null
        val label = "|".repeat(24)
        compose.setContent { EngineTheme(clear = true) {
            val spec = LocalUiSpec.current.spec
            val owned = remember(spec) { EngineHarness(spec = spec) }
            SideEffect { harness = owned }
            DisposableEffect(owned) { onDispose { owned.close() } }
            CompositionLocalProvider(LocalClockOwner provides owned.clock, LocalUiClock provides owned.clock,
                LocalGlassMorphWorkObserver provides { kind, native, _ ->
                    if (kind == "dialog" && native != null) {
                        window = native
                        deferred?.observe(native)
                    }
                }) {
                anchor = rememberGlassAnchor()
                val sourceDensity = LocalDensity.current
                val shared = rememberPopupSharedContent(anchor) {
                    // Equal endpoint typography isolates the flight and body scale from font interpolation.
                    CompositionLocalProvider(LocalContentColor provides Color.Blue) {
                        MorphText(label, androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                            androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
                    }
                }
                val source = rememberFrostedBackdrop(Unit)
                val view = LocalView.current
                Box(Modifier.fillMaxSize().safeDrawingPadding().background(Color.White).testTag("title-scene")) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(source))
                    CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalContentColor provides Color.Blue) {
                        LiquidGlassSurface(Modifier.padding(36.dp).width(220.dp)
                            .glassAnchor(anchor, face = { shared.CompactCopy(Modifier.fillMaxWidth()) }),
                            inkOverride = Color.Blue) {
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
        val output = nativeEvidenceDirectory(if (deferFirstNativeDraw) "popup-title-handoff-deferred" else "popup-title-handoff",
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val log = StringBuilder("Engine cadence=120; native display cadence not claimed\n")
        val failures = mutableListOf<String>()
        var sequence = 0
        data class Scene(val view: View, val bounds: Rect, val main: Window, val origin: IntOffset)
        var cachedScene: Scene? = null

        fun step() {
            compose.runOnUiThread { harness.advance(1.0 / 120) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        fun sample(name: String, reuseScene: Boolean = false): Rect? {
            // fetchSemanticsNode waits for idle. During the intentional draw deferral,
            // reuse the already-calibrated scene and validate its real View origin.
            val measured = if (reuseScene) requireNotNull(cachedScene) else {
                val node = compose.onNodeWithTag("title-scene").fetchSemanticsNode()
                val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
                val origin = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
                val main = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                    .filterIsInstance<Activity>().first().window
                Scene(view, node.boundsInRoot.translate(Offset(origin[0].toFloat(), origin[1].toFloat())),
                    main, IntOffset(origin[0], origin[1])).also { cachedScene = it }
            }
            val view = measured.view
            val bounds = measured.bounds
            val currentOrigin = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
            assertEquals("Calibrated scene origin stays fixed", measured.origin, IntOffset(currentOrigin[0], currentOrigin[1]))
            // Declared diagnostic gutter, outside the whole source/destination title flight.
            // Its opaque green tokens cannot be mistaken for title ink. Endpoint checks
            // below also reject a title that is clipped by this measured field.
            val field = Rect(bounds.left + 32f, bounds.top + 16f, bounds.right, control.top - 8f)
            val main = measured.main
            val stamp = compose.runOnUiThread { harness.clock.stampNow.sequence }
            val native = compose.runOnUiThread { window?.takeIf { it.decorView.isAttachedToWindow } }
            val originals = listOf(main) + listOfNotNull(native)
            val display = compose.runOnUiThread {
                android.graphics.Point().also { requireNotNull(view.display).getRealSize(it) }
            }
            val insets = compose.runOnUiThread {
                requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view)).getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            }
            assertTrue("Measured safeDrawing scene excludes actual system bars and cutout",
                bounds.left >= insets.left && bounds.top >= insets.top &&
                    bounds.right <= display.x - insets.right && bounds.bottom <= display.y - insets.bottom)
            val bitmap = Bitmap.createBitmap(display.x, display.y, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                originals.forEachIndexed { index, ownedWindow ->
                    val token = sequence * 2 + index + 1
                    val file = File(output, "${sequence.toString().padStart(3, '0')}-$name-window-$index.png")
                    val image = if (deferred?.isHolding(ownedWindow) == true)
                        previousNative(ownedWindow, deferred, file, log)
                    else original(ownedWindow, token, file, log)
                    try {
                        val point = IntArray(2).also { compose.runOnUiThread { ownedWindow.decorView.getLocationOnScreen(it) } }
                        val localField = field.translate(Offset(-point[0].toFloat(), -point[1].toFloat()))
                        log.append("$name window=$index token=$token origin=${point.toList()} title=${coloredBounds(image, true, localField)} maxBlueContrast=${blueContrast(image, localField)}\n")
                        // Derived SRC_OVER image, never labelled as a physical compositor frame.
                        // Both original Window buffers and their independent token checks survive.
                        canvas.drawBitmap(image, point[0].toFloat(), point[1].toFloat(), null)
                    } finally { image.recycle() }
                }
                assertEquals("Engine state stays fixed across original Window copies", stamp,
                    compose.runOnUiThread { harness.clock.stampNow.sequence })
                val actualControl = coloredBounds(bitmap, blue = false, control.inflate(4f))
                assertNotNull("Native control is visible in $name", actualControl)
                assertEdges("Native translated/scaled control in $name", control, requireNotNull(actualControl), 1f)
                val title = coloredBounds(bitmap, blue = true, field)
                // Export measured app content only; original native buffers above are diagnostics.
                val content = Bitmap.createBitmap(bitmap, bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.width.roundToInt(), bounds.height.roundToInt())
                try { File(output, "${sequence.toString().padStart(3, '0')}-$name-derived-content.png").outputStream().use {
                    check(content.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { content.recycle() }
                log.append("field=$field scene=$bounds copyKind=derived-SRC_OVER-from-original-Windows\n")
                if (name.endsWith("calibration") && title != null) {
                    assertTrue("Endpoint title is inside the declared diagnostic-free field",
                        title.left > field.left && title.top > field.top && title.right < field.right && title.bottom < field.bottom)
                }
                log.append("${sequence++} $name title=$title maxBlueContrast=${blueContrast(bitmap, field)} control=$actualControl\n")
                return title
            } finally { bitmap.recycle() }
        }
        try {
            val source = requireNotNull(sample("source-calibration")) { "Source title must draw before opening" }
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
            deferred?.enableRendering()
            compose.onNodeWithTag("title-trigger").performTouchInput { up() }
            var deferredTitlePresent: Boolean? = null
            if (deferred != null) {
                try {
                    // No idle wait until the deliberately deferred draw is released.
                    // Clock steps flush composition; native traversals run independently.
                    for (frame in 0 until 180) {
                        if (deferred.reached.count == 0L) break
                        compose.runOnUiThread { harness.advance(1.0 / 120) }
                        compose.mainClock.advanceTimeByFrame()
                        if (deferred.reached.await(20, TimeUnit.MILLISECONDS)) break
                    }
                    assertTrue("The first admitted native draw must be deferred", deferred.reached.count == 0L)
                    val pending = sample("admission-pending", reuseScene = true)
                    deferredTitlePresent = pending != null
                    log.append("admission-pending titlePresent=$deferredTitlePresent title=$pending\n")
                } finally { compose.runOnUiThread { deferred.release() } }
                compose.waitForIdle()
            }
            // Calibrate the destination at the end of this same first opening. A warm-up
            // return would introduce another ownership transfer before the guarded event.
            data class OpeningFrame(val frame: Int, val phase: Float, val hidden: Boolean,
                val actual: Rect?, val center: Offset, val scaleX: Float, val scaleY: Float)
            val openingFrames = mutableListOf<OpeningFrame>()
            var complete = false
            var admitted = false
            for (frame in 0 until 180) {
                step()
                val p = compose.runOnUiThread { anchor.contact.expansion.value.coerceIn(0f, 1f) }
                val hidden = compose.runOnUiThread { anchor.hidden }
                admitted = admitted || hidden
                val actual = sample("opening-$frame")
                val body = if (hidden) requireNotNull(sharedBodyBounds()) else null
                val receipt = compose.runOnUiThread {
                    val center = body?.center ?: anchor.bounds.center
                    val shape = anchor.contact.body.value
                    OpeningFrame(frame, p, hidden, actual, center,
                        shape.scaleX(anchor.contact.vertical), shape.scaleY(anchor.contact.vertical))
                }
                openingFrames += receipt
                log.append("frame=$frame phase=$p hidden=$hidden center=${receipt.center} scale=${receipt.scaleX},${receipt.scaleY} actual=$actual\n")
                if (p == 1f && hidden && anchor.contact.state.momentum.settled) { complete = true; break }
            }
            val measuredDestination = sample("destination-calibration")
            // Lead-requested observation for a possible separate return defect. Keep it
            // before opening assertions so their failure cannot discard the close cycle.
            // Neither return settlement nor post-return title presence is asserted here.
            compose.runOnUiThread { open = false }
            var returned = false
            for (frame in 0 until 300) {
                step()
                returned = compose.runOnUiThread {
                    anchor.contact.expansion.value == 0f && anchor.contact.state.momentum.settled
                        && !anchor.hidden && !anchor.modalPresent && window?.decorView?.isAttachedToWindow != true
                }
                if (returned) break
            }
            val afterReturn = sample("post-return-observation")
            log.append("post-return observation settled=$returned titlePresent=${afterReturn != null} title=$afterReturn source=$source\n")
            assertTrue("Native admission must be sampled", admitted)
            assertTrue("Opening must reach expanded rest", complete)
            if (deferred != null) assertTrue(
                "Title ink must survive while the first admitted native draw is pending", deferredTitlePresent == true)
            val destination = requireNotNull(measuredDestination) { "Expanded title must draw" }
            assertEquals("Equal font endpoints retain the same glyph height", source.height, destination.height, 1f)
            assertEquals("Endpoint slots do not clip the controlled title", source.width, destination.width, 1f)
            openingFrames.forEach { receipt ->
                val expected = transformed(androidx.compose.ui.geometry.lerp(source, destination, receipt.phase),
                    receipt.center, receipt.scaleX, receipt.scaleY)
                log.append("frame=${receipt.frame} expected=$expected actual=${receipt.actual}\n")
                if (receipt.actual == null) failures += "opening-${receipt.frame} phase=${receipt.phase} hidden=${receipt.hidden}: title ink missing"
                else edgeFailure(expected, receipt.actual, 2f)?.let { failures += "opening-${receipt.frame} phase=${receipt.phase}: $it" }
            }
            assertTrue("Title ink must exist and follow its shared flight/body scale in every frame: $failures", failures.isEmpty())
        } finally {
            compose.runOnUiThread { deferred?.close() }
            File(output, "frames.txt").writeText(log.toString())
            compose.runOnUiThread { open = false }
            compose.mainClock.autoAdvance = true
        }
    }

    /** A native scheduling adversary, not another rendering or motion implementation.
     * Preparatory buffers may draw. Once the source is hidden, the next native draw waits
     * for the instrumentation thread to copy the existing buffers and release it. */
    private class AdmissionDrawHold(private val admitted: () -> Boolean, private val stamp: () -> Long) {
        val reached = CountDownLatch(1)
        private val released = AtomicBoolean(false)
        private var window: Window? = null
        private var tree: ViewTreeObserver? = null
        private var marker: FrameMarker? = null
        private var listener: ViewTreeObserver.OnPreDrawListener? = null
        private var renderingWasEnabled: Boolean? = null
        @Volatile var token = 0
            private set
        @Volatile var lastDrewAdmitted = false
            private set
        @Volatile var lastSequence = -1L
            private set
        @Volatile var attempts = 0
            private set

        fun enableRendering() {
            if (renderingWasEnabled == null) {
                renderingWasEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
                androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            }
        }
        fun observe(native: Window) {
            if (window === native) return
            check(window == null) { "The first opening must keep its native Window" }
            window = native
            val root = native.decorView
            marker = FrameMarker {
                token++
                check(token <= 65535) { "Never wrap the native admission token" }
                lastDrewAdmitted = admitted()
                lastSequence = stamp()
                NativeColor.rgb(token ushr 8, token and 255, 0)
            }.also { it.setBounds(2, 120, 18, 136); root.overlay.add(it) }
            val watch = ViewTreeObserver.OnPreDrawListener {
                if (!released.get() && admitted()) {
                    attempts++
                    reached.countDown()
                    false
                } else true
            }
            listener = watch
            tree = root.viewTreeObserver.also { it.addOnPreDrawListener(watch) }
        }
        fun isHolding(native: Window) = window === native && reached.count == 0L && !released.get()
        fun release() { released.set(true); window?.decorView?.postInvalidateOnAnimation() }
        fun close() {
            release()
            tree?.takeIf { it.isAlive }?.let { observer -> listener?.let(observer::removeOnPreDrawListener) }
            marker?.let { window?.decorView?.overlay?.remove(it) }
            renderingWasEnabled?.let { androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(it) }
        }
    }

    private fun previousNative(window: Window, hold: AdmissionDrawHold, output: File, log: StringBuilder): Bitmap {
        val receipt = compose.runOnUiThread {
            check(hold.isHolding(window)) { "The old native buffer must be held during its copy" }
            check(hold.token > 0 && !hold.lastDrewAdmitted) { "A preparatory native buffer must precede the deferred admitted draw" }
            Triple(hold.token, hold.lastSequence, windowCrop(window.decorView))
        }
        val image = copyNativeWindowOnce(window,
            Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888),
            "Previous native buffer copy timed out", sourceBounds = receipt.third,
            failureMessage = { "Previous native buffer copy failed: $it; no retry" })
        try {
            output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val expected = NativeColor.rgb(receipt.first ushr 8, receipt.first and 255, 0)
            log.append("previous-native token=${receipt.first} sequence=${receipt.second} crop=${receipt.third} attempts=${hold.attempts} actual=${image.getPixel(10, 128)}\n")
            assertEquals("Previous native buffer contains its preparatory draw token", expected, image.getPixel(10, 128))
            compose.runOnUiThread {
                check(hold.isHolding(window) && hold.token == receipt.first && hold.lastSequence == receipt.second) {
                    "The deferred native Window must not redraw during buffer copies"
                }
            }
            return image
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }

    /** Window crop coordinates are passed directly to the padded Surface before API 28. */
    private fun windowCrop(view: View): android.graphics.Rect = android.graphics.Rect(0, 0, view.width, view.height).also { rect ->
        if (Build.VERSION.SDK_INT < 28) {
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(view)
            val attributes = root.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }.get(root)
            val insets = android.view.WindowManager.LayoutParams::class.java.getField("surfaceInsets")
                .get(attributes) as android.graphics.Rect
            rect.offset(insets.left, insets.top)
        }
    }

    /** Existing single-copy ownership helper, with the API 26 padded-Surface crop used by
     * PopupChromeDepthTest. Native token equality validates that coordinate adapter here. */
    private fun original(window: Window, token: Int, output: File, log: StringBuilder): Bitmap {
        val view = window.decorView
        val ready = CountDownLatch(1)
        val color = NativeColor.rgb(token ushr 8, token and 255, 0)
        val marker = FrameMarker { color }.also { it.setBounds(2, 96, 18, 112) }
        var draws = 0
        lateinit var tree: ViewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            draws++
            if (draws == 1) view.postOnAnimation { view.invalidate() }
            else view.post { ready.countDown() }
        }
        val enabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        if (!enabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        val crop = compose.runOnUiThread {
            tree = view.viewTreeObserver
            tree.addOnDrawListener(listener)
            view.overlay.add(marker)
            marker.invalidateSelf(); view.invalidate()
            windowCrop(view)
        }
        try {
            assertTrue("Native window submits the requested fixed-state draw", ready.await(5, TimeUnit.SECONDS))
            val image = copyNativeWindowOnce(window,
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888),
                "Original fixed-state Window copy timed out", sourceBounds = crop,
                failureMessage = { "Original fixed-state Window copy failed: $it; no retry" })
            try {
                output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                log.append("native token=$token draws=$draws crop=$crop expected=$color actual=${image.getPixel(10, 104)}\n")
                assertEquals("Original Window buffer contains its requested opaque token", color, image.getPixel(10, 104))
                return image
            } catch (failure: Throwable) { image.recycle(); throw failure }
        } finally {
            compose.runOnUiThread {
                if (tree.isAlive) tree.removeOnDrawListener(listener)
                view.overlay.remove(marker)
            }
            if (!enabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    private class FrameMarker(private val color: () -> Int) : Drawable() {
        private val paint = Paint()
        override fun draw(canvas: Canvas) { paint.color = color(); canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
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

    // Distinguishes absent title ink from a low-opacity title below the half-coverage
    // bounds detector. A missing-title cause needs zero contrast, not just a null bound.
    private fun blueContrast(bitmap: Bitmap, region: Rect): Int {
        val left = region.left.toInt().coerceAtLeast(0)
        val top = region.top.toInt().coerceAtLeast(0)
        val right = region.right.toInt().coerceAtMost(bitmap.width)
        val bottom = region.bottom.toInt().coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return 0
        val pixels = IntArray((right - left) * (bottom - top))
        bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return pixels.fold(0) { maximum, color ->
            maxOf(maximum, NativeColor.blue(color) - maxOf(NativeColor.red(color), NativeColor.green(color)))
        }
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

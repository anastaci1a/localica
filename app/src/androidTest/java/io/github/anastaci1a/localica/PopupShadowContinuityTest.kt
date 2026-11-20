package io.github.anastaci1a.localica


import io.github.anastaci1a.localica.ui.engine.*

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
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.ShadowMassContour
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

    @Test fun restingNativePopupAndReturnedTriggerKeepTheDownwardMass() {
        output = nativeEvidenceDirectory("shadow-mass-popup", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        check(output.isDirectory || output.mkdirs())
        compose.setContent { Fixture(nested = false) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val failures = mutableListOf<String>()
        fun settle() { repeat(24) { compose.mainClock.advanceTimeBy(64); compose.waitForIdle() } }
        fun measure(name: String, popup: Boolean) {
            val nodes = if (popup) compose.popupGeometryNodes(anchor, open, output, name, 0) else emptyList()
            val bounds = if (popup) nodes.minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot else anchor.bounds
            val origin = IntArray(2)
            if (popup) compose.runOnIdle { requireNotNull(popupView).getLocationOnScreen(origin) }
            val screen = bounds.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
            val d = if (popup) requireNotNull(popupDensity) else compose.density.density
            val rows = (16 * d).toInt()
            val left = kotlin.math.ceil(screen.left).toInt()
            val right = kotlin.math.floor(screen.right).toInt()
            val top = kotlin.math.floor(screen.top).toInt() - rows
            val bottom = kotlin.math.ceil(screen.bottom).toInt() + rows
            val image = screenshot()
            try {
                assertTrue("The complete mass bands must fit the native display", left >= 0 && top >= 0 && right <= image.width && bottom <= image.height)
                val referenceY = (screen.top - 64 * d).toInt()
                assertTrue("Unshadowed backing must be inside app content", referenceY > 24 * d)
                val background = AndroidColor.red(image.getPixel(screen.center.x.toInt(), referenceY)) / 255.0
                assertTrue("Backing must carry contrast", background > .1)
                val local = screen.translate(Offset(-left.toFloat(), -top.toFloat()))
                val radius = if (popup) 28 * d else screen.height / 2
                val contours = mutableListOf(ShadowMassContour(
                    local.left, local.top, local.right, local.bottom, radius))
                if (!popup && neighboringBody != Rect.Zero) {
                    val neighbor = neighboringBody.translate(Offset(-left.toFloat(), -top.toFloat()))
                    contours += ShadowMassContour(
                        neighbor.left, neighbor.top, neighbor.right, neighbor.bottom, neighbor.height / 2)
                }
                val mass = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMass(
                    right - left, bottom - top, local.top, local.bottom, rows) { x, y ->
                    maxOf(0.0, 1 - AndroidColor.red(image.getPixel(left + x, top + y)) / (255 * background))
                }
                val reference = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMassReference(
                    right - left, bottom - top, local.left, local.top, local.right, local.bottom, radius, d,
                    code = 1 / (255 * background), bandDp = 16f, contours = contours)
                File(output, "$name.txt").writeText("density=$d body=$screen background=$background " +
                    "window=${System.identityHashCode(popupWindow ?: pageView)} generation=$stage " +
                    "modal=${anchor.modalPresent} mass=$mass reference=$reference contours=$contours\n")
                if (!reference.accepts(mass)) failures += "$name: native visible mass=$mass reference=$reference"
            } finally { image.recycle() }
        }
        try {
            settle(); measure("closed", false)
            compose.runOnIdle { open = true }; settle()
            assertNotNull(popupWindow)
            measure("popup", true)
            compose.runOnIdle { open = false }; settle()
            assertNull(popupWindow)
            assertFalse(anchor.modalPresent)
            measure("returned", false)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }
    private var open by mutableStateOf(false)
    private lateinit var anchor: GlassAnchor
    private var popupView: View? = null
    private var popupWindow: Window? = null
    private var popupDensity: Float? = null
    private var popupPlane: ShadowPlane? = null
    private var marker: FrameMarker? = null
    private lateinit var pageView: View
    private var pageMarker: FrameMarker? = null
    private var stage = 0
    private var geometryWindowCount = 0
    private lateinit var output: File
    private var neighboringBody by mutableStateOf(Rect.Zero)
    private var referenceOrigin by mutableStateOf(Offset.Zero)
    private var referenceShift = 0f
    private var referencePlane: ShadowPlane? = null
    private val referenceCasters = arrayOfNulls<SurfaceView>(2)
    private var sharedSampleCoordinates = ""
    private var sweptBounds = Rect.Zero

    @Test fun utilityShadowHasOneUnclippedFieldThroughoutRepeatedReversals() = verify(nested = false)
    @Test fun nestedShadowHasOneUnclippedFieldThroughoutRepeatedReversals() = verify(nested = true)

    /** #465: a neighbor shares the trigger's union shadow. Ownership moves between the
     * page and popup windows at identical geometry on every open and return, so their
     * shared gap must not switch between union and stacked coverage at either handoff. */
    @Test fun sharedNeighborShadowHasNoOwnershipHandoffThroughRepeatedCycles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        output = File(root, "popup-shadow-continuity/shared-gap-api${Build.VERSION.SDK_INT}")
        assertTrue(output.isDirectory || output.mkdirs())
        compose.setContent { Fixture(false, sharedReference = true) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val log = StringBuilder("api=${Build.VERSION.SDK_INT} density=${compose.density.density} strength=2.0\n")
        val homeContinuity = ShadowFrameProbe()
        val frames = mutableListOf<Pair<Float, Float>>() // progress to geometry-matched error
        val rawContinuity = ShadowFrameProbe()
        var popupHomeFrames = 0
        var pageHomeFrames = 0
        var handoffs = 0
        var previousOwner: Boolean? = null
        fun frame(label: String) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val p = anchor.contact.expansion.value
            val captureName = label.takeIf { p <= .002f && previousOwner != null && previousOwner != anchor.modalPresent }
            val (gap, reference) = sharedGapSample(captureName)
            assertReferenceIsolation()
            val error = gap - reference
            if (p <= .002f) {
                homeContinuity.observeLuminance(1f - error / 255f)
                rawContinuity.observeLuminance(1f - gap / 255f)
                val owner = anchor.modalPresent
                if (owner) popupHomeFrames++ else pageHomeFrames++
                if (previousOwner != null && previousOwner != owner) handoffs++
                previousOwner = owner
            } else {
                homeContinuity.beginSegment(); rawContinuity.beginSegment(); previousOwner = null
            }
            frames += p to error
            val shape = anchor.contact.body.value
            log.append("$label p=$p hidden=${anchor.hidden} inModal=${anchor.modalPresent} window=${popupWindow != null} " +
                "strain=${shape.strain} depth=${shape.depth} gap=$gap reference=$reference error=$error $sharedSampleCoordinates\n")
        }
        fun returnToRest(label: String) {
            compose.runOnIdle { open = false }
            var settled = 0
            var index = 0
            while (settled < 4) {
                assertTrue("The popup must return and dispose: $log", index < 240)
                frame("$label-$index")
                if (popupWindow == null && !anchor.modalPresent) settled++
                index++
            }
        }
        try {
            val (rest, referenceRest) = sharedGapSample()
            log.append("rest gap=$rest reference=$referenceRest\n")
            assertTrue("The independent union must match at rest", abs(rest - referenceRest) < 4f)
            assertTrue("The neighbor union must be visible in the gap: $rest", rest > 8f)
            repeat(4) {
                frame("precheck-$it")
                assertTrue("The two unmoving unions must agree within 1/255: $log", abs(frames.last().second) <= 1f)
                val realPlane = requireNotNull(anchor.shadowPlane)
                val realOrigin = requireNotNull(realPlane.layout).backdropPosition(requireNotNull(realPlane.view))
                val realBodies = realPlane.casters.mapNotNull { (it.shape as? LayoutShape)?.laidOut()?.translate(realOrigin) }
                    .sortedBy { it.left }
                val controlBodies = requireNotNull(referencePlane).casters.mapNotNull {
                    (it.shape as? LayoutShape)?.laidOut()?.translate(referenceOrigin + Offset(0f, referenceShift))
                }.sortedBy { it.left }
                assertEquals("Both unions must contain the same two resting bodies", realBodies, controlBodies)
            }
            repeat(2) { cycle ->
                compose.runOnIdle { open = true }
                repeat(10) { frame("open$cycle-$it") }
                returnToRest("return$cycle")
            }
            // Interrupted opening, reversed close, then the final return to rest.
            compose.runOnIdle { open = true }
            repeat(8) { frame("interrupted-$it") }
            compose.runOnIdle { open = false }
            repeat(6) { frame("reversing-$it") }
            compose.runOnIdle { open = true }
            repeat(6) { frame("reopened-$it") }
            returnToRest("final")
            // The disposal deadline above is unchanged. The final body has its
            // own, slower spring: sample it until production publishes exact rest.
            var settledFrames = 0
            var settlingIndex = 0
            while (settledFrames < 4) {
                assertTrue("The final body spring must settle: $log", settlingIndex < 360)
                frame("settling-${settlingIndex++}")
                val body = anchor.contact.body.value
                if (body.strain == 0f && body.depth == 0f) settledFrames++ else settledFrames = 0
            }
            val (finalGap, _) = sharedGapSample()
            assertTrue("The settled union must return to its initial darkness: $log", abs(finalGap - rest) < 4f)
            File(output, "frames.txt").writeText(log.toString())
            // Expansion can be home while the body spring still moves. Compare each
            // presented gap with a never-transferred union at that SAME body shape.
            // No fit, filtering of handoff frames, or change to the 3/255 bound.
            log.append("rawJump=${rawContinuity.maximumDelta} jump=${homeContinuity.maximumDelta} " +
                "popupHomeFrames=$popupHomeFrames pageHomeFrames=$pageHomeFrames handoffs=$handoffs\n")
            assertTrue("Both ownership directions must be sampled: $log",
                popupHomeFrames > 0 && pageHomeFrames > 0 && handoffs >= 6)
            val home = frames.filter { it.first <= .002f }
            assertTrue("Sample both home owners: $log", home.size > 20)
            homeContinuity.assertContinuous(3f / 255f, "popup home ownership")
            val worst = home.maxOf { abs(it.second) }
            var jump = 0f
            for (index in 1 until frames.size) {
                val (previousP, previousGap) = frames[index - 1]
                val (p, gap) = frames[index]
                if (previousP <= .002f && p <= .002f) jump = maxOf(jump, abs(gap - previousGap))
            }
            assertTrue("Home shadow must keep the neighbor union through every handoff: " +
                "worst=$worst jump=$jump rest=$rest\n$log", worst < 4f && jump < 3f)
        } finally {
            File(output, "frames.txt").writeText(log.toString())
            compose.runOnIdle { open = false }
            compose.mainClock.autoAdvance = true
        }
    }

    /** Mean shadow levels (red channel, 0-255) in the gap between the neighbor and trigger. */
    private fun sharedGapSample(captureName: String? = null): Pair<Float, Float> {
        val on = syncedScreenshot()
        try {
            if (captureName != null) File(output, "$captureName.png").outputStream().use {
                assertTrue("Save the same presented handoff frame", on.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val d = compose.density.density
            val gx = ((neighboringBody.right + anchor.bounds.left) / 2f).roundToInt()
            // The flat white page at the same rows, beyond the trigger's shadow reach.
            val reference = (anchor.bounds.right + 40f * d).roundToInt()
            val rows = (-6..6).map { (anchor.bounds.center.y + it * d).roundToInt() }
            sharedSampleCoordinates = "x=$gx dx=-1..1 rows=$rows controlRows=${rows.map { it - referenceShift.roundToInt() }} " +
                "backgroundX=$reference shift=$referenceShift"
            assertTrue("The gap and reference must be on screen", gx in 1 until on.width - 1 && reference < on.width)
            var sum = 0f
            var referenceSum = 0f
            var count = 0
            for (dy in -6..6) for (dx in -1..1) {
                val y = (anchor.bounds.center.y + dy * d).roundToInt()
                val controlY = y - referenceShift.roundToInt()
                assertTrue("The independent union must remain on screen", controlY in 0 until on.height)
                if (anchor.contact.expansion.value <= .002f) {
                    val background = on.getPixel(reference, y)
                    val controlBackground = on.getPixel(reference, controlY)
                    assertTrue("Both probes need the same flat white backdrop",
                        AndroidColor.red(background) >= 254 && AndroidColor.red(controlBackground) >= 254 &&
                            AndroidColor.red(background) == AndroidColor.green(background) &&
                            AndroidColor.red(background) == AndroidColor.blue(background) &&
                            AndroidColor.red(controlBackground) == AndroidColor.green(controlBackground) &&
                            AndroidColor.red(controlBackground) == AndroidColor.blue(controlBackground))
                }
                sum += AndroidColor.red(on.getPixel(reference, y)) - AndroidColor.red(on.getPixel(gx + dx, y))
                referenceSum += AndroidColor.red(on.getPixel(reference, controlY)) - AndroidColor.red(on.getPixel(gx + dx, controlY))
                count++
            }
            return sum / count to referenceSum / count
        } finally { on.recycle() }
    }

    /** Wait until the page window and any popup window have both drawn this token:
     * shadow ownership moves between them. */
    private fun syncedScreenshot(): Bitmap {
        compose.waitForIdle()
        stage = stage % 30 + 1
        val token = stage
        val pageOrigin = IntArray(2)
        val popupOrigin = IntArray(2)
        var hasPopup = false
        compose.runOnIdle {
            pageView.rootView.getLocationOnScreen(pageOrigin)
            requireNotNull(pageMarker).apply {
                color = AndroidColor.rgb(token * 8, 255, 0); setBounds(96, 96, 112, 112); invalidateSelf()
            }
            val popupMarker = if (popupWindow != null) marker else null
            hasPopup = popupMarker != null
            popupMarker?.apply {
                color = AndroidColor.rgb(token * 8, 255, 0); setBounds(96, 136, 112, 152); invalidateSelf()
            }
            popupWindow?.decorView?.getLocationOnScreen(popupOrigin)
        }
        var screenshot: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                screenshot?.recycle()
                val next = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                screenshot = next
                // The popup's scrim may dim the page token; compare its channel ratio.
                val page = next.getPixel(pageOrigin[0] + 104, pageOrigin[1] + 104)
                val green = AndroidColor.green(page)
                green >= 100 && abs(AndroidColor.red(page) * 255f / green - token * 8) <= 4f &&
                    (!hasPopup || next.getPixel(popupOrigin[0] + 104, popupOrigin[1] + 144) == AndroidColor.rgb(token * 8, 255, 0))
            }
            return requireNotNull(screenshot)
        } catch (failure: Throwable) { screenshot?.recycle(); throw failure }
    }

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

    private data class ReturnPoint(val hidden: Boolean, val request: Long, val presented: Long,
        val modalPresent: Boolean, val shadowInModal: Boolean, val window: Boolean)

    private fun verify(nested: Boolean) {
        calibrateDefaultField()
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
            // The closing gate restores, acknowledges, transfers and releases within one
            // idle, so observe its order passively at each snapshot apply. The callback
            // only copies values; the log is written here, on the instrumentation thread.
            val returns = java.util.concurrent.ConcurrentLinkedQueue<ReturnPoint>()
            fun record() = returns.add(ReturnPoint(anchor.hidden, anchor.restoredDrawRequest,
                anchor.restoredPresentedReceipt, anchor.modalPresent, anchor.modalPresent, popupWindow != null))
            val request = compose.runOnIdle { record(); anchor.restoredDrawRequest }
            val observer = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { _, _ -> record() }
            var returned = false
            try {
                compose.runOnIdle { open = false }
                repeat(180) {
                    if (!returned) {
                        advance(16)
                        // The first page-owned frame, while the popup window is still present.
                        if (!anchor.hidden && anchor.restoredDrawn && !anchor.modalPresent && popupWindow != null) {
                            assertEquals(0f, anchor.contact.expansion.value, 0f)
                            verifyField("restored-draw-ack")
                            returned = true
                        }
                    }
                }
            } finally { observer.dispose() }
            val points = returns.toList()
            File(output, "return-order.txt").writeText(points.joinToString("\n", "request=$request\n"))
            assertTrue("Capture the final popup-owned shadow before native disposal: $points", returned)
            val receipt = request + 1
            assertEquals("One restored-draw request per return: $points", receipt, anchor.restoredDrawRequest)
            val released = points.indexOfFirst { !it.modalPresent }
            assertTrue("The return releases the popup: $points", released > 0)
            assertTrue("The popup keeps its shadow until the restored trigger is presented: $points",
                points.take(released).any { !it.hidden && it.presented == receipt && it.modalPresent && it.shadowInModal && it.window })
            assertTrue("Ownership releases only after that receipt: $points",
                points.drop(released).all { it.presented == receipt && !it.hidden })
            repeat(12) { if (popupWindow != null) advance(16) }
            assertNull("The returning native window must dispose", popupWindow)
            if (!nested) verifyField("first-trigger-owned-frame", popup = false)
            assertFalse(anchor.modalPresent)
            assertFalse(anchor.hidden)
            assertFalse(anchor.modalPresent)
            // A complete close/reopen uses a new window, not an old retained shadow.
            compose.runOnIdle { open = true }
            advanceEndpoint(1200)
            assertNotSame(window, popupWindow)
            verifyField("replacement-window")
        } finally {
            compose.runOnIdle { open = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Composable private fun Fixture(nested: Boolean, sharedReference: Boolean = false) {
        LocalicaTheme {
            val page = LocalView.current
            DisposableEffect(page) {
                val next = FrameMarker()
                pageView = page; pageMarker = next
                page.rootView.overlay.add(next)
                onDispose { page.rootView.overlay.remove(next); pageMarker = null }
            }
            val appearance = if (sharedReference) GlassAppearance(shadowStrength = 2f, shadowSpread = .6f, shapeDamping = 2f) else GlassAppearance()
            CompositionLocalProvider(LocalGlassAppearance provides appearance, LocalReducedMotion provides false) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                val modal = rememberFrostedBackdrop(Unit, dark = false)
                val group = rememberShadowPlane()
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides modal,
                    LocalPageShadows provides group) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(modal).shadowPlaneHost(group, appearance.shadowStrength, appearance.shadowSpread)) {
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
                            if (sharedReference) SharedUnionReference()
                            Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                                .padding(horizontal = 56.dp, vertical = 72.dp).fillMaxWidth().height(64.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiquidGlassSurface(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                                    neighboringBody = Rect(it.backdropPosition(page), androidx.compose.ui.geometry.Size(
                                        it.size.width.toFloat(), it.size.height.toFloat()))
                                }.pageGlassCaster(),
                                    source = source, shadow = false) {}
                                PopupActionPill(anchor, source, Modifier.width(104.dp).fillMaxHeight(),
                                    onSearch = {}, onMore = { open = true }, role = io.github.anastaci1a.localica.ui.components.TriggerRole.Navigation)
                            }
                            Menu()
                        }
                    }
                }
            }
        }
    }

    private fun homeBodies(): List<RoundRect> {
        if (neighboringBody == Rect.Zero || anchor.bounds == Rect.Zero) return emptyList()
        val shape = anchor.contact.body.value
        val sx = shape.scaleX(anchor.contact.vertical)
        val sy = shape.scaleY(anchor.contact.vertical)
        val rect = anchor.bounds
        val half = Offset(rect.width * sx / 2f, rect.height * sy / 2f)
        val corner = minOf(rect.width, rect.height) / 2f
        return listOf(RoundRect(neighboringBody, CornerRadius(neighboringBody.height / 2f)),
            RoundRect(Rect(rect.center - half, rect.center + half), CornerRadius(corner * sx, corner * sy)))
    }

    private fun assertReferenceIsolation() {
        val plane = requireNotNull(referencePlane)
        assertTrue("The control stays on the same page host class", plane.view?.rootView === pageView.rootView)
        assertTrue("The control has exactly two independent casters", plane.casters.size == 2)
        referenceCasters.forEach { caster ->
            assertTrue("The control caster must never transfer", requireNotNull(caster).ownsWindow(plane))
            assertFalse("The real union must not contain a control caster", anchor.shadowPlane?.casters?.any { it === caster } == true)
        }
        // The spread and 8dp vertical shadow shift are specified by the renderer.
        // Check the FULL padded outlines at every sampled strain/depth, not just
        // the gap or the undeformed 64dp body. A separate plane prevents merging.
        val pad = (8f + 48f) * compose.density.density + 2f
        val shift = Offset(0f, -referenceShift)
        val bodies = homeBodies().map { it.boundingRect.inflate(pad) }
        val controls = bodies.map { it.translate(shift) }
        assertTrue("The full control spread must not clip at the top", controls.all { it.top >= 0f })
        assertTrue("The real and control fields must be outside each other's reach",
            controls.none { control -> bodies.any { it.overlaps(control) } })
    }

    /** A separate page ShadowPlane with the same live home geometry. Its two
     * SurfaceNodes never follow popup motion or transfer to another native host.
     * Integer translation preserves the pixel phase in the same display capture. */
    @Composable private fun SharedUnionReference() {
        val plane = rememberShadowPlane()
        val page = LocalView.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        SideEffect {
            referencePlane = plane
            referenceShift = with(density) { 192.dp.roundToPx().toFloat() }
        }
        Box(Modifier.fillMaxSize().onGloballyPositioned { referenceOrigin = it.backdropPosition(page) }
            .shadowPlaneHost(plane, 2f, .6f)) {
            repeat(2) { index ->
                val key = remember { Any() }
                Box(Modifier.matchParentSize().glassCaster(plane, key,
                    onNode = { node, bound -> referenceCasters[index] = node.takeIf { bound } }) {
                    homeBodies().getOrNull(index)?.translate(-referenceOrigin - Offset(0f, referenceShift))
                })
            }
        }
    }

    @Composable private fun Menu() {
        GlassMenu(open, anchor, { open = false }) {
            val view = LocalView.current
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val plane = LocalShadowPlane.current
            val window = (view.parent as DialogWindowProvider).window
            DisposableEffect(window) {
                val next = FrameMarker()
                popupView = view; popupWindow = window; popupDensity = density; popupPlane = plane; marker = next
                window.decorView.overlay.add(next)
                onDispose {
                    window.decorView.overlay.remove(next)
                    if (popupWindow === window) {
                        popupView = null; popupWindow = null; popupDensity = null; popupPlane = null; marker = null
                    }
                }
            }
            ActionMenuItem("Scan music", icon = AppIcon.TabFolders, onClick = {})
            ActionMenuItem("Settings", icon = AppIcon.ActionSettings, onClick = {})
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

    private class SweptShadowErrors(private val bounds: Rect) {
        private val sums = FloatArray(4)
        private val counts = IntArray(4)
        var maximum = 0f
            private set
        val count get() = counts.sum()
        val mean get() = sums.sum() / count
        val sideMeans get() = counts.indices.filter { counts[it] != 0 }.map { sums[it] / counts[it] }
        val accepted get() = maximum < .055f && mean < .015f && sideMeans.all { it < .015f }

        fun add(point: Offset, expected: Float, actual: Float) {
            if (expected < .015f && abs(actual) < .015f) return
            val delta = point - bounds.center
            // A localized trail can disappear in the average around the whole contour.
            val side = if (abs(delta.x) / bounds.width > abs(delta.y) / bounds.height) {
                if (delta.x < 0f) 0 else 1
            } else if (delta.y < 0f) 2 else 3
            val error = abs(actual - expected)
            maximum = maxOf(maximum, error)
            sums[side] += error
            counts[side]++
        }
    }

    private fun calibrateDefaultField() {
        val strip = (4..12).flatMap { x -> (-2..2).map { y -> Offset(160f + x, 28f + y) } }
        val exterior = (-24..184 step 3).flatMap { x -> (-24..80 step 3).map { y -> Offset(x.toFloat(), y.toFloat()) } }
        for ((sx, sy) in listOf(1f to 1f, 1.07f to .93f, .96f to 1.04f)) {
            val bounds = Rect(80f - 80f * sx, 28f - 28f * sy, 80f + 80f * sx, 28f + 28f * sy)
            val contour = ShadowMassContour(bounds.left, bounds.top, bounds.right, bounds.bottom, 28f * sx, 28f * sy)
            fun distance(point: Offset, shift: Float) = contour.distance(point.x.toDouble(), point.y.toDouble(), shift)
            fun field(point: Offset) = shadowPolynomialReference(distance(point, 8f).toFloat())
            fun translated(point: Offset) = field(point - Offset(24f, 0f))
            val controls = mapOf<String, (Offset) -> Float>(
                "unchanged" to ::field,
                "translated" to ::translated,
                "clipped" to { if (it.x > 164f || it.y > 60f || it.x < -4f || it.y < -4f) 0f else field(it) },
                "trailing" to { maxOf(field(it), translated(it)) },
            )
            for ((name, control) in controls) {
                val stripErrors = strip.map { control(it) - field(it) }
                val swept = SweptShadowErrors(bounds)
                for (point in exterior) if (distance(point, 0f) > 2.0) {
                    swept.add(point, field(point), control(point))
                }
                val unchanged = name == "unchanged"
                assertEquals("$name strip at $sx,$sy", unchanged,
                    stripErrors.maxOf { abs(it) } < .035f && abs(stripErrors.sum()) / stripErrors.size < .015f)
                assertEquals("$name exterior at $sx,$sy: mean=${swept.mean} sides=${swept.sideMeans} max=${swept.maximum}",
                    unchanged, swept.accepted)
            }
        }
    }

    private fun verifyField(name: String, popup: Boolean = true) {
        // This is the highest (tested) native window, including for a nested menu.
        val nodes = if (popup) compose.popupGeometryNodes(anchor, open, output, name, geometryWindowCount) else emptyList()
        if (popup && name == "first-open") geometryWindowCount = nodes.size
        val bounds = if (popup) nodes.minBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot else anchor.bounds
        val origin = IntArray(2)
        if (popup) compose.runOnIdle { requireNotNull(popupView).getLocationOnScreen(origin) }
        val screen = bounds.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
        val d = compose.density.density
        val (caster, inheritsScale) = compose.runOnUiThread {
            val native = popupPlane.takeIf { popup }
            val node = requireNotNull(anchor.surfaceNode as? SurfaceNode ?: native?.node(anchor))
            val plane = native?.takeIf(node::owns) ?: requireNotNull(node.sourcePlane)
            val contour = node.frameOn(plane).bodies.single()
            val position = requireNotNull(plane.layout).localToScreen(Offset.Zero)
            contour.translate(position) to (plane === native && node.fieldInheritsBodyScale)
        }
        val drawn = caster.boundingRect
        val sx = if (inheritsScale) drawn.width / screen.width else 1f
        val sy = if (inheritsScale) drawn.height / screen.height else 1f
        val corner = caster.topLeftCornerRadius
        val radiusX = corner.x / sx
        val radiusY = if (inheritsScale) radiusX else corner.y
        val contour = ShadowMassContour(drawn.left, drawn.top, drawn.right, drawn.bottom, radiusX, radiusY)
        fun distance(point: Offset, shifted: Boolean) =
            contour.distance(point.x.toDouble(), point.y.toDouble(), if (shifted) 8f * d else 0f, sx, sy)
        val right = drawn.right
        // This strip and its unshadowed reference are over the same flat scene:
        // white beside the utility, or the flat content inside the enclosing panel.
        // The clock stays frozen, including the last frame before window disposal.
        val exports = PopupShadowFrameExports(output, PopupShadowFrameExports.enabled(
            InstrumentationRegistry.getArguments().getString("popupShadowFrameExports")))
        val on = screenshot()
        exports.verify(on, name, { if (sweptBounds == Rect.Zero) screen else sweptBounds }) {
            ShadowFrameProbe.assertSpreadEdge(on.asImageBitmap(),
                Rect(right + 20f*d*sx, drawn.center.y - 2*d, right + 22f*d*sx, drawn.center.y + 2*d),
                Rect(right + 22f*d*sx, drawn.center.y - 2*d, right + 24f*d*sx, drawn.center.y + 2*d),
                .015f, "popup $name")
            var maximumError = 0f
            var expectedTotal = 0f
            var actualTotal = 0f
            var count = 0
            val p = anchor.contact.expansion.value.coerceIn(0f, 1f)
            for (offset in 4..12) for (dy in -2..2) {
                val x = (right + offset * d * sx).roundToInt()
                val y = (drawn.center.y + dy * d * sy).roundToInt()
                assertTrue("Entire padded sample must be on screen", x in 0 until on.width && y in 0 until on.height)
                val expected = shadowPolynomialReference(distance(Offset(x + .5f, y + .5f), shifted = true).toFloat() / d)
                val referenceX = (right + 36f*d*sx).roundToInt()
                assertTrue("The unshadowed reference must remain on screen", referenceX in 0 until on.width)
                val background = AndroidColor.red(on.getPixel(referenceX, y)).toFloat()
                val actual = 1f - AndroidColor.red(on.getPixel(x, y)) / background.coerceAtLeast(1f)
                maximumError = maxOf(maximumError, abs(actual - expected))
                expectedTotal += expected; actualTotal += actual; count++
            }
            // Also check the entire swept exterior. A right-aligned menu can leave
            // its right edge nearly stationary while old top/left silhouettes trail.
            val padded = drawn.inflate(24f*d*maxOf(sx, sy))
            sweptBounds = if (sweptBounds == Rect.Zero) padded else Rect(
                minOf(sweptBounds.left, padded.left), minOf(sweptBounds.top, padded.top),
                maxOf(sweptBounds.right, padded.right), maxOf(sweptBounds.bottom, padded.bottom))
            val parentInterior = nodes.maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
                ?.takeIf { nodes.size > 1 }?.boundsInRoot?.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))?.deflate(32*d)
            val background = AndroidColor.red(on.getPixel((right + 36f*d*sx).roundToInt(), drawn.center.y.roundToInt())).toFloat()
            val exterior = SweptShadowErrors(drawn)
            for (y in sweptBounds.top.toInt().coerceAtLeast(0) until sweptBounds.bottom.toInt().coerceAtMost(on.height) step 3)
                for (x in sweptBounds.left.toInt().coerceAtLeast(0) until sweptBounds.right.toInt().coerceAtMost(on.width) step 3) {
                    val point = Offset(x + .5f, y + .5f)
                    if (parentInterior != null && !parentInterior.contains(point)) continue
                    if (neighboringBody != Rect.Zero && neighboringBody.inflate(26*d).contains(point)) continue
                    if (distance(point, shifted = false) <= 2f / minOf(sx, sy)) continue // Antialiased material edge.
                    val expected = shadowPolynomialReference(distance(point, shifted = true).toFloat() / d)
                    val actual = 1f - AndroidColor.red(on.getPixel(x,y))/background.coerceAtLeast(1f)
                    exterior.add(point, expected, actual)
                }
            assertTrue("The swept exterior must include a measurable shadow", exterior.count > 20)
            File(output, "profiles.txt").appendText(
                "$name sweptCount=${exterior.count} sweptMean=${exterior.mean} sweptMax=${exterior.maximum} sides=${exterior.sideMeans}\n")
            // Save the entire synthetic popup region so off-axis trails are reviewable.
            val fullLeft = sweptBounds.left.roundToInt().coerceIn(0, on.width-1)
            val fullTop = sweptBounds.top.roundToInt().coerceIn(0, on.height-1)
            val fullWidth = (sweptBounds.right.roundToInt()-fullLeft).coerceIn(1, on.width-fullLeft)
            val fullHeight = (sweptBounds.bottom.roundToInt()-fullTop).coerceIn(1, on.height-fullTop)
            exports.success(on, "$name-field", fullLeft, fullTop, fullWidth, fullHeight)
            assertTrue("$name must retain one exterior field: max=${exterior.maximum} mean=${exterior.mean} sides=${exterior.sideMeans}",
                exterior.accepted)
            // Keep a complete perpendicular shadow profile as well.
            val left = (right + 2*d*sx).roundToInt().coerceIn(0, on.width - 1)
            val top = (drawn.center.y - 8*d*sy).roundToInt().coerceIn(0, on.height - 1)
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

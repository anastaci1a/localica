package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color as NativeColor
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import io.github.anastaci1a.localica.ui.engine.ownsWindow
import io.github.anastaci1a.localica.ui.engine.UiClock
import io.github.anastaci1a.localica.ui.engine.LocalUiClock
import kotlin.math.ceil
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual QueueScreen caller, rather than the generic popup used by the field oracle. */
class QueueSelectorPersistenceTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun selectedNameKeepsOneLayoutInstanceAcrossNativeOpenAndReturn() = QueueSelectorCallerFixture(compose).verify(false)
    @Test fun selectedNameKeepsOneLayoutInstanceAcrossCancelledOpenAndReversal() = QueueSelectorCallerFixture(compose).verify(true)
}

/** Playback still owns initial scroll when the browsed queue is outside the viewport. */
class QueueSelectorOffscreenNameTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun browsedNameSurvivesWhenPlaybackStartsTheSelectorElsewhere() =
        QueueSelectorCallerFixture(compose, browsedAwayFromPlayback = true).verify(false)
}

/** Actual native return after the source is restored: the origin receiver is no longer active.
 * This scoped oracle does not claim support for the earlier native receiver/exclusion phase. */
class QueueSelectorFieldReturnTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun restoredQueueSelectorKeepsOneFieldThroughNativeReturn() = QueueSelectorCallerFixture(compose).verify(false, field = true)
    @Test fun cancelledQueueSelectorKeepsOneFieldThroughNativeReturn() = QueueSelectorCallerFixture(compose).verify(true, field = true)
}

private class QueueSelectorCallerFixture(private val compose: androidx.compose.ui.test.junit4.ComposeContentTestRule,
    private val browsedAwayFromPlayback: Boolean = false) {
    private val label = "${if (browsedAwayFromPlayback) 80 else 1}. Persistent queue"
    private var window: Window? = null
    private lateinit var sourceView: View
    private val markers = linkedMapOf<View, Marker>()
    private var token = 0
    private lateinit var clock: UiClock

    fun verify(interrupted: Boolean, field: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = nativeEvidenceDirectory("queue-selector-${if (field) "field-return" else "persistence"}-${if (interrupted) "interrupted" else "complete"}${if (browsedAwayFromPlayback) "-browsed-away" else ""}",
            instrumentation.targetContext.cacheDir)
        val queues = if (browsedAwayFromPlayback) List(80) { index ->
            ListeningQueue(id = "queue-$index", name = if (index == 79) "Persistent queue" else "Queue ${index + 1}", entries = emptyList())
        } else listOf(ListeningQueue(id = "selected", name = "Persistent queue", entries = emptyList()))
        val state = QueueUiState(QueueLibrary(queues, queues.first().id), loading = false, browsedId = queues.last().id)
        compose.setContent { LocalicaTheme {
            val view = LocalView.current
            val ownerClock = LocalUiClock.current
            SideEffect { clock = ownerClock }
            DisposableEffect(view) {
                sourceView = view
                attach(view.rootView)
                onDispose { detach(view.rootView) }
            }
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = .8f, shadowSpread = .6f),
                LocalGlassMorphWorkObserver provides { kind, native, _ ->
                    if (kind == "dialog" && native != null) { window = native; attach(native.decorView) }
                }) {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("queue-selector-caller")) { QueueScreen(state, null, onMinimize = {}) }
            }
        } }
        compose.waitForIdle()
        val home = logicalNames().single().layoutInfo
        // Read the actual source descriptor from the real modifier chain; no new production hook.
        val icon = compose.onNodeWithContentDescription("Select queue").fetchSemanticsNode()
        val anchor = compose.runOnUiThread {
            generateSequence(icon.layoutInfo) { it.parentInfo }
                .flatMap { it.getModifierInfo().asSequence() }
                .mapNotNull { it.modifier.glassSurfaceAnchor() }.first()
        }
        val log = StringBuilder("actual QueueScreen; interrupted=$interrupted; source=${System.identityHashCode(home)}\n")
        val instances = linkedSetOf<LayoutInfo>(home)
        val missing = mutableListOf<String>()
        var frame = 0
        val fieldInputs = mutableListOf<FieldInput>()
        val fieldFailures = mutableListOf<String>()
        fun sample(stage: String) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val names = logicalNames()
            names.forEach { instances += it.layoutInfo }
            if (names.size != 1) missing += "$stage:${names.size}"
            val native = window?.takeIf { it.decorView.isAttachedToWindow }
            val phase = anchor.contact.expansion.value
            log.append("$frame $stage phase=$phase hidden=${anchor.hidden} modal=${anchor.modalPresent} inModal=${anchor.shadowInModal} native=${native != null} labels=${names.map { System.identityHashCode(it.layoutInfo) }}\n")
            val input = if (field && phase <= .002f && !anchor.hidden)
                captureHomeField(anchor, native, frame) else null
            val bitmap = input?.capture?.image?.asAndroidBitmap() ?: screenshot(native)
            val actualFile = File(output, "${(frame++).toString().padStart(3, '0')}-$stage.png")
            try { actualFile.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
                if (input != null) fieldInputs += input.copy(actual = actualFile)
            } finally { if (input == null) bitmap.recycle() }
        }
        fun close() { UiDevice.getInstance(instrumentation).pressBack() }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Select queue").performClick()
            var admitted = false
            for (i in 0 until 90) {
                sample("opening")
                if (anchor.hidden && (!interrupted || anchor.contact.expansion.value in .05f.. .8f)) {
                    admitted = true
                    if (interrupted) break
                }
                if (!interrupted && anchor.contact.expansion.value >= .999f && anchor.hidden) break
            }
            assertTrue("Must exercise the actual admitted native caller\n$log", admitted)
            close()
            if (interrupted) {
                repeat(3) { sample("reverse-close") }
                // The closing window yields native input immediately. Re-enter through the
                // real trigger's screen position even while its semantics are still hidden.
                val center = compose.runOnUiThread { anchor.bounds.center }
                UiDevice.getInstance(instrumentation).click(center.x.roundToInt(), center.y.roundToInt())
                repeat(5) { sample("reopening") }
                close()
            }
            var settled = 0
            for (i in 0 until 180) {
                sample("return")
                if (!anchor.modalPresent && window?.decorView?.isAttachedToWindow != true) settled++ else settled = 0
                if (settled == 4) break
            }
            assertEquals("Native return and four source frames are mandatory\n$log", 4, settled)
            if (field) {
                // Native reference work runs only after capturing the complete actual transition.
                // It cannot consume unobserved native frames in the middle of the handoff.
                for (input in fieldInputs) {
                    inspectHomeField(input.capture.image.asAndroidBitmap(), input, output, log)?.let(fieldFailures::add)
                }
                assertTrue("The first restored frame and settled source frames must be included\n$log", fieldInputs.size >= 4)
                assertTrue("Native-held restored source must have both real window receipts\n$log",
                    fieldInputs.any { it.capture.windowIds.size == 2 })
                assertTrue("Source ownership after native disposal must be included\n$log",
                    fieldInputs.any { it.sourceOwned && it.capture.windowIds.size == 1 })
                assertTrue("Actual Queue return differs from its independent current-geometry field: $fieldFailures\n$log", fieldFailures.isEmpty())
            } else {
                assertTrue("One name remains present through every sampled caller frame: $missing\n$log", missing.isEmpty())
                assertEquals("The name must be one persistent layout instance across both native roots\n$log", 1, instances.size)
            }
        } finally {
            File(output, "frames.txt").writeText(log.toString())
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { markers.keys.toList().forEach(::detach) }
            fieldInputs.forEach { it.capture.image.asAndroidBitmap().recycle() }
        }
    }

    private data class FieldInput(val frame: Int, val actual: File?, val rect: Rect, val body: RoundRect,
        val sourceOwned: Boolean, val capture: PresentedCapture)

    private fun captureHomeField(anchor: GlassAnchor, native: Window?, frame: Int): FieldInput {
        check(!anchor.hidden) { "Unsupported: active native origin receiver requires its own independent model" }
        val node = anchor.surfaceNode
        val home = anchor.shadowPlane
        val sourceOwned = node != null && home != null && node.ownsWindow(home)
        val nativeSurface = if (native != null && !sourceOwned)
            compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().singleOrNull() else null
        val rect = if (nativeSurface != null) {
            val view = (nativeSurface.root as ViewRootForTest).view
            val origin = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
            nativeSurface.boundsInRoot.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
        } else anchor.bounds
        val phase = anchor.contact.expansion.value.coerceIn(0f, 1f)
        val density = compose.density.density
        val radius = (anchor.bounds.height / 2 * (1f - phase) + 28f * density * phase)
            .coerceAtMost(minOf(rect.width, rect.height) / 2)
        val body = requireNotNull(glassCasterBody(rect, compose.density, (radius / density).dp, anchor))
        val shape = anchor.contact.body.value
        val sx = shape.scaleX(anchor.contact.vertical)
        val sy = shape.scaleY(anchor.contact.vertical)
        val sequence = clock.stampNow.sequence
        val epoch = requireNotNull(home).host.routeEpoch
        val state = ShadowDrawState(sequence, epoch, listOf(body to 1f), Color.White,
            scaleX = sx, scaleY = sy, strength = .8f, spread = .6f)
        fun receipt(): ShadowDrawState {
            check(clock.stampNow.sequence == sequence && anchor.contact.expansion.value.coerceIn(0f, 1f) == phase &&
                anchor.contact.body.value == shape && !anchor.hidden) { "Incomplete: Queue geometry changed during native capture" }
            return state
        }
        val extras = listOfNotNull(native?.let { popup ->
            ShadowNativeWindow(popup, popup.decorView, IntOffset(18, 1), popup = {
                PopupShadowComposition(if (anchor.modalPresent) PopupShadowScrim(phase, (.16f * phase * 255f).roundToInt()) else null)
            }) { receipt() }
        })
        val capture = ShadowNativeCapture(compose, compose.onNodeWithTag("queue-selector-caller"), extras,
            invalidateWindow = false) { clock.stampNow.sequence }.use { owner ->
            owner.capture(epoch, popup = native?.let { popup -> { PopupShadowComposition(higherWindows = listOf(popup)) } }) { receipt() }
        }
        return FieldInput(frame, null, rect, body, sourceOwned, capture)
    }

    private fun inspectHomeField(actual: Bitmap, input: FieldInput, output: File, log: StringBuilder): String? {
        val (frame, _, rect, body, sourceOwned) = input
        val density = compose.density.density
        val state = input.capture.nativeWindows.first().state
        log.append("field-receipts frame=$frame state=$state native=${input.capture.nativeWindows}\n")
        check(state.scaleX == 1f && state.scaleY == 1f) {
            "Incomplete: independent Queue private-field reference must model anisotropic rebound, not substitute a fixed-pixel kernel: $state"
        }
        val reference = shadowUnionReference(compose, actual.width, actual.height, state).asAndroidBitmap()
        try {
            val left = (((body.left + body.right) / 2f) - 6 * density).roundToInt().coerceAtLeast(0)
            val right = ((body.left + body.right) / 2f + 6 * density).roundToInt().coerceAtMost(actual.width - 1)
            val top = ceil(body.bottom + 2 * density).toInt()
            val bottom = ceil(body.bottom + 8 * density).toInt()
            check(top > body.bottom && bottom < actual.height) { "Field ROI must stay outside the current body" }
            val region = Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
            val blank = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888).apply { eraseColor(NativeColor.WHITE) }
            val probe = ShadowFieldProbe(listOf(region), 1f / 255f)
            val comparison = try {
                probe.compare(input.capture, input.capture.copy(image = reference.asImageBitmap(), offscreen = true),
                    input.capture.copy(image = blank.asImageBitmap(), offscreen = true))
            } finally { blank.recycle() }
            log.append("field frame=$frame sourceOwned=$sourceOwned rect=$rect body=$body roi=$region comparison=$comparison\n")
            File(output, "reference-${frame.toString().padStart(3, '0')}.png").outputStream().use {
                check(reference.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            return try { probe.assertWithin(comparison, 2f / 255f, "Actual Queue restored-source bottom field frame=$frame"); null }
                catch (failure: AssertionError) { failure.message }
        } finally { reference.recycle() }
    }

    /** Endpoint measurement copies are deliberately unplaced. A shared semantic container
     * and its internal text renderer describe one instance, rather than two independent names. */
    private fun logicalNames() = compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
        .filter { node ->
            generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isPlaced } &&
                generateSequence(node.parent) { it.parent }.none { parent ->
                    parent.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                }
        }

    private fun attach(view: View) {
        if (markers.containsKey(view)) return
        val marker = Marker()
        markers[view] = marker
        view.overlay.add(marker)
    }
    private fun detach(view: View) { markers.remove(view)?.let { view.overlay.remove(it) } }

    /** Overlay-only native tokens do not invalidate the Queue's shadow or text recording. */
    private fun screenshot(popup: Window?): Bitmap {
        token = token % 30 + 1
        val color = NativeColor.rgb(token * 8, 255, 0)
        val targets = listOfNotNull(sourceView.rootView, popup?.decorView).distinct()
        val origins = targets.mapIndexed { index, target -> IntArray(3).also { origin -> compose.runOnUiThread {
            val point = IntArray(2).also(target::getLocationOnScreen)
            origin[0] = point[0]; origin[1] = point[1]; origin[2] = 96 + index * 40
            markers.getValue(target).apply { this.color = color; setBounds(96, origin[2], 112, origin[2] + 16); invalidateSelf() }
        } } }
        var latest: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                latest?.recycle()
                val next = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                latest = next
                origins.all { origin ->
                    val pixel = next.getPixel(origin[0] + 104, origin[1] + origin[2] + 8)
                    val green = NativeColor.green(pixel)
                    green >= 100 && abs(NativeColor.red(pixel) * 255f / green - token * 8) <= 4f
                }
            }
            return requireNotNull(latest)
        } catch (failure: Throwable) { latest?.recycle(); throw failure }
    }

    private class Marker : Drawable() {
        var color = NativeColor.GREEN
        private val paint = Paint()
        override fun draw(canvas: Canvas) { paint.color = color; canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }
}

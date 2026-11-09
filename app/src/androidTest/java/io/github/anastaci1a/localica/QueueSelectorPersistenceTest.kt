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
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalView
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
    private val label = "1. Persistent queue"
    private var window: Window? = null
    private lateinit var sourceView: View
    private val markers = linkedMapOf<View, Marker>()
    private var token = 0

    @Test fun selectedNameKeepsOneLayoutInstanceAcrossNativeOpenAndReturn() = verify(false)
    @Test fun selectedNameKeepsOneLayoutInstanceAcrossCancelledOpenAndReversal() = verify(true)

    private fun verify(interrupted: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = nativeEvidenceDirectory("queue-selector-persistence-${if (interrupted) "interrupted" else "complete"}",
            instrumentation.targetContext.cacheDir)
        val state = QueueUiState(QueueLibrary(listOf(ListeningQueue(id = "selected", name = "Persistent queue", entries = emptyList())), "selected"), loading = false)
        compose.setContent { LocalicaTheme {
            val view = LocalView.current
            DisposableEffect(view) {
                sourceView = view
                attach(view.rootView)
                onDispose { detach(view.rootView) }
            }
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = .8f, shadowSpread = .6f),
                LocalGlassMorphWorkObserver provides { kind, native, _ ->
                    if (kind == "dialog" && native != null) { window = native; attach(native.decorView) }
                }) {
                Box(Modifier.fillMaxSize().background(Color.White)) { QueueScreen(state, null, onMinimize = {}) }
            }
        } }
        compose.waitForIdle()
        val home = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().layoutInfo
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
        fun sample(stage: String) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val names = compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
            names.forEach { instances += it.layoutInfo }
            if (names.size != 1) missing += "$stage:${names.size}"
            val native = window?.takeIf { it.decorView.isAttachedToWindow }
            val phase = anchor.contact.expansion.value
            log.append("$frame $stage phase=$phase hidden=${anchor.hidden} modal=${anchor.modalPresent} inModal=${anchor.shadowInModal} native=${native != null} labels=${names.map { System.identityHashCode(it.layoutInfo) }}\n")
            val bitmap = screenshot(native)
            try { File(output, "${(frame++).toString().padStart(3, '0')}-$stage.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
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
            assertTrue("One name remains present through every sampled caller frame: $missing\n$log", missing.isEmpty())
            assertEquals("The name must be one persistent layout instance across both native roots\n$log", 1, instances.size)
        } finally {
            File(output, "frames.txt").writeText(log.toString())
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { markers.keys.toList().forEach(::detach) }
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

package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Proposed #600 material-region oracle; original source assertions remain in their classes.
 * The control suppresses foreground drawing; two native overlay fault injections must fail. Window synchronization
 * adds invalidation/readback work and does not certify retained-field recording identity.
 */
class PopupMaterialRegionProposalTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val method = org.junit.rules.TestName()
    private val evidence = HomeNativeEvidence { "proposal-material-${method.methodName}" }

    private enum class Trigger { ListMore, PillMore, Cover }

    private lateinit var anchor: GlassAnchor
    private var materialOnly = false
    private enum class Mutation { None, Cut, PersistentTint }
    private var mutation = Mutation.None
    private var sampleIndex = 0
    private var lastBackground = 128
    private val faults = java.util.IdentityHashMap<android.view.View, FaultPatch>()
    private var rootView: android.view.View? = null
    private var popupView: android.view.View? = null
    private var selected by mutableStateOf(false)
    private var open by mutableStateOf(false)
    private var itemHeight by mutableStateOf(72.dp)

    @Composable private fun Fixture(trigger: Trigger) {
        LocalicaTheme { Box(Modifier.fillMaxSize()) {
            rootView = LocalView.current
            anchor = rememberGlassAnchor(deferContextPlacement = false)
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFF808080)) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                ConnectedSelectionHost(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height(itemHeight).testTag("item")
                            .selectionLens(selected, false, itemKey = "row", anchor = anchor)) {
                            when (trigger) {
                                Trigger.ListMore -> Box(Modifier.align(Alignment.CenterEnd)) {
                                    ActionIcon(AppIcon.ActionEllipsis, "More", anchor = anchor, quiet = true) { open = true } }
                                Trigger.PillMore -> Box(Modifier.align(Alignment.CenterEnd)) {
                                    ActionIcon(AppIcon.ActionEllipsis, "More", anchor = anchor, glass = false,
                                        glyph = { modifier -> androidx.compose.material3.Icon(appIconPainter(AppIcon.ActionEllipsis), "More",
                                            modifier.then(if (materialOnly) Modifier.drawWithContent { } else Modifier)) }) { open = true }
                                    if (materialOnly) SideEffect { anchor.face = { androidx.compose.material3.Icon(
                                        appIconPainter(AppIcon.ActionEllipsis), null, Modifier.size(24.dp).drawWithContent { }) } } }
                                Trigger.Cover -> Box(Modifier.align(Alignment.CenterStart).size(itemHeight)
                                    .contextMenuAnchor(anchor)) { Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFFB4D8C8)) } }
                            }
                        }
                        Spacer(Modifier.height(itemHeight))
                    }
                }
                GlassMenu(open, anchor, { open = false }) {
                    popupView = LocalView.current
                    Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("Play now", Modifier.then(if (materialOnly) Modifier.drawWithContent { } else Modifier)); Spacer(Modifier.height(80.dp)); Text("Add to queue", Modifier.then(if (materialOnly) Modifier.drawWithContent { } else Modifier)) }
                }
            }
        } }
    }

    private fun surface(): Rect? {
        val nodes = compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes()
        val view = popupView ?: return null
        if (nodes.isEmpty()) return null
        val origin = IntArray(2); view.getLocationOnScreen(origin)
        return nodes.single().boundsInRoot.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
    }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    /** Open, sampling the first frames; returns them. */
    private fun openFrames(count: Int): List<Rect> {
        compose.runOnIdle { open = true }
        val frames = mutableListOf<Rect>()
        var guard = 0
        while (frames.isEmpty() && guard++ < 400) { frame(); surface()?.let { frames += it } }
        assertTrue("The popup surface appears", frames.isNotEmpty())
        repeat(count) { frame(); surface()?.let { frames += it } }
        return frames
    }
    private fun start(trigger: Trigger) {
        open = false; selected = false; popupView = null
        compose.setContent { Fixture(trigger) }
        compose.waitForIdle()
    }

    @Test fun qualifiedRegionWithOriginalForeground() { materialOnly = false; fadeCheck(Trigger.PillMore) }
    @Test fun qualifiedRegionWithMaterialOnly() { materialOnly = true; fadeCheck(Trigger.PillMore) }

    @Test fun abruptMaterialLossMutantMustFailCliff() { mutation = Mutation.Cut; fadeCheck(Trigger.PillMore) }
    @Test fun persistentTintMutantMustFailEndpoint() { mutation = Mutation.PersistentTint; fadeCheck(Trigger.PillMore) }

    // Fixed before measuring fade amplitudes, from paired foreground-ablation originals.
    private fun region(b: Rect) = Rect(b.left + b.width * .40625f, b.top + b.height * .21875f,
        b.left + b.width * .78125f, b.top + b.height * .4375f)

    private class FaultPatch : android.graphics.drawable.Drawable() {
        var color = 0
        private val paint = android.graphics.Paint()
        override fun draw(canvas: android.graphics.Canvas) { paint.color = color; canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { }
        @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
    private fun updateFault() {
        if (mutation == Mutation.None || sampleIndex < 3) return
        compose.runOnUiThread {
            val view = listOfNotNull(rootView, popupView).lastOrNull { it.isAttachedToWindow } ?: error("No fault target")
            faults.forEach { (old, patch) -> if (old !== view) old.overlay.remove(patch) }
            val patch = faults.getOrPut(view) { FaultPatch() }.also { view.overlay.remove(it); view.overlay.add(it) }
            val origin = IntArray(2); view.getLocationOnScreen(origin)
            val r = region(anchor.presentedBounds)
            patch.setBounds(kotlin.math.floor(r.left).toInt() - origin[0] - 2,
                kotlin.math.floor(r.top).toInt() - origin[1] - 2,
                kotlin.math.ceil(r.right).toInt() - origin[0] + 2,
                kotlin.math.ceil(r.bottom).toInt() - origin[1] + 2)
            val value = if (mutation == Mutation.Cut) lastBackground else 220
            patch.color = android.graphics.Color.rgb(value, value, value)
            patch.invalidateSelf(); view.invalidate()
        }
    }

    private fun fadeCheck(trigger: Trigger) {
        run {


            start(trigger)
            assertTrue("Plain and quiet buttons share one fading behavior", compose.runOnIdle { anchor.quiet })
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            fun glass(): Int {
                updateFault()
                val shot = synchronizedShot(automation)
                try {
                    val b = anchor.presentedBounds; val c = Offset(b.left + b.width * .15f, b.center.y) // beside the glyph
                    val centre = android.graphics.Color.red(shot.getPixel(c.x.toInt().coerceIn(0, shot.width - 1), c.y.toInt().coerceIn(0, shot.height - 1)))
                    val background = android.graphics.Color.red(shot.getPixel(4, c.y.toInt().coerceIn(0, shot.height - 1)))
                    val qualified = region(b)
                    val values = mutableListOf<Int>()
                    for (y in kotlin.math.ceil(qualified.top).toInt() until kotlin.math.floor(qualified.bottom).toInt())
                        for (x in kotlin.math.ceil(qualified.left).toInt() until kotlin.math.floor(qualified.right).toInt()) {
                            check(x in 0 until shot.width && y in 0 until shot.height)
                            values += abs(android.graphics.Color.red(shot.getPixel(x, y)) - android.graphics.Color.red(shot.getPixel(4, y)))
                        }
                    check(values.size >= 100) { "Qualified material region must have substantial coverage" }
                    values.sort()
                    val contrast = values[values.size / 2]
                    lastBackground = background
                    sampleIndex++
                    evidence.save(shot, org.json.JSONObject().put("clock", compose.mainClock.currentTime)
                        .put("trigger", trigger.name).put("materialOnly", materialOnly).put("nativeStage", stage).put("sampleX", c.x).put("sampleY", c.y)
                        .put("centre", centre).put("background", background).put("contrast", contrast)
                        .put("oldContrast", abs(centre - background)).put("region", qualified.toString())
                        .put("regionPixels", values.size).put("mutation", mutation.name).put("sampleIndex", sampleIndex - 1)
                        .put("bounds", anchor.bounds.toString()).put("presentedBounds", b.toString())
                        .put("hidden", anchor.hidden).put("modal", anchor.modalPresent).put("inModal", anchor.shadowInModal)
                        .put("restored", anchor.restoredDrawn).put("request", anchor.restoredDrawRequest)
                        .put("receipt", anchor.restoredDrawReceipt).put("posted", anchor.restoredPresentedReceipt))
                    return contrast
                } finally { shot.recycle() }
            }
            compose.mainClock.autoAdvance = false
            try {
                val rest = glass()
                assertTrue("A plain button rests without glass: $rest", rest <= 6)
                openFrames(2); repeat(40) { frame() }
                compose.runOnIdle { open = false }
                val contrast = mutableListOf<Int>()
                var guard = 0
                while (guard++ < 600) { frame(); if (surface() == null) break; contrast += glass() }
                android.util.Log.i("Trim678", "fade $trigger contrast n=${contrast.size} ${contrast.joinToString(" ")}")
                assertTrue("The return is sampled frame by frame: $contrast", contrast.size >= 4)
                val peak = contrast.max()
                assertTrue("The return visibly contains material above the resting limit: $contrast", peak > 6)
                val peakAt = contrast.indexOf(peak)
                val tail = contrast.drop(peakAt)
                assertTrue("The glass remains at the home shape and fades out gradually, never a cliff: $contrast",
                    tail.zipWithNext().all { (a, b) -> a - b <= (peak * .6f) + 2 })
                assertTrue("Glass has mostly faded by the end of the return: $contrast", tail.last() <= peak * .6f + 2)
                compose.mainClock.advanceTimeBy(1000)
                assertTrue("Plain icon only afterwards: ${glass()}", glass() <= 6)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    private val markers = java.util.IdentityHashMap<android.view.View, FrameMarker>()
    private var stage = 0
    @org.junit.After fun removeMarkers() = compose.runOnUiThread {
        markers.forEach { (view, marker) -> view.overlay.remove(marker) }
        faults.forEach { (view, patch) -> view.overlay.remove(patch) }
    }

    private class FrameMarker(val x: Int) : android.graphics.drawable.Drawable() {
        var stage = 0
        private val paint = android.graphics.Paint()
        init { setBounds(x, 100, x + 8, 108) }
        override fun draw(canvas: android.graphics.Canvas) {
            paint.color = android.graphics.Color.rgb(stage * 8, 255, 128)
            canvas.drawRect(bounds, paint)
        }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { }
        @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }


    // Native window synchronization only. This does not identify a retained child recording.
    private fun synchronizedShot(automation: android.app.UiAutomation): android.graphics.Bitmap {
        compose.waitForIdle()
        stage = stage % 24 + 1
        val requested = stage
        lateinit var views: List<android.view.View>
        compose.runOnUiThread {
            views = listOfNotNull(rootView, popupView).distinct().filter { it.isAttachedToWindow }
            views.forEachIndexed { index, view ->
                val marker = markers.getOrPut(view) { FrameMarker(4 + index * 16).also { view.overlay.add(it) } }
                marker.stage = requested
                marker.invalidateSelf()
                view.invalidate()
            }
        }
        var presented: android.graphics.Bitmap? = null
        compose.waitUntil(5000) {
            val next = requireNotNull(automation.takeScreenshot())
            var matches = true
            compose.runOnUiThread {
                views.filter { it.isAttachedToWindow }.forEach { view ->
                    val origin = IntArray(2); view.getLocationOnScreen(origin)
                    val c = next.getPixel(origin[0] + requireNotNull(markers[view]).x + 4, origin[1] + 104)
                    val green = android.graphics.Color.green(c)
                    matches = matches && green > 80 && kotlin.math.abs(android.graphics.Color.red(c).toFloat() / green - requested * 8 / 255f) < .012f
                }
            }
            if (matches) presented = next else next.recycle()
            matches
        }
        return requireNotNull(presented)
    }
}

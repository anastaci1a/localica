package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.components.TriggerRole

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import kotlin.math.hypot

/**
 * #554 / #555 through the shared anchor and morph: a popup's home is the trigger as PRESENTED
 * (lifted when its item is selected), for expand, retract and interruption, in every trigger style;
 * a plain (glass-free) button retracts into glass, then the glass fades continuously.
 */
class PopupHomeShapeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val method = org.junit.rules.TestName()
    private val evidence = HomeNativeEvidence { method.methodName }
    private val admissionEvidence = HomeNativeEvidence { "window-admission-${method.methodName}" }

    private enum class Trigger { ListMore, PillMore, Cover }

    private lateinit var anchor: GlassAnchor
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
                                    ActionIcon(AppIcon.ActionEllipsis, "More", anchor = anchor, role = TriggerRole.PlainIcon) { open = true } }
                                Trigger.PillMore -> Box(Modifier.align(Alignment.CenterEnd)) {
                                    ActionIcon(AppIcon.ActionEllipsis, "More", anchor = anchor, glass = false) { open = true } }
                                Trigger.Cover -> Box(Modifier.align(Alignment.CenterStart).size(itemHeight)
                                    .contextMenuAnchor(anchor)) { Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFFB4D8C8)) } }
                            }
                        }
                        Spacer(Modifier.height(itemHeight))
                    }
                }
                GlassMenu(open, anchor, { open = false }) {
                    popupView = LocalView.current
                    Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("Play now"); Spacer(Modifier.height(80.dp)); Text("Add to queue") }
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
    private fun distance(a: Rect, b: Rect) = hypot(a.center.x - b.center.x, a.center.y - b.center.y) + abs(a.width - b.width) + abs(a.height - b.height)

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
    private fun closeFrames(): List<Rect> {
        compose.runOnIdle { open = false }
        val frames = mutableListOf<Rect>()
        var guard = 0
        while (guard++ < 600) { frame(); surface()?.let { frames += it } ?: break }
        return frames
    }

    private fun start(trigger: Trigger) {
        open = false; selected = false; popupView = null
        compose.setContent { Fixture(trigger) }
        compose.waitForIdle()
    }

    // #678 instrumentation (log only): every sampled rect sequence per check, to compare runs.
    private fun Rect.text() = "%.2f,%.2f,%.2f,%.2f".format(left, top, right, bottom)
    private fun log(label: String, rects: List<Rect>) = android.util.Log.i("Trim678", "$label n=${rects.size} ${rects.joinToString(" ") { it.text() }}")

    private fun check(height: Dp, liftedItem: Boolean) {
        val started = System.nanoTime()
        val id = "check h=$height lifted=$liftedItem"
        compose.runOnIdle { itemHeight = height; selected = liftedItem }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            repeat(30) { frame() } // Lift settles.
            val rest = compose.runOnIdle { anchor.bounds }
            val presented = compose.runOnIdle { anchor.presentedBounds }
            android.util.Log.i("Trim678", "$id rest=${rest.text()} presented=${presented.text()}")
            if (liftedItem) {
                assertTrue("A selected item's trigger is presented larger: $presented vs $rest", presented.width > rest.width + .5f)
                assertTrue("...and displaced from its resting position: $presented vs $rest",
                    abs(presented.left - rest.left) + abs(presented.top - rest.top) > .5f || presented.width > rest.width)
            } else assertEquals("An unselected trigger is presented where it is laid out", rest, presented)
            val first = openFrames(2)
            log("$id first", first)
            assertTrue("Expansion starts at the presented trigger, not the resting one: ${first.first()} presented=$presented rest=$rest",
                distance(first.first(), presented) <= distance(first.first(), rest) + .5f)
            repeat(40) { frame() }
            val back = closeFrames()
            log("$id back", back)
            assertTrue("The return is observed", back.size >= 3)
            val tail = back.takeLast(3)
            assertTrue("Retraction converges on the presented trigger: $tail presented=$presented",
                distance(tail.last(), presented) <= 3f || distance(tail.last(), presented) <= distance(tail.last(), rest))
            tail.zipWithNext().forEach { (a, b) -> assertTrue("Retraction approaches its home monotonically",
                distance(b, presented) <= distance(a, presented) + 1.5f) }
            assertFalse("The trigger is restored", compose.runOnIdle { anchor.hidden })
            // Interruption: reopen mid-return, then close mid-opening; homes stay the presented shape.
            compose.runOnIdle { open = true }
            repeat(40) { frame() }
            compose.runOnIdle { open = false }
            repeat(4) { frame() }
            compose.runOnIdle { open = true }
            repeat(3) { frame() }
            compose.runOnIdle { open = false }
            val interrupted = closeFrames()
            log("$id interrupted", interrupted)
            if (interrupted.isNotEmpty()) assertTrue("Interrupted return ends at the presented trigger: ${interrupted.last()} $presented",
                distance(interrupted.last(), presented) <= distance(interrupted.last(), rest) + 3f)
            // Deselecting during the return lowers the home with it.
            if (liftedItem) {
                compose.runOnIdle { open = true }
                repeat(40) { frame() }
                compose.runOnIdle { open = false; selected = false }
                val lowering = mutableListOf<Rect>()
                var guard = 0
                while (guard++ < 600) { frame(); surface()?.let { lowering += it } ?: break }
                val restFinal = compose.runOnIdle { anchor.presentedBounds }
                log("$id lowering", lowering)
                android.util.Log.i("Trim678", "$id restFinal=${restFinal.text()}")
                assertEquals("Lowered item presents at rest", compose.runOnIdle { anchor.bounds }, restFinal)
                if (lowering.isNotEmpty()) assertTrue("Return follows the lowering item: ${lowering.last()} $restFinal",
                    distance(lowering.last(), restFinal) <= 4f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            android.util.Log.i("Trim678", "$id ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    @Test fun selectedListMoreHomesOnTheLiftedButtonAtEveryListSize() {
        start(Trigger.ListMore)
        for (h in listOf(40.dp, 56.dp, 72.dp, 96.dp)) check(h, true)
    }
    @Test fun unselectedListMoreHomesOnItsButton() { start(Trigger.ListMore); check(72.dp, false) }
    @Test fun selectedGalleryCoverHomesOnTheLiftedCover() { start(Trigger.Cover); for (s in listOf(100.dp, 140.dp, 180.dp)) check(s, true) }
    @Test fun unselectedGalleryCoverHomesOnItsCover() { start(Trigger.Cover); for (s in listOf(100.dp, 140.dp, 180.dp)) check(s, false) }
    @Test fun selectedPillStyleMoreHomesOnTheLiftedButton() { start(Trigger.PillMore); check(72.dp, true) }

    @Test fun plainPillButtonRetractsIntoGlassThenTheGlassFadesContinuously() = fadeCheck(Trigger.PillMore)
    @Test fun listMoreButtonFadesItsGlassTheSameWay() = fadeCheck(Trigger.ListMore)

    // Fixed before measuring fade amplitudes, from paired foreground-ablation originals.
    private fun region(b: Rect) = Rect(b.left + b.width * .40625f, b.top + b.height * .21875f,
        b.left + b.width * .78125f, b.top + b.height * .4375f)

    private fun fadeCheck(trigger: Trigger) {
        run {


            start(trigger)
            assertTrue("Plain and quiet buttons share one fading behavior", compose.runOnIdle { anchor.quiet })
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            fun glass(): Int {
                val shot = synchronizedShot(automation)
                try {
                    val b = anchor.presentedBounds
                    if (trigger == Trigger.PillMore) {
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
                        return contrast
                    }
                    val c = Offset(b.left + b.width * .15f, b.center.y) // beside the glyph
                    val centre = android.graphics.Color.red(shot.getPixel(c.x.toInt().coerceIn(0, shot.width - 1), c.y.toInt().coerceIn(0, shot.height - 1)))
                    val background = android.graphics.Color.red(shot.getPixel(4, c.y.toInt().coerceIn(0, shot.height - 1)))
                    val contrast = abs(centre - background)
                    evidence.save(shot, org.json.JSONObject().put("clock", compose.mainClock.currentTime)
                        .put("trigger", trigger.name).put("sampleX", c.x).put("sampleY", c.y)
                        .put("centre", centre).put("background", background).put("contrast", contrast)
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
                if (trigger == Trigger.PillMore)
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
        var matchedViews = org.json.JSONArray()
        var matchedBounds = androidx.compose.ui.geometry.Rect.Zero
        compose.waitUntil(5000) {
            val next = requireNotNull(automation.takeScreenshot())
            var matches = true
            compose.runOnUiThread {
                matchedViews = org.json.JSONArray()
                matchedBounds = anchor.presentedBounds
                views.filter { it.isAttachedToWindow }.forEach { view ->
                    val origin = IntArray(2); view.getLocationOnScreen(origin)
                    val c = next.getPixel(origin[0] + requireNotNull(markers[view]).x + 4, origin[1] + 104)
                    matchedViews.put(org.json.JSONObject()
                        .put("identity", System.identityHashCode(view)).put("class", view.javaClass.name)
                        .put("attached", view.isAttachedToWindow).put("focused", view.hasWindowFocus())
                        .put("originX", origin[0]).put("originY", origin[1])
                        .put("markerX", origin[0] + requireNotNull(markers[view]).x + 4)
                        .put("markerY", origin[1] + 104).put("observedColor", c))
                    val green = android.graphics.Color.green(c)
                    matches = matches && green > 80 && kotlin.math.abs(android.graphics.Color.red(c).toFloat() / green - requested * 8 / 255f) < .012f
                }
            }
            if (matches) presented = next else next.recycle()
            matches
        }
        val bitmap = requireNotNull(presented)
        try {
            admissionEvidence.save(bitmap, org.json.JSONObject()
                .put("clock", compose.mainClock.currentTime).put("requestedStage", requested)
                .put("admittedViews", matchedViews).put("presentedBounds", matchedBounds.toString())
                .put("scope", "Accepted native-window bitmap only; CPU bounds do not attest retained-child recording or first presentation"))
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
        return bitmap
    }
}

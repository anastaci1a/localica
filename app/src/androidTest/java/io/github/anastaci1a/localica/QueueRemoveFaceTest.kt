package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Actual QueueScreen callbacks and native windows; no replacement source or confirmation fixture. */
@OptIn(ExperimentalTestApi::class)
class QueueRemoveFaceTest {
    @get:Rule val compose = localicaComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val markers = mutableListOf<Marker>()
    private var token = 0
    private data class Marker(val view: View, val drawable: ColorDrawable, val x: Int)
    private data class Sample(val x: Float, val y: Float, val value: Double)
    private data class Match(val icon: Double, val label: Double, val sx: Float, val sy: Float, val dx: Int, val dy: Int)
    private data class Pose(val sx: Float, val sy: Float, val dx: Int, val dy: Int, val correlation: Double)

    private companion object {
        /** The co-motion search: translations up to 8 px, scales .94 to 1.06 in .02 steps. Agreement compares the residual
         * TRANSLATION within one pixel step; residual scale is not identifiable on a 24 dp glyph and one text line at this resolution
         * (the label reads .94-.96 even at rest), so it is searched and recorded, neither compared nor boundary-checked. */
        const val PoseReach = 8
        val PoseScales = listOf(.94f, .96f, .98f, 1f, 1.02f, 1.04f, 1.06f)
        /** Each glyph's own best pose must correlate at least as well as the retained-face floor. */
        const val PoseFloor = .65
    }
    @Test fun cancelKeepsTheTrashAndLabelTogetherThroughTheNativeReturn() = verifyReturn(false)
    @Test fun systemBackKeepsTheTrashAndLabelTogetherThroughTheNativeReturn() = verifyReturn(true)

    private fun view(node: androidx.compose.ui.semantics.SemanticsNode) = (node.root as ViewRootForTest).view
    private fun window(view: View): Window = (view.parent as DialogWindowProvider).window
    private fun surface(): androidx.compose.ui.semantics.SemanticsNode? =
        compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes()
            .minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
    private fun screenBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
        val origin = IntArray(2)
        compose.runOnUiThread { view(node).getLocationOnScreen(origin) }
        return node.boundsInRoot.translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
    }
    private fun marker(view: View) {
        compose.runOnUiThread {
            val root = view.rootView
            val drawable = ColorDrawable()
            root.overlay.add(drawable)
            markers += Marker(root, drawable, 24 + markers.size * 32)
        }
    }
    /** Wait for unrelated native draw tokens, never for the ink/result under test. */
    private fun screenshot(): Bitmap {
        token = token % 30 + 1
        val expected = token * 8
        data class Probe(val x: Int, val y: Int, val topmost: Boolean)
        val probes = mutableListOf<Probe>()
        compose.runOnUiThread {
            val attached = markers.filter { it.view.isAttachedToWindow }
            attached.forEachIndexed { index, marker ->
                val origin = IntArray(2).also(marker.view::getLocationOnScreen)
                val visible = android.graphics.Rect().also(marker.view::getWindowVisibleDisplayFrame)
                val x = max(origin[0], visible.left) + marker.x
                val y = max(origin[1], visible.top) + 96
                marker.drawable.color = AndroidColor.rgb(expected, 255, 0)
                marker.drawable.setBounds(x - origin[0] - 4, y - origin[1] - 4, x - origin[0] + 4, y - origin[1] + 4)
                marker.drawable.invalidateSelf(); marker.view.invalidate()
                probes += Probe(x, y, index == attached.lastIndex)
            }
        }
        var image: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                image?.recycle()
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                image = next
                probes.all { probe ->
                    val color = next.getPixel(probe.x, probe.y)
                    val green = AndroidColor.green(color)
                    green >= 80 && abs(AndroidColor.red(color) * 255f / green - expected) <= 4f &&
                        (!probe.topmost || green >= 250)
                }
            }
            return requireNotNull(image)
        } catch (failure: Throwable) { image?.recycle(); throw failure }
    }
    private fun save(image: Bitmap, file: File) {
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun gray(image: Bitmap, x: Int, y: Int): Double {
        val pixel = image.getPixel(x, y)
        return (54 * AndroidColor.red(pixel) + 183 * AndroidColor.green(pixel) + 19 * AndroidColor.blue(pixel)) / 256.0
    }
    private fun template(image: Bitmap, home: Rect, start: Float, end: Float, radius: Float): List<Sample> = buildList {
        for (y in ceil(home.center.y - radius).toInt() until floor(home.center.y + radius).toInt() step 2)
            for (x in ceil(home.left + start).toInt() until floor(home.left + end).toInt() step 2)
                add(Sample(x + .5f - home.center.x, y + .5f - home.center.y, gray(image, x, y)))
    }
    private fun correlation(image: Bitmap, samples: List<Sample>, center: Offset, sx: Float, sy: Float, dx: Int, dy: Int): Double {
        var a = 0.0; var b = 0.0; var aa = 0.0; var bb = 0.0; var ab = 0.0
        samples.forEach { sample ->
            val x = floor(center.x + sample.x * sx + dx).toInt()
            val y = floor(center.y + sample.y * sy + dy).toInt()
            val actual = gray(image, x, y)
            a += sample.value; b += actual; aa += sample.value * sample.value; bb += actual * actual; ab += sample.value * actual
        }
        val n = samples.size.toDouble()
        return (n * ab - a * b) / sqrt((n * aa - a * a) * (n * bb - b * b)).coerceAtLeast(1.0)
    }
    /** One glyph's own best RESIDUAL transform around the drawn body ([bodyX], [bodyY] about the panel center, as
     * QueueSelectorPersistenceTest.currentBodyRect draws it): every translation within [PoseReach] px at unit residual scale, then
     * the residual scales in [PoseScales] with one pixel around that translation. The same correlation as [correlation], read from
     * one pixel copy of the frame. */
    private fun pose(image: Bitmap, samples: List<Sample>, center: Offset, bodyX: Float, bodyY: Float): Pose {
        val w = image.width; val h = image.height
        val pixels = IntArray(w * h).also { image.getPixels(it, 0, w, 0, 0, w, h) }
        fun gray(x: Int, y: Int): Double { val pixel = pixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            return (54 * AndroidColor.red(pixel) + 183 * AndroidColor.green(pixel) + 19 * AndroidColor.blue(pixel)) / 256.0 }
        fun score(sx: Float, sy: Float, dx: Int, dy: Int): Double {
            var a = 0.0; var b = 0.0; var aa = 0.0; var bb = 0.0; var ab = 0.0
            samples.forEach { sample ->
                val actual = gray(floor(center.x + sample.x * sx * bodyX + dx).toInt(), floor(center.y + sample.y * sy * bodyY + dy).toInt())
                a += sample.value; b += actual; aa += sample.value * sample.value; bb += actual * actual; ab += sample.value * actual
            }
            val n = samples.size.toDouble()
            return (n * ab - a * b) / sqrt((n * aa - a * a) * (n * bb - b * b)).coerceAtLeast(1.0)
        }
        var best = Pose(1f, 1f, 0, 0, -1.0)
        for (dx in -PoseReach..PoseReach) for (dy in -PoseReach..PoseReach) score(1f, 1f, dx, dy).let { if (it > best.correlation) best = Pose(1f, 1f, dx, dy, it) }
        val (tx, ty) = best.dx to best.dy
        for (sx in PoseScales) for (sy in PoseScales) for (dx in tx - 1..tx + 1) for (dy in ty - 1..ty + 1)
            score(sx, sy, dx, dy).let { if (it > best.correlation) best = Pose(sx, sy, dx, dy, it) }
        return best
    }
    /** A pose at the edge of the translation search, or whose best correlation over the scale search is below the floor, measured
     * nothing: it cannot witness co-motion. Residual scale is recorded only (see [PoseScales]). */
    private fun Pose.measured() = abs(dx) < PoseReach && abs(dy) < PoseReach && correlation >= PoseFloor
    /** Whether the panel has re-seated on its home row as closely as v0.34.0's quiet window had at p = .16: every moving edge is
     * within that geometry fraction of its travel, plus one pixel of layout rounding. Measured from the panel itself, so it holds
     * for any popup law (#854 §1.5 gives Dialogs linear geometry; the old phase window assumed the .14 hold). */
    private fun seated(panel: Rect, home: Rect, expanded: Rect): Boolean {
        val fraction = io.github.anastaci1a.localica.ui.engine.Curve.window(.14f, 1f).at(.16f)
        return listOf(Triple(panel.left, home.left, expanded.left), Triple(panel.top, home.top, expanded.top),
            Triple(panel.right, home.right, expanded.right), Triple(panel.bottom, home.bottom, expanded.bottom))
            .all { (now, rest, open) -> abs(now - rest) <= abs(open - rest) * fraction + 1f }
    }
    /** One common, tightly bounded raster transform for BOTH icon and text, not separate realignment; a residual around the drawn
     * body ([bodyX], [bodyY] about the panel center). */
    private fun match(image: Bitmap, icon: List<Sample>, label: List<Sample>, center: Offset, bodyX: Float, bodyY: Float): Match {
        var best = Match(-1.0, -1.0, 1f, 1f, 0, 0)
        for (sx in listOf(.96f, .98f, 1f, 1.02f, 1.04f)) for (sy in listOf(.96f, .98f, 1f, 1.02f, 1.04f))
            for (dx in -1..1) for (dy in -1..1) {
                val i = correlation(image, icon, center, sx * bodyX, sy * bodyY, dx, dy)
                val l = correlation(image, label, center, sx * bodyX, sy * bodyY, dx, dy)
                if (min(i, l) > min(best.icon, best.label)) best = Match(i, l, sx, sy, dx, dy)
            }
        return best
    }
    private fun contactSheet(files: List<File>, crop: Rect, output: File) {
        val width = 320
        val height = (width * crop.height / crop.width).roundToInt()
        val sheet = Bitmap.createBitmap(width * 4, (height + 22) * ceil(files.size / 4.0).toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet); canvas.drawColor(AndroidColor.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.BLACK; textSize = 14f }
            files.forEachIndexed { index, file ->
                val image = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                try {
                    val left = index % 4 * width; val top = index / 4 * (height + 22)
                    canvas.drawText(file.nameWithoutExtension, left + 4f, top + 16f, paint)
                    canvas.drawBitmap(image, android.graphics.Rect(crop.left.toInt().coerceAtLeast(0), crop.top.toInt().coerceAtLeast(0),
                        crop.right.toInt().coerceAtMost(image.width), crop.bottom.toInt().coerceAtMost(image.height)),
                        android.graphics.Rect(left, top + 22, left + width, top + 22 + height), null)
                } finally { image.recycle() }
            }
            save(sheet, output)
        } finally { sheet.recycle() }
    }
    private fun verifyReturn(systemBack: Boolean) {
        val mode = if (systemBack) "system-back" else "cancel"
        val output = File(nativeEvidenceDirectory("queue-remove-face-692", instrumentation.targetContext.getExternalFilesDir(null)), "api${Build.VERSION.SDK_INT}-$mode").apply { mkdirs() }
        val files = mutableListOf<File>()
        val frames = JSONArray()
        val checks = mutableListOf<Match>()
        val apart = mutableListOf<String>()
        var poses = 0
        val queue = ListeningQueue(name = "Current queue", entries = emptyList())
        val other = ListeningQueue(name = "Other queue", entries = emptyList())
        val state = QueueUiState(QueueLibrary(listOf(queue, other), queue.id), loading = false)
        lateinit var host: GlassDialogs
        var failure: Throwable? = null
        var crop = Rect.Zero
        var referenceBounds = Rect.Zero
        var homeBounds = Rect.Zero
        var sourceRoot: View? = null
        var sourceWindowDetached = false
        try {
            compose.setContent { LocalicaTheme {
                val owner = requireNotNull(LocalGlassDialogs.current)
                SideEffect { host = owner }
                QueueScreen(state, null)
            } }
            compose.onNodeWithContentDescription("Select queue").performClick()
            val source = compose.onNodeWithText("Remove all other queues")
            source.assertIsDisplayed()
            // The source popup can still be opening after semantics become visible.
            // Finish that setup flight before deriving pixels/coordinates for the
            // return oracle; keep the measured return itself fully frame-stepped.
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(1600)
            compose.waitForIdle()
            referenceBounds = screenBounds(source.fetchSemanticsNode())
            repeat(2) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                assertEquals("Source reference geometry is settled before capture", referenceBounds,
                    screenBounds(source.fetchSemanticsNode()))
            }
            val sourceView = view(source.fetchSemanticsNode())
            sourceRoot = sourceView.rootView
            val sourceWindow = compose.runOnUiThread { window(sourceView) }
            val sourceToken = compose.runOnUiThread { sourceView.windowToken }
            marker(sourceView)
            val referenceFile = File(output, "00-queue-source.png").also { files += it }
            screenshot().let { image -> try { save(image, referenceFile) } finally { image.recycle() } }
            compose.mainClock.autoAdvance = true
            source.performClick()
            compose.onNodeWithText("Delete queues?").assertIsDisplayed()
            val entry = compose.runOnIdle { host.entries.single { it.mounted } }
            val anchor = entry.anchor
            val home = compose.runOnIdle { anchor.presentedBounds }
            homeBounds = home
            val childView = view(compose.onNodeWithText("Delete queues?").fetchSemanticsNode())
            val childWindow = compose.runOnUiThread { window(childView) }
            assertNotSame("Confirmation uses its ordinary separate native window", sourceWindow, childWindow)
            marker(childView)
            val expanded = screenBounds(requireNotNull(surface()))
            crop = Rect(min(home.left, expanded.left), min(home.top, expanded.top), max(home.right, expanded.right), max(home.bottom, expanded.bottom)).inflate(12f)
            val d = compose.density.density
            val baseline = requireNotNull(BitmapFactory.decodeFile(referenceFile.absolutePath))
            val icon: List<Sample>; val label: List<Sample>
            try {
                icon = template(baseline, home, 18 * d, 42 * d, 12 * d)
                label = template(baseline, home, 54 * d, home.width - 18 * d, 10 * d)
                assertTrue("Reference contains the real trash glyph", correlation(baseline, icon, home.center, 1f, 1f, 0, 0) > .99)
                assertTrue("Reference contains the real label", correlation(baseline, label, home.center, 1f, 1f, 0, 0) > .99)
            } finally { baseline.recycle() }
            val expandedFile = File(output, "01-delete-queues.png").also { files += it }
            screenshot().let { image -> try { save(image, expandedFile) } finally { image.recycle() } }
            compose.mainClock.autoAdvance = false
            val start = compose.mainClock.currentTime
            if (systemBack) pressSystemBack() else compose.onNodeWithText("Cancel").performClick()
            var returned = false
            var retainedSamples = 0
            var frame = 0
            while (compose.mainClock.currentTime - start <= 5000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val p = compose.runOnUiThread { anchor.contact.expansion.value }
                // The drawn body: the retained face is drawn inside the deformed glass body, scaled about the panel center.
                val (bodyX, bodyY) = compose.runOnUiThread { anchor.contact.body.value.let { it.scaleX(anchor.contact.vertical) to it.scaleY(anchor.contact.vertical) } }
                val hidden = compose.runOnUiThread { anchor.hidden }
                val present = compose.runOnUiThread { anchor.modalPresent }
                val retired = compose.runOnUiThread { entry !in host.entries }
                val node = surface()
                val panel = if (present && node != null) screenBounds(node) else home
                val native = node?.let { compose.runOnUiThread { window(view(it)) } }
                assertTrue("Return retains the original source/confirmation windows", native == null || native === sourceWindow || native === childWindow)
                val capture = p < .55f || frame % 4 == 0 || !present
                if (capture) {
                    val image = screenshot()
                    try {
                        val file = File(output, "return-${frame.toString().padStart(3, '0')}.png").also { files += it }
                        save(image, file)
                        val record = JSONObject().put("frame", frame).put("clockMillis", compose.mainClock.currentTime - start)
                            .put("progress", p).put("hidden", hidden).put("modalPresent", present).put("entryRetired", retired)
                            .put("windowIdentity", native?.let(System::identityHashCode)).put("file", file.name)
                        // Co-motion on every late return frame, wherever the panel is: the trash and the label each find their own
                        // best residual transform, and their translations agree within one pixel step (#692's named invariant).
                        if (p <= .16f) {
                            val i = pose(image, icon, panel.center, bodyX, bodyY); val l = pose(image, label, panel.center, bodyX, bodyY); poses++
                            record.put("bodyScale", JSONArray(listOf(bodyX, bodyY)))
                            record.put("iconPose", JSONArray(listOf(i.sx, i.sy, i.dx, i.dy, i.correlation)))
                                .put("labelPose", JSONArray(listOf(l.sx, l.sy, l.dx, l.dy, l.correlation))).put("poseFloor", PoseFloor)
                                .put("poseReach", PoseReach).put("poseMeasured", i.measured() && l.measured())
                            if (!i.measured() || !l.measured() ||
                                abs(i.dx - l.dx) > 1 || abs(i.dy - l.dy) > 1)
                                apart += "frame $frame p=$p icon=$i label=$l"
                        }
                        if (p <= .16f && seated(panel, home, expanded)) {
                            val result = match(image, icon, label, panel.center, bodyX, bodyY)
                            checks += result
                            if (hidden) retainedSamples++
                            record.put("iconCorrelation", result.icon).put("labelCorrelation", result.label)
                                .put("scaleX", result.sx).put("scaleY", result.sy).put("dx", result.dx).put("dy", result.dy)
                        }
                        frames.put(record)
                    } finally { image.recycle() }
                }
                frame++
                if (!hidden && !present && retired) { returned = true; break }
            }
            assertTrue("The same source returns within the existing five-second bound", returned)
            assertTrue("Observe actual retained-face pixels before the native handoff", retainedSamples >= 2)
            assertTrue("Retained and live phases were both sampled", checks.size > retainedSamples)
            assertTrue("Trash geometry stays with the label throughout return: $checks", checks.all { it.icon >= .65 && it.label >= .65 })
            assertTrue("Trash and label move together on every return frame with p <= .16", poses >= 2)
            assertTrue("Trash and label poses agree within one search step: $apart", apart.isEmpty())
            assertTrue("The conditional confirmation retires normally", compose.runOnIdle { host.entries.isEmpty() })
            assertEquals(home, compose.runOnIdle { anchor.presentedBounds })
            val restored = view(source.fetchSemanticsNode())
            assertSame("Source native root does not remount", sourceView, restored)
            assertSame("Source window does not change", sourceWindow, compose.runOnUiThread { window(restored) })
            assertSame("Source native token does not change", sourceToken, compose.runOnUiThread { restored.windowToken })
            source.assertIsDisplayed()
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            compose.mainClock.autoAdvance = true
            var cleanupFailure: Throwable? = null
            try {
                // Each method leaves the outer Queues selector open. Retire its
                // actual native window before ActivityScenario starts the next
                // method; rule disposal alone does not acknowledge that boundary.
                sourceRoot?.let { root ->
                    if (compose.runOnUiThread { root.isAttachedToWindow }) {
                        pressSystemBack()
                        compose.mainClock.advanceTimeBy(1600)
                        compose.waitForIdle()
                        compose.waitUntil(5000) { !root.isAttachedToWindow }
                    }
                    sourceWindowDetached = !compose.runOnUiThread { root.isAttachedToWindow }
                    assertTrue("Outer selector native window detaches between methods", sourceWindowDetached)
                }
            } catch (error: Throwable) {
                if (failure != null) failure.addSuppressed(error)
                else { failure = error; cleanupFailure = error }
            }
            compose.runOnUiThread { markers.forEach { it.view.overlay.remove(it.drawable) }; markers.clear() }
            File(output, "receipt.json").writeText(JSONObject().put("route", "QueueScreen > Queues > Remove all other queues > Delete queues? > $mode")
                .put("api", Build.VERSION.SDK_INT).put("status", if (failure == null) "passed" else "failed")
                .put("failure", failure?.stackTraceToString()).put("capture", "Compositor screenshots gated only by unrelated native draw markers; paused Compose frames, not a wall-time/performance claim")
                .put("sourceWindowDetached", sourceWindowDetached)
                .put("referenceSetup", "Opening advanced 1600ms on Compose clock; source bounds invariant across two more frames; clock paused during reference compositor capture")
                .put("referenceBounds", JSONArray(listOf(referenceBounds.left, referenceBounds.top, referenceBounds.right, referenceBounds.bottom)))
                .put("homeBounds", JSONArray(listOf(homeBounds.left, homeBounds.top, homeBounds.right, homeBounds.bottom)))
                .put("frames", frames).toString(2))
            if (files.isNotEmpty() && crop != Rect.Zero) contactSheet(files, crop, File(output, "contact.png"))
            cleanupFailure?.let { throw it }
        }
    }
}

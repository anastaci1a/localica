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
    /** One common, tightly bounded raster transform for BOTH icon and text, not separate realignment. */
    private fun match(image: Bitmap, icon: List<Sample>, label: List<Sample>, center: Offset): Match {
        var best = Match(-1.0, -1.0, 1f, 1f, 0, 0)
        for (sx in listOf(.96f, .98f, 1f, 1.02f, 1.04f)) for (sy in listOf(.96f, .98f, 1f, 1.02f, 1.04f))
            for (dx in -1..1) for (dy in -1..1) {
                val i = correlation(image, icon, center, sx, sy, dx, dy)
                val l = correlation(image, label, center, sx, sy, dx, dy)
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
        val queue = ListeningQueue(name = "Current queue", entries = emptyList())
        val other = ListeningQueue(name = "Other queue", entries = emptyList())
        val state = QueueUiState(QueueLibrary(listOf(queue, other), queue.id), loading = false)
        lateinit var host: GlassDialogs
        var failure: Throwable? = null
        var crop = Rect.Zero
        try {
            compose.setContent { LocalicaTheme {
                val owner = requireNotNull(LocalGlassDialogs.current)
                SideEffect { host = owner }
                QueueScreen(state, null)
            } }
            compose.onNodeWithContentDescription("Select queue").performClick()
            val source = compose.onNodeWithText("Remove all other queues")
            source.assertIsDisplayed()
            val sourceView = view(source.fetchSemanticsNode())
            val sourceWindow = compose.runOnUiThread { window(sourceView) }
            val sourceToken = compose.runOnUiThread { sourceView.windowToken }
            marker(sourceView)
            val referenceFile = File(output, "00-queue-source.png").also { files += it }
            screenshot().let { image -> try { save(image, referenceFile) } finally { image.recycle() } }
            source.performClick()
            compose.onNodeWithText("Delete queues?").assertIsDisplayed()
            val entry = compose.runOnIdle { host.entries.single { it.mounted } }
            val anchor = entry.anchor
            val home = compose.runOnIdle { anchor.presentedBounds }
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
                val hidden = compose.runOnUiThread { anchor.hidden }
                val present = compose.runOnUiThread { anchor.modalPresent }
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
                            .put("progress", p).put("hidden", hidden).put("modalPresent", present)
                            .put("windowIdentity", native?.let(System::identityHashCode)).put("file", file.name)
                        if (p <= .16f) {
                            val result = match(image, icon, label, panel.center)
                            checks += result
                            if (hidden) retainedSamples++
                            record.put("iconCorrelation", result.icon).put("labelCorrelation", result.label)
                                .put("scaleX", result.sx).put("scaleY", result.sy).put("dx", result.dx).put("dy", result.dy)
                        }
                        frames.put(record)
                    } finally { image.recycle() }
                }
                frame++
                if (!hidden && !present) { returned = true; break }
            }
            assertTrue("The same source returns within the existing five-second bound", returned)
            assertTrue("Observe actual retained-face pixels before the native handoff", retainedSamples >= 2)
            assertTrue("Retained and live phases were both sampled", checks.size > retainedSamples)
            assertTrue("Trash geometry stays with the label throughout return: $checks", checks.all { it.icon >= .65 && it.label >= .65 })
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
            compose.runOnUiThread { markers.forEach { it.view.overlay.remove(it.drawable) }; markers.clear() }
            File(output, "receipt.json").writeText(JSONObject().put("route", "QueueScreen > Queues > Remove all other queues > Delete queues? > $mode")
                .put("api", Build.VERSION.SDK_INT).put("status", if (failure == null) "passed" else "failed")
                .put("failure", failure?.stackTraceToString()).put("capture", "Compositor screenshots gated only by unrelated native draw markers; paused Compose frames, not a wall-time/performance claim")
                .put("frames", frames).toString(2))
            if (files.isNotEmpty() && crop != Rect.Zero) contactSheet(files, crop, File(output, "contact.png"))
        }
    }
}

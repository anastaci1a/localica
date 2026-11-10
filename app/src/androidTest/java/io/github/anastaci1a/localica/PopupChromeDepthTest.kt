package io.github.anastaci1a.localica

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** #667: the actual overflow route keeps its returning pixels below both pieces of bottom chrome. */
class PopupChromeDepthTest {
    @get:Rule val compose = localicaComposeRule()
    private val markers = IdentityHashMap<View, Marker>()
    private val channels: List<(Int)->Int> = listOf({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
    private var stage = 0

    @org.junit.After fun removeMarkers() = compose.runOnUiThread {
        markers.forEach { (view, marker) -> view.overlay.remove(marker) }
    }

    @Test fun overflowSettingsRetractsBehindMiniPlayerAndTabs() { overflow("Settings") }
    @Test fun overflowScanRetractsBehindMiniPlayerAndTabs() { overflow("Scan music") }

    private fun overflow(destination: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            destination.replace(' ', '-')).apply { mkdirs() }
        val samples = JSONArray()
        val report = JSONObject().put("sourceBinding", "prototype-evidence/context.txt + test-input-apks.sha256").put("destination", destination)
            .put("samples", samples).put("status", "preparing").put("captureOrder", "popup-before-activity")
            .put("comparison", "Original activity and overflow buffers, SrcOver in source space; destination excluded; neutral black scrim only")
        fun save() { File(output, "frames.json").writeText(report.toString(2)) }
        var baseline: Bitmap? = null
        try {
            preferences.write(InterfaceSettings(reducedMotion = false, fasterMotion = false))
            val track = Track("file:///missing-667.mp3", "One", artist = "Test", album = "One",
                fileName = "missing-667.mp3", relativePath = "missing-667.mp3")
            lateinit var host: View
            lateinit var home: GlassPopupHome
            var popup: Window? = null
            var progress = 0f
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                home = requireNotNull(LocalGlassPopupHome.current)
                CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, p ->
                    if (kind == "dialog" && window != null && popup == null) popup = window
                    if (kind == "geometry" && window === popup && p != null) progress = p
                }) {
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                        PlaybackState(connected = true, trackId = track.id, trackUri = track.id,
                            title = track.title, artist = track.artist, durationMs = 125000),
                        {}, {}, {}, {}, {}, {}, playlist = PlaylistState(loading = false))
                }
            } }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            val origin = IntArray(2).also { compose.runOnUiThread { host.getLocationInWindow(it) } }
            val regions = listOf("mini-player" to "player-surface", "tabs" to "tab-pill").map { (name, tag) ->
                name to compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot.translate(androidx.compose.ui.geometry.Offset(origin[0].toFloat(), origin[1].toFloat()))
            }
            report.put("chrome", JSONArray(regions.map { (name, r) -> JSONObject().put("name", name).put("bounds", r.toString()) }))
            compose.mainClock.autoAdvance = false
            baseline = original(activity, output, "baseline")
            compose.onNodeWithContentDescription("More").performClick()
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                popup?.decorView?.isAttachedToWindow == true && progress >= .999f &&
                    compose.onAllNodesWithText(destination, substring = false).fetchSemanticsNodes().size == 1
            }
            val window = requireNotNull(popup)
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            compose.waitUntil(5000) { window.decorView.hasWindowFocus() }
            val flight = compose.runOnUiThread {
                fun flights(h: GlassPopupHome): List<GlassPopupFlight> = h.flights.toList() + h.children.flatMap(::flights)
                flights(home).single()
            }
            compose.onNodeWithText(destination, substring = false).performClick()
            val returnStartClockMs = compose.mainClock.currentTime
            report.put("returnStartClockMs", returnStartClockMs).put("density", host.resources.displayMetrics.density)
            var valid = 0
            var highest = 0f
            var lowest = 1f
            val violations = mutableListOf<String>()
            val inset = 4f * host.resources.displayMetrics.density
            for (frame in 0 until 96) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                if (!window.decorView.isAttachedToWindow) break
                val p = progress.coerceIn(0f, 1f)
                if (p !in .04f.. .9f) continue
                val before = compose.runOnUiThread { flight.handedOff }
                val sample = JSONObject().put("frame", frame).put("clockMs", compose.mainClock.currentTime)
                    .put("phase", p).put("projectedBefore", before)
                    .put("returnClockMs", compose.mainClock.currentTime - returnStartClockMs)
                samples.put(sample); save()
                // Update the native flight recording before its Activity projection.
                val modal = original(window, output, "frame-$frame-popup")
                try {
                    val root = original(activity, output, "frame-$frame-root")
                    try {
                        val after = compose.runOnUiThread { flight.handedOff }
                        sample.put("projectedAfter", after)
                        // A pair spanning the one native→scene ownership event is not one compositor frame.
                        // Preserve both originals and classify by the routing receipt, never by optical pixels.
                        if (before != after) { sample.put("status", "mixed-routing-epoch"); save(); continue }
                        val rootAt = IntArray(2); val modalAt = IntArray(2)
                        var nativeAlpha = 1f
                        compose.runOnUiThread {
                            activity.decorView.getLocationOnScreen(rootAt); window.decorView.getLocationOnScreen(modalAt)
                            nativeAlpha = window.attributes.alpha
                        }
                        sample.put("nativeWindowAlpha", nativeAlpha)
                            .put("rootOriginOnScreen", JSONArray(rootAt.toList()))
                            .put("popupOriginOnScreen", JSONArray(modalAt.toList()))
                            .put("rootDecorSize", JSONArray(listOf(activity.decorView.width, activity.decorView.height)))
                            .put("popupDecorSize", JSONArray(listOf(window.decorView.width, window.decorView.height)))
                        val counts = JSONArray()
                        regions.forEach { (name, bounds) ->
                            var count = 0; var bad = 0
                            for (y in bounds.top.toInt() until bounds.bottom.toInt() step 2)
                                for (x in bounds.left.toInt() until bounds.right.toInt() step 2) {
                                    if (!insideCapsule(x.toFloat(), y.toFloat(), bounds, inset)) continue
                                    val b = requireNotNull(baseline).getPixel(x, y)
                                    val r = root.getPixel(x, y)
                                    val mx = x + rootAt[0] - modalAt[0]; val my = y + rootAt[1] - modalAt[1]
                                    val m = if (mx in 0 until modal.width && my in 0 until modal.height) modal.getPixel(mx, my) else Color.TRANSPARENT
                                    val a = Color.alpha(m) / 255f * nativeAlpha
                                    val norm = channels.sumOf { (it(b) * it(b)).toDouble() }.toFloat()
                                    val dot = channels.sumOf { (it(b) * (it(m) * a + it(r) * (1f - a))).toDouble() }.toFloat()
                                    val gain = (if (norm == 0f) 1f else dot / norm).coerceIn(1f - .16f * p, 1f)
                                    val error = channels.maxOf { channel ->
                                        abs(channel(m) * a + channel(r) * (1f - a) - channel(b) * gain) / 255f
                                    }
                                    // Permit only neutral black-scrim attenuation, in either source space; never substituted chrome ink.
                                    if (error > .055f) bad++
                                    count++
                                }
                            counts.put(JSONObject().put("region", name).put("pixels", count).put("changed", bad))
                            assertTrue("A real interior chrome region is sampled: $name=$bounds", count > 100)
                            if (bad > max(4, count / 1000)) violations += "frame=$frame p=$p $name changed=$bad/$count projected=$after"
                        }
                        sample.put("regions", counts).put("status", "sampled")
                        valid++; highest = maxOf(highest, p); lowest = minOf(lowest, p)
                    } finally { root.recycle() }
                } finally { modal.recycle() }
                save()
            }
            report.put("validFrames", valid).put("highestPhase", highest).put("lowestPhase", lowest)
                .put("violations", JSONArray(violations)).put("status", if (violations.isEmpty()) "sampled" else "failed-depth")
            save()
            assertTrue("#667 needs actual intermediate-frame coverage: $report", valid >= 3 && highest >= .6f && lowest <= .2f)
            assertTrue("#667 returning $destination pixels crossed bottom chrome: $violations", violations.isEmpty())
            assertFalse("The overflow's original native window retires after return", window.decorView.isAttachedToWindow)
            report.put("status", "passed"); save()
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString()); save(); throw failure
        } finally {
            baseline?.recycle(); compose.mainClock.autoAdvance = true; preferences.write(previous)
        }
    }

    private fun insideCapsule(x: Float, y: Float, r: Rect, inset: Float): Boolean {
        val radius = r.height / 2f
        val dx = maxOf(r.left + radius - x, 0f, x - (r.right - radius))
        return dx * dx + (y - r.center.y) * (y - r.center.y) < (radius - inset) * (radius - inset)
    }

    /** One original Window copy after a native draw receipt, with an unrelated opaque frame token. No copy retry. */
    private fun original(window: Window, output: File, name: String): Bitmap {
        val view = window.decorView
        val ready = CountDownLatch(1)
        val token = stage % 24 + 1; stage = token
        lateinit var tree: ViewTreeObserver
        var draws = 0
        val committed = Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated
        val watch = ViewTreeObserver.OnDrawListener {
            if (!committed) {
                draws++
                if (draws == 1) view.postOnAnimation { view.invalidate() }
                else view.post { ready.countDown() }
            }
        }
        compose.runOnUiThread {
            tree = view.viewTreeObserver
            tree.addOnDrawListener(watch)
            if (Build.VERSION.SDK_INT >= 29 && committed) tree.registerFrameCommitCallback { ready.countDown() }
            val marker = markers.getOrPut(view) { Marker().also { view.overlay.add(it) } }
            marker.stage = token; marker.invalidateSelf()
            view.invalidate()
        }
        try { assertTrue("Native draw receipt for $name", ready.await(2, TimeUnit.SECONDS)) }
        finally { compose.runOnUiThread { if (tree.isAlive) tree.removeOnDrawListener(watch) } }
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // API 26 passes Window crop coordinates directly to the padded Surface.
        // API 28+ adds these insets inside PixelCopy; compensate only on the old path.
        val crop = android.graphics.Rect(0, 0, view.width, view.height)
        val insets = if (Build.VERSION.SDK_INT < 28) compose.runOnUiThread {
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(view)
            val attributes = root.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }.get(root)
            android.graphics.Rect(android.view.WindowManager.LayoutParams::class.java
                .getField("surfaceInsets").get(attributes) as android.graphics.Rect)
        } else android.graphics.Rect()
        crop.offset(insets.left, insets.top)
        File(output, "$name-copy.json").writeText(JSONObject().put("api", Build.VERSION.SDK_INT)
            .put("crop", crop.toShortString()).put("surfaceInsets", insets.toShortString()).toString(2))
        val result = copyNativeWindowOnce(window, image, "#667 original $name copy timed out",
            sourceBounds = crop,
            failureMessage = { "#667 original $name copy failed: $it; no retry" })
        try {
            File(output, "$name.png").outputStream().use { assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val marker = result.getPixel(8, 104)
            assertTrue("Original $name carries requested frame token $token, actual RGBA=${Color.red(marker)},${Color.green(marker)},${Color.blue(marker)},${Color.alpha(marker)} crop=$crop",
                abs(Color.red(marker) - token * 8) <= 1 && Color.green(marker) >= 254 && abs(Color.blue(marker) - 128) <= 1)
            return result
        } catch (failure: Throwable) { result.recycle(); throw failure }
    }

    private class Marker : Drawable() {
        var stage = 0
        private val paint = Paint()
        init { setBounds(4, 100, 12, 108) }
        override fun draw(canvas: Canvas) { paint.color = Color.rgb(stage * 8, 255, 128); canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { }
        @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

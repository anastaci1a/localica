package io.github.anastaci1a.localica

import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.TriggerRole
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** #847: a popup returning into its source pill or round button ends as that source: one optical owner per frame (#748 R5),
 * the returning body's last record is the source's own record, and the displayed pixels do not step at the hand-off. */
class PopupRetractTintTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    @org.junit.After fun removeMarkers() = frames.dispose()
    // Page-heading triggers (the library Sort/View pill, Back) have no sampled source: only the shell's modal recording.
    @Test fun pillRetractHandsOverWithoutStep() = retract(pill = true, sourced = false)
    @Test fun roundButtonRetractHandsOverWithoutStep() = retract(pill = false, sourced = false)
    @Test fun sourcedPillRetractHandsOverWithoutStep() = retract(pill = true, sourced = true)
    @Test fun sourcedRoundButtonRetractHandsOverWithoutStep() = retract(pill = false, sourced = true)
    @Test fun flatPillRetractHandsOverWithoutStep() = retract(pill = true, sourced = false, "fixed-default-control")
    @Test fun flatRoundButtonRetractHandsOverWithoutStep() = retract(pill = false, sourced = false, "fixed-default-control")
    @Test fun flatSourcedPillRetractHandsOverWithoutStep() = retract(pill = true, sourced = true, "fixed-default-control")
    @Test fun flatSourcedRoundButtonRetractHandsOverWithoutStep() = retract(pill = false, sourced = true, "fixed-default-control")

    private fun retract(pill: Boolean, sourced: Boolean, case: String = "default") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-retract-tint", context.getExternalFilesDir(null)),
            (if (pill) "pill" else "round") + (if (sourced) "-sourced" else "") + "-" + case).apply { mkdirs() }
        val rows = JSONArray()
        val report = JSONObject().put("source", BuildConfig.SOURCE_SHA).put("api", android.os.Build.VERSION.SDK_INT).put("frames", rows)
        fun save() = File(output, "frames.json").writeText(report.toString(2))
        var popup: Window? = null
        var progress = 0f
        lateinit var host: View
        lateinit var anchor: GlassAnchor
        var open by mutableStateOf(false)
        val receipts = mutableListOf<GlassDrawReceipt>()
        glassDrawProbe = { receipt -> if (receipt is GlassSurfaceDrawObservation || receipt is GlassBodyReceipt) synchronized(receipts) { receipts += receipt } }
        try {
            preferences.write(InterfaceSettings(reducedMotion = false, animationSpeedTenths = 10))
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                val home = rememberGlassPopupHome(false)
                val page = rememberFrostedBackdrop(home, dark = false)
                anchor = rememberGlassAnchor()
                CompositionLocalProvider(LocalFrostedBackdrop provides page.takeIf { sourced }, LocalModalBackdrop provides page,
                    LocalGlassMorphWorkObserver provides { kind, window, phase ->
                        if (kind == "dialog" && window != null && popup == null) popup = window
                        if (kind == "geometry" && window === popup && phase != null) progress = phase
                    }) {
                    GlassPopupHome(home, chrome = {}) {
                        Box(Modifier.fillMaxSize()) {
                            // A light page with structure, so a lens (refraction band, blur) differs spatially from no lens.
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(page)) {
                                drawRect(androidx.compose.ui.graphics.Color(0xFFF2F2F4))
                                var y = 0f
                                while (y < size.height) { drawRect(androidx.compose.ui.graphics.Color(0xFFD5D8E0), androidx.compose.ui.geometry.Offset(0f, y),
                                    androidx.compose.ui.geometry.Size(size.width, 6.dp.toPx())); y += 18.dp.toPx() }
                            }
                            Box(Modifier.align(Alignment.TopEnd).padding(24.dp).testTag("retract-source")) {
                                val menu: @Composable ColumnScope.() -> Unit = { Column(Modifier.fillMaxWidth().height(240.dp)) { Text("Retract reference") } }
                                if (pill) PopupActionPill(anchor, modifier = Modifier.height(CompactControlSize),
                                    role = TriggerRole.BrowseOptions, trailingLabel = "Open retract menu", menuVisible = open,
                                    onMenuDismiss = { open = false }, menuContent = menu, onSearch = {}, onMore = { open = true })
                                else {
                                    ActionIcon(AppIcon.ActionSort, "Open retract menu", anchor = anchor) { open = true }
                                    GlassMenu(open, anchor, { open = false }, content = menu)
                                }
                            }
                        }
                    }
                }
            } }
            compose.waitForIdle()
            val activity = compose.runOnUiThread { generateSequence(host.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().first().window }
            val bounds = compose.onNodeWithTag("retract-source").fetchSemanticsNode().boundsInWindow
            val l = bounds.left.toInt(); val t = bounds.top.toInt(); val r = bounds.right.toInt(); val b = bounds.bottom.toInt()
            compose.mainClock.autoAdvance = false
            frames.openReferenceTrigger("Open retract menu")
            compose.waitUntil(10000) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); popup?.decorView?.hasWindowFocus() == true && progress >= .999f }
            val window = requireNotNull(popup)
            val sourceRecord = compose.runOnUiThread { anchor.material?.value }
            compose.runOnUiThread { open = false }
            var last: DoubleArray? = null
            // Composite of both windows over the source, the image the display shows.
            fun sample(frame: Int): JSONObject {
                val base = frames.capture(activity, output, "a-$frame")
                val over = if (window.decorView.isAttachedToWindow) frames.capture(window, output, "d-$frame") else null
                val origin = IntArray(2).also { compose.runOnUiThread { activity.decorView.getLocationOnScreen(it) } }
                val dialogOrigin = IntArray(2).also { compose.runOnUiThread { if (over != null) window.decorView.getLocationOnScreen(it) } }
                var fill = 0.0; var fillCount = 0; var rim = 0.0; var rimCount = 0
                val inset = ((b - t) * .12f).toInt().coerceAtLeast(3)
                val luma = DoubleArray((r - l) * (b - t))
                for (y in t until b) for (x in l until r) {
                    var c = base.getPixel(x, y)
                    if (over != null) {
                        val ox = x + origin[0] - dialogOrigin[0]; val oy = y + origin[1] - dialogOrigin[1]
                        if (ox in 0 until over.width && oy in 0 until over.height) {
                            val o = over.getPixel(ox, oy); val a = Color.alpha(o) / 255f
                            c = Color.rgb((Color.red(o) * a + Color.red(c) * (1 - a)).toInt(),
                                (Color.green(o) * a + Color.green(c) * (1 - a)).toInt(), (Color.blue(o) * a + Color.blue(c) * (1 - a)).toInt())
                        }
                    }
                    val value = .2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c)
                    luma[(y - t) * (r - l) + x - l] = value
                    val edge = minOf(x - l, r - 1 - x, y - t, b - 1 - y)
                    // The rim is the outer band; the fill is the band between rim and the centred face.
                    if (edge in 1..inset) { rim += value; rimCount++ }
                    else if (edge in inset + 2..inset * 2 + 2) { fill += value; fillCount++ }
                }
                base.recycle(); over?.recycle()
                File(output, "a-$frame.png").delete(); File(output, "d-$frame.png").delete()
                // Spatial step against the previous displayed frame: outline, refraction band and face, not only band means.
                val step = last?.let { p -> luma.indices.map { abs(luma[it] - p[it]) } }
                last = luma
                val drawn = synchronized(receipts) { receipts.toList().also { receipts.clear() } }
                val withdraws = drawn.count { it is GlassBodyReceipt && it.event == "withdraw" }
                val refills = drawn.count { it is GlassBodyReceipt && it.event != "withdraw" }
                val body = drawn.filterIsInstance<GlassSurfaceDrawObservation>().lastOrNull { it.windowIdentity != null && it.presented != null }
                return JSONObject().put("frame", frame).put("phase", progress).put("hidden", anchor.hidden).put("present", anchor.modalPresent)
                    .put("fill", fill / fillCount.coerceAtLeast(1)).put("rim", rim / rimCount.coerceAtLeast(1))
                    .put("pixelMax", step?.maxOrNull() ?: 0.0).put("pixelMean", step?.average() ?: 0.0)
                    .put("pixelsOver", step?.count { it > PixelLimit } ?: 0)
                    .put("withdraws", withdraws).put("refills", refills).put("body", body?.presented?.let { JSONArray(it) })
                    .put("bodyTint", body?.tintArgb).put("bodyDepthScale", body?.depthScale)
            }
            var after = 0
            for (frame in 0 until 240) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                rows.put(sample(frame)); save()
                if (!anchor.hidden && !anchor.modalPresent && ++after >= 6) break
            }
            val all = (0 until rows.length()).map { rows.getJSONObject(it) }
            // Smooth-motion bound over the settled end of the retract (phase <= .01, the body within a few pixels of home)
            // through the hand-off: earlier frames still carry the body edge across the measured bands.
            val tail = all.filter { it.getDouble("phase") <= .01 || !it.getBoolean("hidden") }
            fun step(key: String) = tail.zipWithNext { a, b -> abs(b.getDouble(key) - a.getDouble(key)) }.maxOrNull() ?: 0.0
            val handOff = all.first { !it.getBoolean("hidden") }
            // Emulator flicker is isolated pixels, a step moves a region: the hand-off may change no more pixels by > PixelLimit
            // than the settled frames just before it, plus a margin of the source area (pre-fix step: 24-28% of it).
            val settled = all.take(all.indexOf(handOff)).takeLast(3)
            val countBound = (settled.maxOfOrNull { it.getInt("pixelsOver") } ?: 0) + (r - l) * (b - t) * CountMargin
            val lastBody = all.lastOrNull { it.has("body") && !it.isNull("body") }
            report.put("fillStep", step("fill")).put("rimStep", step("rim")).put("handOffPixelsOver", handOff.getInt("pixelsOver")).put("countBound", countBound)
                .put("handOffPixelMean", handOff.getDouble("pixelMean")).put("withdraws", all.sumOf { it.getInt("withdraws") })
                .put("refills", all.sumOf { it.getInt("refills") }).put("status", "captured"); save()
            assertTrue("retract covers the hand-off: ${tail.size} tail frames", tail.size >= 4 && tail.last().let { !it.getBoolean("hidden") })
            assertEquals("three settled frames before the hand-off", 3, settled.size)
            // One optical owner per frame (#748 R5): the source takes the body once, and the popup never refills or rearms it.
            run {
                assertEquals("one R5 withdrawal per return", 1, all.sumOf { it.getInt("withdraws") })
                assertEquals("R5 unlink or rearm after the source took the body", 0, all.sumOf { it.getInt("refills") })
                // Material identity: the returning body's last record is the source's own record, lens weight included.
                val record = requireNotNull(sourceRecord) { "the source publishes its material record" }
                val drawn = requireNotNull(lastBody).getJSONArray("body")
                listOf(record.frost, record.lightMix, record.refraction, record.lens).forEachIndexed { i, expected ->
                    assertEquals("returning body record field ${i + 1} equals the source record", expected.toDouble(), drawn.getDouble(i + 1), 1e-3) }
                assertEquals("returning body tint", record.tint.toArgb(), lastBody.getInt("bodyTint"))
                assertEquals("returning body depth scale", record.depthScale.toDouble(), lastBody.getDouble("bodyDepthScale"), 1e-3)
            }
            assertTrue("fill step at the hand-off ${step("fill")} > $SmoothStep (frames.json in $output)", step("fill") <= SmoothStep)
            assertTrue("rim step at the hand-off ${step("rim")} > $SmoothStep (frames.json in $output)", step("rim") <= SmoothStep)
            assertTrue("hand-off pixels changed by > $PixelLimit: ${handOff.getInt("pixelsOver")} > $countBound (frames.json in $output)", handOff.getInt("pixelsOver") <= countBound)
            assertTrue("hand-off mean pixel step ${handOff.getDouble("pixelMean")} > $HandOffMean (frames.json in $output)", handOff.getDouble("pixelMean") <= HandOffMean)
        } finally { glassDrawProbe = null; compose.mainClock.autoAdvance = true; preferences.write(previous) }
    }

    /** Per-frame smooth-motion bound for band means; at the hand-off the geometry is identical, so pixels may differ only by
     * rasterisation noise. The base step is 12.8 (mean) on the same fixture. */
    private companion object { const val SmoothStep = 1.5; const val PixelLimit = 15.0; const val CountMargin = .02; const val HandOffMean = .5 }
}

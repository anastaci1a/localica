package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** #847: a popup returning into its source pill or round button hands over without a one-frame step in fill or rim. */
class PopupRetractTintTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    @org.junit.After fun removeMarkers() = frames.dispose()
    // Page-heading triggers (the library Sort/View pill, Back) have no sampled source: only the shell's modal recording.
    @Test fun pillRetractHandsOverWithoutStep() = retract(pill = true, sourced = false)
    @Test fun roundButtonRetractHandsOverWithoutStep() = retract(pill = false, sourced = false)
    @Test fun sourcedPillRetractHandsOverWithoutStep() = retract(pill = true, sourced = true)

    private fun retract(pill: Boolean, sourced: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-retract-tint", context.getExternalFilesDir(null)),
            (if (pill) "pill" else "round") + if (sourced) "-sourced" else "").apply { mkdirs() }
        val rows = JSONArray()
        val report = JSONObject().put("source", BuildConfig.SOURCE_SHA).put("api", android.os.Build.VERSION.SDK_INT).put("frames", rows)
        fun save() = File(output, "frames.json").writeText(report.toString(2))
        var popup: Window? = null
        var progress = 0f
        lateinit var host: View
        lateinit var anchor: GlassAnchor
        var open by mutableStateOf(false)
        val draws = mutableListOf<GlassSurfaceDrawObservation>()
        glassDrawProbe = { receipt -> if (receipt is GlassSurfaceDrawObservation) synchronized(draws) { draws += receipt } }
        try {
            preferences.write(InterfaceSettings(reducedMotion = false, animationSpeedTenths = 10, glass = GlassAppearance(renderer = GlassRendererMode.Normal)))
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
                            // A light page like the library grid: the source's resting tint is close to the page.
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(page)) { drawRect(androidx.compose.ui.graphics.Color(0xFFF2F2F4)) }
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
            compose.mainClock.autoAdvance = false
            frames.openReferenceTrigger("Open retract menu")
            compose.waitUntil(10000) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); popup?.decorView?.hasWindowFocus() == true && progress >= .999f }
            val window = requireNotNull(popup)
            compose.runOnUiThread { open = false }
            // Composite of both windows over the source, the image the display shows.
            fun sample(frame: Int): JSONObject {
                val base = frames.capture(activity, output, "a-$frame")
                val over = if (window.decorView.isAttachedToWindow) frames.capture(window, output, "d-$frame") else null
                val origin = IntArray(2).also { compose.runOnUiThread { activity.decorView.getLocationOnScreen(it) } }
                val dialogOrigin = IntArray(2).also { compose.runOnUiThread { if (over != null) window.decorView.getLocationOnScreen(it) } }
                var fill = 0.0; var fillCount = 0; var rim = 0.0; var rimCount = 0
                val l = bounds.left.toInt(); val t = bounds.top.toInt(); val r = bounds.right.toInt(); val b = bounds.bottom.toInt()
                val inset = ((b - t) * .12f).toInt().coerceAtLeast(3)
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
                    val luma = .2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c)
                    val edge = minOf(x - l, r - 1 - x, y - t, b - 1 - y)
                    // The rim is the outer band; the fill is the band between rim and the centred face.
                    if (edge in 1..inset) { rim += luma; rimCount++ }
                    else if (edge in inset + 2..inset * 2 + 2) { fill += luma; fillCount++ }
                }
                base.recycle(); over?.recycle()
                File(output, "a-$frame.png").delete(); File(output, "d-$frame.png").delete()
                val drawn = synchronized(draws) { draws.toList().also { draws.clear() } }
                fun material(o: GlassSurfaceDrawObservation?) = o?.let { JSONObject().put("frost", it.frost).put("lightMix", it.toneLightMix)
                    .put("refraction", it.refraction).put("tint", it.tintArgb).put("trim", it.toneTrim).put("blur", it.blur)
                    .put("whiteTint", it.whiteTint).put("depthScale", it.depthScale).put("homeWeight", it.suppliedHomeWeight).put("presented", it.presented?.let { p -> JSONArray(p) }) }
                return JSONObject().put("frame", frame).put("phase", progress).put("hidden", anchor.hidden).put("present", anchor.modalPresent)
                    .put("fill", fill / fillCount.coerceAtLeast(1)).put("rim", rim / rimCount.coerceAtLeast(1))
                    .put("popup", material(drawn.lastOrNull { it.windowIdentity != null }))
                    .put("source", material(drawn.lastOrNull { it.windowIdentity == null }))
            }
            var after = 0
            for (frame in 0 until 240) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                rows.put(sample(frame)); save()
                if (!anchor.hidden && !anchor.modalPresent && ++after >= 6) break
            }
            // Smooth-motion bound: at the end of the retract each frame may move fill and rim by at most
            // what the geometry's own last frames move them; a hand-off step is several times larger.
            val tail = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getDouble("phase") <= .05 || !it.getBoolean("hidden") }
            fun step(key: String) = tail.zipWithNext { a, b -> abs(b.getDouble(key) - a.getDouble(key)) }.maxOrNull() ?: 0.0
            report.put("fillStep", step("fill")).put("rimStep", step("rim")).put("status", "captured"); save()
            assertTrue("retract covers the hand-off: ${tail.size} tail frames", tail.size >= 4 && tail.last().let { !it.getBoolean("hidden") })
            assertTrue("fill step at the hand-off ${step("fill")} > $SmoothStep (frames.json in $output)", step("fill") <= SmoothStep)
            assertTrue("rim step at the hand-off ${step("rim")} > $SmoothStep (frames.json in $output)", step("rim") <= SmoothStep)
        } finally { glassDrawProbe = null; compose.mainClock.autoAdvance = true; preferences.write(previous) }
    }

    private companion object { const val SmoothStep = 1.5 }
}

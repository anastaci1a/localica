package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Same menu, clock and assertions on the published base and the candidate.
 * Only the test host adapter bridges the old Box API to the shared chrome slot. */
class PopupBrowseReturnReferenceTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    @org.junit.After fun removeMarkers() = frames.dispose()
    @Test fun normalDepartingMenuLayerReference() = capture(GlassRendererMode.Normal)
    @Test fun singleToneDepartingMenuLayerReference() = capture(GlassRendererMode.SingleToneBlur)

    private fun capture(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-browse-return-reference", context.getExternalFilesDir(null)), mode.name).apply { mkdirs() }
        val rows = JSONArray()
        val report = JSONObject().put("source", BuildConfig.SOURCE_SHA).put("renderer", mode.name).put("frames", rows)
            .put("api", android.os.Build.VERSION.SDK_INT).put("fingerprint", android.os.Build.FINGERPRINT)
            .put("model", android.os.Build.MODEL).put("density", context.resources.displayMetrics.density)
        fun save() = File(output, "frames.json").writeText(report.toString(2))
        var popup: Window? = null
        var progress = 0f
        lateinit var home: GlassPopupHome
        var open by mutableStateOf(false)
        var departing by mutableStateOf(false)
        try {
            preferences.write(InterfaceSettings(reducedMotion = false, fasterMotion = false, glass = GlassAppearance(renderer = mode)))
            compose.setContent { LocalicaTheme {
                home = rememberGlassPopupHome(departing)
                val page = rememberFrostedBackdrop(home, dark = false)
                val anchor = rememberGlassAnchor()
                CompositionLocalProvider(LocalFrostedBackdrop provides page,
                    LocalGlassMorphWorkObserver provides { kind, window, phase ->
                        if (kind == "dialog" && window != null && popup == null) popup = window
                        if (kind == "geometry" && window === popup && phase != null) progress = phase
                    }) {
                    R1ReferenceHome(home, chrome = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height(64.dp)) {
                                SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }, source = page) {}
                            }
                        }
                    }) {
                        Box(Modifier.fillMaxSize().recordFrostedBackdrop(page)) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color.White)
                                val stripe = 24.dp.toPx()
                                var y = 0f
                                var index = 0
                                while (y < size.height) {
                                    drawRect(if (index++ % 2 == 0) Color(0xFF234F91) else Color(0xFFF2D341), Offset(0f, y), Size(size.width, stripe))
                                    y += stripe
                                }
                            }
                            Box(Modifier.align(Alignment.BottomEnd)) {
                                ActionIcon(AppIcon.ActionSort, "Open reference menu", anchor = anchor) { open = true }
                            }
                            GlassMenu(open, anchor, { open = false }) {
                                Column(Modifier.fillMaxWidth().height(240.dp)) {
                                    Text("Departure reference")
                                    Text("Identical glass and chrome on both source revisions")
                                }
                            }
                        }
                    }
                }
            } }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Open reference menu").performClick()
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                popup?.decorView?.hasWindowFocus() == true && progress >= .999f && home.flights.size == 1
            }
            val window = requireNotNull(popup)
            val flight = compose.runOnUiThread { home.flights.single() }
            compose.runOnUiThread { open = false; departing = true }
            // Match the ordinary held-page lifecycle on both revisions, then
            // cancel the page departure while the dismissed menu keeps returning.
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                flight.handedOff && flight.wasProjected
            }
            compose.runOnUiThread { departing = false }
            var highest = 0f
            var lowest = 1f
            var count = 0
            for (frame in 0 until 96) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                if (!window.decorView.isAttachedToWindow) break
                if (progress !in .04f.. .95f || !flight.prepared) continue
                frames.capture(window, output, "return-$frame-window").recycle()
                val image = runBlocking { requireNotNull(flight.layer).toImageBitmap() }.asAndroidBitmap()
                try {
                    val name = "return-$frame-layer.png"
                    File(output, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    rows.put(JSONObject().put("frame", frame).put("phase", progress)
                        .put("phaseBits", progress.toRawBits()).put("layer", name)
                        .put("originX", flight.origin.x).put("originY", flight.origin.y)
                        .put("width", image.width).put("height", image.height).put("projected", flight.wasProjected))
                    save()
                    count++; highest = maxOf(highest, progress); lowest = minOf(lowest, progress)
                } finally { image.recycle() }
            }
            report.put("highestPhase", highest).put("lowestPhase", lowest).put("status", "captured"); save()
            assertTrue("R1 reference covers the non-chrome departure", count >= 3 && highest >= .6f && lowest <= .2f)
            assertTrue("R1 reference captures an actual projected return", flight.wasProjected)
        } finally { compose.mainClock.autoAdvance = true; preferences.write(previous) }
    }
}

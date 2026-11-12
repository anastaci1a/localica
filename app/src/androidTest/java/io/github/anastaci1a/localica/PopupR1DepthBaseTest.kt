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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.navigation.MainTab
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

/** Fixture-only R1 back-port. Production is exactly published v0.34.1. */
class PopupR1DepthBaseTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    private val channels: List<(Int)->Int> = listOf({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
    @org.junit.After fun removeMarkers() = frames.dispose()
    @Test fun normalDepartingBrowseMenuStaysAboveTabs() = nonChromeMenu(GlassRendererMode.Normal)
    @Test fun singleToneDepartingBrowseMenuStaysAboveTabs() = nonChromeMenu(GlassRendererMode.SingleToneBlur)

    private fun nonChromeMenu(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/non-chrome-menu").apply { mkdirs() }
        lateinit var host: View
        lateinit var home: GlassPopupHome
        var popup: Window? = null
        var popupPhase = 0f
        lateinit var fixtureAnchor: GlassAnchor
        val phaseControl = JSONArray()
        lateinit var baseScene: androidx.compose.ui.graphics.layer.GraphicsLayer
        var baseScenePixels: Bitmap? = null
        var open by mutableStateOf(false)
        var leaving by mutableStateOf(false)
        val receipts = JSONArray()
        try {
            preferences.write(InterfaceSettings(glass = GlassAppearance(renderer = mode)))
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                home = rememberGlassPopupHome(leaving)
                val page = rememberFrostedBackdrop(home, dark = false)
                baseScene = rememberGraphicsLayer()
                BackdropLease(home.backdrop)
                CompositionLocalProvider(LocalGlassMorphWorkObserver provides { kind, window, phase ->
                    if (kind == "dialog" && window != null && popup == null) popup = window
                    if (kind == "geometry" && window === popup && phase != null) popupPhase = phase
                }) {
                    // Base's home recorded this complete scene: page plus chrome,
                    // before any non-chrome flight was drawn above the home.
                    Box(Modifier.fillMaxSize().drawWithContent {
                        baseScene.record { this@drawWithContent.drawContent() }
                        drawLayer(baseScene)
                    }) {
                    R1ReferenceHome(home, chrome = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height(64.dp).testTag("overlap-tabs")) {
                                SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }, source = page) {}
                            }
                        }
                    }) {
                        Box(Modifier.fillMaxSize().recordFrostedBackdrop(page).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.BottomEnd) {
                            R1BasePopupActionPill(rememberGlassAnchor().also { fixtureAnchor = it }, source = page,
                                modifier = Modifier.size(104.dp, 64.dp), trailingLabel = "Open overlap menu", menuVisible = open, onMenuDismiss = { open = false },
                                menuContent = {
                                    ActionMenuItem("Leave page", icon = AppIcon.TabFolders, onClick = { open = false; leaving = true })
                                    Box(Modifier.fillMaxWidth().height(240.dp).background(androidx.compose.ui.graphics.Color.Green))
                                }, onSearch = {}, onMore = { open = true })
                        }
                    }
                    }
                }
            } }
            compose.waitForIdle()
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            original(activity, output, "base-complete-root").recycle()
            baseScenePixels = runBlocking { baseScene.toImageBitmap() }.asAndroidBitmap()
            File(output, "base-complete-scene.png").outputStream().use {
                assertTrue(requireNotNull(baseScenePixels).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(output, "trigger-pre-open.json").writeText(JSONObject()
                .put("hidden", fixtureAnchor.hidden).put("quietPolicy", fixtureAnchor.quiet)
                .put("surfaceShadowEnabled", true).put("bounds", fixtureAnchor.bounds.toString())
                .put("scene", "base-complete-scene.png").toString(2))
            compose.mainClock.autoAdvance = false
            frames.openReferenceTrigger("Open overlap menu")
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                popup?.decorView?.hasWindowFocus() == true && home.flights.size == 1 && popupPhase >= .999f
            }
            val window = requireNotNull(popup)
            val flight = compose.runOnUiThread { home.flights.single() }
            // Published base has no chromeTrigger field: its ordinary flights all project above chrome.
            val tabBounds = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("Leave page").performClick()
            // A mounted departing page deliberately holds its menu at full size.
            // Cancel that page transition only after its inline draw is admitted;
            // the dismissed menu then completes a real projected return.
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                flight.handedOff && flight.wasProjected
            }
            compose.runOnUiThread { leaving = false }
            var qualified = 0
            for (frame in 0 until 96) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val attached = window.decorView.isAttachedToWindow
                val candidatePhase = r1CandidateFlightPhase(flight)
                phaseControl.put(JSONObject().put("frame", frame).put("geometryPhase", popupPhase)
                    .put("flightPhase", candidatePhase ?: JSONObject.NULL).put("prepared", flight.prepared)
                    .put("attached", attached).put("triggerHidden", fixtureAnchor.hidden)
                    .put("geometrySelected", attached && flight.prepared && popupPhase in .1f.. .8f)
                    .put("flightSelected", candidatePhase?.let { attached && flight.prepared && it in .1f.. .8f } ?: JSONObject.NULL))
                File(output, "phase-control.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                    .put("renderer", mode.name).put("samples", phaseControl).put("qualifiedFrames", qualified).toString(2))
                if (!attached) break
                val phase = popupPhase
                if (phase !in .1f.. .8f || !flight.prepared) continue
                val modal = original(window, output, "return-$frame-popup")
                val root = original(activity, output, "return-$frame-root")
                val layer = runBlocking { requireNotNull(flight.layer).toImageBitmap() }.asAndroidBitmap()
                val source = runBlocking { requireNotNull(home.backdrop).layer.toImageBitmap() }.asAndroidBitmap()
                try {
                    if (!flight.handedOff) continue
                    val hostAt = IntArray(2); val rootAt = IntArray(2)
                    compose.runOnUiThread { host.getLocationOnScreen(hostAt); activity.decorView.getLocationOnScreen(rootAt) }
                    File(output, "return-$frame-flight-layer.png").outputStream().use { assertTrue(layer.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    File(output, "return-$frame-sampled-scene.png").outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    var sourcePixels = 0; var sourceChanged = 0; var chromePixels = 0
                    // The independent base scene was frozen before opening. The
                    // left tabs stay still; exclude the trigger at the right.
                    for (y in tabBounds.top.toInt() until tabBounds.bottom.toInt())
                        for (x in tabBounds.left.toInt() until tabBounds.center.x.toInt()) {
                            val sx = x + hostAt[0] - home.currentOrigin.x.toInt()
                            val sy = y + hostAt[1] - home.currentOrigin.y.toInt()
                            val expected = requireNotNull(baseScenePixels).getPixel(sx, sy)
                            if (expected != Color.WHITE) chromePixels++
                            if (source.getPixel(sx, sy) != expected) sourceChanged++
                            sourcePixels++
                        }
                    assertTrue("The base recording contains real tab ink", chromePixels > 100)
                    assertEquals("R1: a departing non-chrome menu samples the base scene including tabs", 0, sourceChanged)
                    var sampled = 0; var bad = 0
                    for (y in tabBounds.top.toInt() until tabBounds.bottom.toInt() step 2)
                        for (x in tabBounds.left.toInt() until tabBounds.right.toInt() step 2) {
                            val lx = x + hostAt[0] - flight.origin.x.toInt()
                            val ly = y + hostAt[1] - flight.origin.y.toInt()
                            if (lx !in 0 until layer.width || ly !in 0 until layer.height) continue
                            val expected = layer.getPixel(lx, ly)
                            if (Color.alpha(expected) < 254 || Color.green(expected) < 200 || Color.red(expected) > 40 || Color.blue(expected) > 40) continue
                            val actual = root.getPixel(x + hostAt[0] - rootAt[0], y + hostAt[1] - rootAt[1])
                            if (channels.maxOf { abs(it(expected) - it(actual)) } > 14) bad++
                            sampled++
                        }
                    receipts.put(JSONObject().put("frame", frame).put("phase", phase).put("pixels", sampled).put("changed", bad)
                        .put("baseSourcePixels", sourcePixels).put("baseSourceChanged", sourceChanged))
                    File(output, "frames.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                        .put("renderer", mode.name).put("samples", receipts).toString(2))
                    if (sampled > 10) {
                        qualified++
                        assertTrue("Non-chrome departing menu stays above actual tabs: $bad/$sampled", bad <= max(4, sampled / 1000))
                    }
                } finally { modal.recycle(); root.recycle(); layer.recycle(); source.recycle() }
            }
            File(output, "phase-control.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                .put("renderer", mode.name).put("samples", phaseControl).put("qualifiedFrames", qualified).toString(2))
            assertTrue("Non-chrome menu overlaps tabs in at least three return frames: $receipts", qualified >= 3)
        } finally { baseScenePixels?.recycle(); compose.mainClock.autoAdvance = true; preferences.write(previous) }
    }

    private fun original(window: Window, output: File, name: String): Bitmap = frames.capture(window, output, name)
}

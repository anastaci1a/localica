package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presented-pixel regression plus retained baseline comparison frames for tap/held optics. */
class SwitchTapOpticsTest {
    @get:Rule val compose = createComposeRule()
    private var stage by mutableIntStateOf(0)
    private lateinit var view: View
    private val backings = mutableMapOf<String, FrostedBackdrop>()

    @Test fun recordShortTapAndHeldReleasePatternDuringTravel() {
        var tapped by mutableStateOf(false)
        var held by mutableStateOf(false)
        var speed by mutableFloatStateOf(4f / 3f)
        var generation by mutableIntStateOf(0)
        var reduced by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            CompositionLocalProvider(LocalMotionSpeed provides speed, LocalReducedMotion provides reduced) {
                key(generation) {
                    Column(Modifier.width(280.dp)) {
                        Sample("tap", tapped) { tapped = it }
                        Sample("held", held) { held = it }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (mode in listOf("direct", "row", "semantic")) for (faster in listOf(false, true)) {
            compose.runOnIdle { speed = if (faster) 2f else 4f / 3f; tapped = false; held = false; generation++ }
            compose.mainClock.advanceTimeBy(32)
            // Find the switch through the unmerged tree; the row owns public semantics.
            val heldSwitch = compose.onAllNodesWithTag("settings-switch", useUnmergedTree = true)[1]
            heldSwitch.performTouchInput { down(center) }
            awaitRecording("held")
            compose.mainClock.advanceTimeBy(320)
            capture("held-ready-$mode-$faster", "held")
            android.util.Log.i("SwitchTapOptics", "before-input clock=${compose.mainClock.currentTime}")
            heldSwitch.performTouchInput { up() }
            android.util.Log.i("SwitchTapOptics", "after-held-release clock=${compose.mainClock.currentTime}")
            if (mode == "row") compose.onNodeWithText("tap switch").performTouchInput { click(Offset(12f, centerY)) }
            else if (mode == "semantic") compose.onNodeWithText("tap switch").performClick()
            else compose.onAllNodesWithTag("settings-switch", useUnmergedTree = true)[0]
                .performTouchInput { down(center); advanceEventTime(8); up() }
            android.util.Log.i("SwitchTapOptics", "after-input clock=${compose.mainClock.currentTime}")
            var elapsed = 0L
            for (at in listOf(16L, 48L, 96L, 160L, 240L)) {
                compose.mainClock.advanceTimeBy(at - elapsed); elapsed = at
                val visibleBackdrop = capture("tap-$mode-$faster-$at", "tap")
                if (at == 160L) assertTrue("Automatic travel must retain a refracted backdrop band ($mode, faster=$faster)", visibleBackdrop > 10)
                capture("held-$mode-$faster-$at", "held")
            }
            compose.runOnIdle { assertTrue(tapped); assertTrue(held) }
            compose.onNodeWithText("tap switch").performClick()
            compose.mainClock.advanceTimeBy(48)
            capture("reverse-$mode-$faster", "tap")
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertFalse(tapped); assertTrue(held) }
            capture("settled-off-$mode-$faster", "tap")
            capture("settled-on-$mode-$faster", "held")
            heldSwitch.performTouchInput { down(Offset(width - 8f, centerY)) }
            compose.mainClock.advanceTimeBy(320)
            capture("held-on-$mode-$faster", "held")
            heldSwitch.performTouchInput { moveTo(Offset(8f, centerY), delayMillis = 64) }
            compose.mainClock.advanceTimeBy(240)
            capture("held-drag-$mode-$faster", "held")
            heldSwitch.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertFalse(held) }
        }
        compose.runOnIdle { reduced = true }
        compose.onNodeWithText("tap switch").performClick()
        compose.mainClock.advanceTimeBy(32)
        capture("reduced-snap", "tap")
        compose.runOnIdle { assertTrue(tapped) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun recordPopupShortTapReversalAndReopenMaterial() {
        var open by mutableStateOf(true)
        var checked by mutableStateOf(false)
        var material: FrostedBackdrop? = null
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
            val anchor = rememberGlassAnchor()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) {
                    val cell = 12.dp.toPx()
                    for (x in 0..(size.width / cell).toInt()) drawRect(
                        if (x % 2 == 0) Color.Green else Color.Black,
                        Offset(x * cell, 0f), Size(cell, size.height))
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    GlassMenu(open, anchor, { open = false }) {
                        view = LocalView.current
                        val backing = LocalFrostedBackdrop.current
                        SideEffect { material = backing; backing?.let { backings["popup-switch"] = it } }
                        Box(Modifier.width(260.dp).height(96.dp).testTag("popup-switch")) {
                            Box(Modifier.padding(24.dp)) { BooleanPreference("Popup switch", checked) { checked = it } }
                            PresentationMarker(Modifier.align(Alignment.TopCenter)) { stage }
                        }
                    }
                }
            }
        } }
        repeat(2) { opening ->
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithText("Popup switch").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            capture("popup-$opening-idle", "popup-switch")
            compose.onAllNodesWithTag("settings-switch", useUnmergedTree = true)[0]
                .performTouchInput { down(center); advanceEventTime(8); up() }
            for (frame in 0..5) {
                compose.mainClock.advanceTimeBy(32)
                android.util.Log.i("SwitchTapOptics", "popup-$opening-$frame backingRecorded=${material?.recorded} raster=${material?.snapshot != null} consumers=${material?.consumers}")
                capture("popup-$opening-$frame", "popup-switch")
                if (frame == 2) compose.onNodeWithText("Popup switch").performClick()
            }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertFalse(checked); open = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            if (opening == 0) compose.runOnIdle { open = true }
        }
    }

    @Test fun coldRecordingKeepsTheWhiteFaceWithoutDelayingTheToggle() {
        var checked by mutableStateOf(false)
        var record by mutableStateOf(false)
        var opticalDraws = 0
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            CompositionLocalProvider(LocalControlLensDraw provides { opticalDraws++ }) {
                Box(Modifier.width(280.dp)) { Sample("cold", checked, record) { checked = it } }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("cold switch").performClick()
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertTrue("Logical toggle does not wait for recording", checked)
            assertTrue("Engagement requests its source before optical drawing", backings.getValue("cold").consumers > 0)
            assertEquals("No track-only optical frame before backing", 0, opticalDraws)
        }
        capture("cold-unready", "cold")
        compose.runOnIdle { record = true }
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { down(center) }
        awaitRecording("cold")
        compose.mainClock.advanceTimeBy(320)
        capture("held-ready-cold", "cold")
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1200)
        capture("settled-cold", "cold")
        compose.runOnIdle { assertEquals("Settling releases the prewarm lease", 0, backings.getValue("cold").consumers) }
        compose.mainClock.autoAdvance = true
    }

    @Composable private fun Sample(tag: String, checked: Boolean, recordingAllowed: Boolean = true, change: (Boolean) -> Unit) {
        val source = rememberFrostedBackdrop(Unit, enabled = recordingAllowed, glassBlur = 0f, whiteTint = 0f)
        SideEffect { backings[tag] = source }
        Box(Modifier.fillMaxWidth().height(96.dp).testTag(tag)) {
            Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source, enabled = recordingAllowed)) {
                val cell = 8.dp.toPx()
                for (x in 0..(size.width / cell).toInt()) drawRect(
                    if (x % 2 == 0) Color.Green else Color.Black,
                    Offset(x * cell, 0f), Size(cell, size.height))
            }
            Canvas(Modifier.matchParentSize()) { drawRect(Color.Blue) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                    BooleanPreference("$tag switch", checked, change)
                }
            }
            PresentationMarker { stage }
        }
    }

    private fun awaitRecording(tag: String) {
        // Readback uses real rendering as well as Compose frames. Prime the held
        // reference from source readiness, never by waiting for the optical pixels.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            val source = backings[tag]
            source != null && if (android.os.Build.VERSION.SDK_INT >= 33)
                source.recorded && !source.layer.isReleased else source.snapshot != null
        }
    }

    private fun capture(label: String, tag: String): Int {
        compose.runOnIdle { stage = stage % 30 + 1 }
        val host = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val sample = if (tag == "popup-switch") IntOffset(host.width.toInt() / 2, 2) else IntOffset(2, 2)
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag(tag), stage, sample) { view }
        val rasterReady = backings[tag]?.snapshot != null
        val recorded = backings[tag]?.recorded == true
        val control = compose.onAllNodes(hasTestTag("settings-switch") and hasAnyAncestor(hasTestTag(tag)),
            useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val centerY = control.center.y - host.top
        val halfTrack = control.height / 3f // 32 dp track in a 48 dp switch.
        val left = (control.left - host.left - control.height / 4).toInt().coerceAtLeast(0)
        val right = (control.right - host.left + control.height / 4).toInt().coerceAtMost(pixels.width)
        var green = 0; var outsideGreen = 0; var white = 0
        for (y in 0 until pixels.height) for (x in left until right) {
            val c = pixels[x, y]
            if (c.green > .45f && c.red < .25f && c.blue < .25f) {
                green++
                if (kotlin.math.abs(y - centerY) > halfTrack) outsideGreen++
            }
            if (c.red > .85f && c.green > .85f && c.blue > .85f) white++
        }
        android.util.Log.i("SwitchTapOptics", "$label clock=${compose.mainClock.currentTime} green=$green outsideTrackGreen=$outsideGreen white=$white backingRecorded=$recorded raster=$rasterReady size=${pixels.width}x${pixels.height}")
        // Small PNGs preserve the actual presented contour and pattern for review.
        val bitmap = android.graphics.Bitmap.createBitmap(pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) bitmap.setPixel(x, y, pixels[x, y].let {
                android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
            })
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(2500)
            android.util.Log.i("SwitchTapPixels", "$label total=${chunks.size} bytes=${bytes.size}")
            chunks.forEachIndexed { i, chunk ->
                android.util.Log.i("SwitchTapPixels", "$label part=$i $chunk")
                // Avoid overflowing legacy logcat while the captured frame/Compose clock stays frozen.
                android.os.SystemClock.sleep(2)
            }
        } finally { bitmap.recycle() }
        if (label == "cold-unready") {
            assertEquals("Unready switch does not expose missing backing", 0, green)
            assertTrue("Unready switch retains opaque white", white > 10)
        }
        if (label.startsWith("settled-")) {
            assertEquals("Settled controls stop exposing the backing", 0, green)
            assertTrue("Settled switch thumb returns to white", white > 10)
        }
        if (label.startsWith("tap-") && android.os.Build.VERSION.SDK_INT < 33 && !rasterReady)
            assertTrue("Cold legacy travel remains white until sampleable", white > 10)
        if (label == "reduced-snap") assertEquals("Reduced motion settles without a travelling optical lens", 0, green)
        if (label.startsWith("held-ready")) assertTrue("Held lens must visibly sample its green backing", green > 10)
        return outsideGreen
    }
}

package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMorphTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneSurfaceExpandsFromTheTriggerAndReturnsBeforeRestoringIt() {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            anchor = rememberGlassAnchor()
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFFB4D8C8)) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                SideEffect { anchor.source = source }
                LiquidGlassSurface(Modifier.align(Alignment.BottomEnd).padding(24.dp).size(CompactControlSize)
                    .glassAnchor(anchor).clickable { open = true }.testTag("trigger")) { }
                GlassMorphPopup(open, anchor, { open = false }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Create playlist")
                        Spacer(Modifier.height(80.dp))
                        Text("Import playlist")
                    }
                }
            }
        } } }
        val start = compose.onNodeWithTag("trigger").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = true }
        compose.mainClock.advanceTimeBy(176)
        compose.waitForIdle()
        val middle = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("The original trigger is hidden throughout the morph", anchor.hidden)
        assertTrue("The original circle grows into the menu", middle.width > start.width)
        compose.mainClock.advanceTimeBy(1000)
        val expanded = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("Opening passes through an intermediate width", expanded.width > middle.width)
        compose.onNodeWithText("Create playlist").assertIsDisplayed()
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(176)
        assertTrue("Do not expose a second surface during the return", anchor.hidden)
        compose.onNodeWithTag("glass-morph-surface").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
        assertFalse(anchor.hidden)
        compose.onNodeWithTag("trigger").assertIsDisplayed()
    }

    @Test fun popupSamplesTheCorrectScreenCoordinatesAndNestedControlsSampleItsMaterial() {
        var open by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        lateinit var material: FrostedBackdrop
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            Box(Modifier.fillMaxSize()) {
                val anchor = rememberGlassAnchor()
                source = rememberFrostedBackdrop(Unit, dark = true)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Red, size = Size(size.width / 2, size.height))
                    drawRect(Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                }
                val list = rememberFrostedBackdrop(Unit, dark = true)
                Canvas(Modifier.padding(top = 320.dp).fillMaxWidth().height(100.dp).recordFrostedBackdrop(list)) { drawRect(Color.Green) }
                CompositionLocalProvider(LocalFrostedBackdrop provides list, LocalModalBackdrop provides source) {
                    SideEffect { anchor.source = list }
                    Box(Modifier.align(Alignment.BottomEnd).size(CompactControlSize).glassAnchor(anchor))
                    GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Search) {
                        material = LocalFrostedBackdrop.current!!
                        Box(Modifier.fillMaxWidth().height(200.dp)) {
                            LiquidGlassSurface(Modifier.align(Alignment.Center).size(CompactControlSize).testTag("nested-lens")) { }
                        }
                    }
                }
            }
        } } }
        if (android.os.Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        assertNotSame("Nested controls cannot resample the original scene", source, material)
        // Compose cannot capture Dialog roots before API 28. Read the real screen
        // using the material's global origin, also exercising cross-window alignment.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        // Compose idleness precedes the dialog's first SurfaceFlinger frame on some
        // software renderers. Require its darkened material before testing the blur.
        compose.waitUntil(10000) {
            val shot = automation.takeScreenshot()
            try {
                val sample = shot.asImageBitmap().toPixelMap()[(material.origin.x + material.size.width / 4).toInt(),
                    (material.origin.y + material.size.height / 3).toInt()]
                sample.red < .85f && sample.red > sample.blue + .2f
            } finally { shot.recycle() }
        }
        val screenshot = automation.takeScreenshot()
        val pixels = screenshot.asImageBitmap().toPixelMap()
        val left = pixels[(material.origin.x + material.size.width / 4).toInt(), (material.origin.y + material.size.height / 3).toInt()]
        val right = pixels[(material.origin.x + material.size.width * 3 / 4).toInt(), (material.origin.y + material.size.height / 3).toInt()]
        assertTrue("Correctly positioned red backdrop: $left", left.red > left.blue + .2f)
        assertTrue("Correctly positioned blue backdrop: $right", right.blue > right.red + .2f)
        val nearBoundary = pixels[(material.origin.x + material.size.width / 2 - 4).toInt(), (material.origin.y + material.size.height / 3).toInt()]
        assertTrue("Scatter pixels above the list's recorded bounds: $nearBoundary", nearBoundary.blue > .03f)
        compose.onNodeWithTag("nested-lens").assertIsDisplayed()
    }
}

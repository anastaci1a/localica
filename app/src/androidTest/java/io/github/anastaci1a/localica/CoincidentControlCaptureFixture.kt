package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*

/** Sequential host copies with continuous independent owners, not atomic presentation evidence. */
internal fun exerciseCoincidentControlScenes(compose: ComposeContentTestRule, frozenActual: Boolean) {
    var stage by mutableIntStateOf(0)
    var captures = 0
    var referenceCaptures = 0
    var draws = 0
    var referenceFront by mutableStateOf(false)
    var calibrating by mutableStateOf(true)
    val variant = if (frozenActual) "frozen-coincident" else "candidate-coincident"
    val runtime = GlassRendererRuntime(GlassRendererMode.Legacy)
    compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalGlassRendererRuntime provides runtime,
            LocalControlLensAllocation provides { if (it == LensAllocation.CaptureBitmap) captures++ },
            LocalControlLensDraw provides { draws++ }) {
            Box(Modifier.size(120.dp, 64.dp).testTag("capture-host")) {
                val geometry: androidx.compose.ui.graphics.drawscope.DrawScope.() -> LensGeometry = {
                    LensGeometry(Rect(24f, 12f, if (stage == 3) 92f else 88f, 52f), 20f)
                }
                val active = { when (stage) { 0, 6 -> 0f; 4 -> .4f; else -> 1f } }
                val body = { GlassShape(depth = if (stage == 4) -.08f else 0f) }
                val scene: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                    if (stage != 2) drawRect(if (stage % 2 == 0) Color.Red else Color.Blue,
                        topLeft = Offset(0f, 22f), size = Size(120f, 12f))
                }
                Box(Modifier.fillMaxSize().zIndex(if (referenceFront) 0f else 1f)
                    .background(Color.White).testTag("actual-slot")) {
                    // This choice is fixed for the entire test; front changes never replace owners.
                    if (frozenActual) CompositionLocalProvider(LocalOriginalControlLensDraw provides { draws++ }) {
                        OriginalControlCaptureScene(Modifier.fillMaxSize().testTag("candidate"), source = null,
                            geometry = geometry, body = body, active = active,
                            onCapture = { captures++ }, scene = scene)
                    } else GlassControlScene(Modifier.fillMaxSize().testTag("candidate"), source = null,
                        geometry = geometry, body = body, active = active, scene = scene)
                    Canvas(Modifier.fillMaxSize()) {
                        if (calibrating) drawRect(Color.Magenta, Offset(4f, 4f), Size(8f, 8f))
                    }
                }
                Box(Modifier.fillMaxSize().zIndex(if (referenceFront) 1f else 0f)
                    .background(Color.White).testTag("reference-slot")) {
                    OriginalControlCaptureScene(Modifier.fillMaxSize().testTag("reference"), source = null,
                        geometry = geometry, body = body, active = active,
                        onCapture = { referenceCaptures++ }, scene = scene)
                    Canvas(Modifier.fillMaxSize()) {
                        if (calibrating) drawRect(Color.Cyan, Offset(4f, 4f), Size(8f, 8f))
                    }
                }
            }
        }
    }
    val host = compose.onNodeWithTag("capture-host", useUnmergedTree = true)
    fun assertCoincidentBounds() {
        val bounds = host.fetchSemanticsNode().boundsInRoot
        assertEquals(120f, bounds.width, 0f)
        assertEquals(64f, bounds.height, 0f)
        for (tag in listOf("actual-slot", "reference-slot")) {
            assertEquals("Both retained slots occupy the measured host", bounds,
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        }
    }
    fun assertMarker(image: ImageBitmap, color: Color, label: String) {
        try {
            assertEquals("Front selection $label", color.toArgb(), image.toPixelMap()[8, 8].toArgb())
        } catch (failure: AssertionError) {
            preserveControlCaptureFailure(failure, listOf("actual" to image), label, variant)
            throw failure
        }
    }
    assertCoincidentBounds()
    compose.runOnIdle { referenceFront = false }
    val actualCalibration = host.captureToImage()
    assertMarker(actualCalibration, Color.Magenta, "calibration-actual-front")
    compose.runOnIdle { referenceFront = true }
    val referenceCalibration = host.captureToImage()
    assertMarker(referenceCalibration, Color.Cyan, "calibration-reference-front")
    compose.runOnIdle {
        assertEquals("Calibration is optically inactive", 0, captures)
        assertEquals("Calibration reference is optically inactive", 0, referenceCaptures)
        assertEquals("Calibration performs no optical draws", 0, draws)
        calibrating = false
    }
    var stableCaptures = 0
    var stableDraws = 0
    var stableReferences = 0
    for (next in 0..7) {
        compose.runOnIdle { stage = next; referenceFront = false }
        assertCoincidentBounds()
        val actual = host.captureToImage()
        compose.runOnIdle { referenceFront = true }
        assertCoincidentBounds()
        val reference = host.captureToImage()
        assertControlCapturePixels(actual, reference, "stage $next", variant)
        compose.runOnIdle {
            when (next) {
                0 -> assertEquals(0, captures)
                1 -> { assertTrue(draws > 0); stableCaptures = captures; stableDraws = draws; stableReferences = referenceCaptures }
                2 -> {
                    assertTrue(draws > stableDraws)
                    assertTrue(referenceCaptures > stableReferences)
                    if (frozenActual) assertTrue("Frozen actual slot retains fresh capture", captures > stableCaptures)
                    else if (Build.VERSION.SDK_INT < 29) assertEquals("Same extent reuses capture", stableCaptures, captures)
                    else assertTrue("API>=29 retains fresh capture", captures > stableCaptures)
                }
                3 -> assertTrue("Extent change allocates", captures > stableCaptures)
                6 -> { stableCaptures = captures; stableDraws = draws }
                7 -> { assertTrue("Reactivation draws", draws > stableDraws); assertTrue("Reactivation owns a fresh capture", captures > stableCaptures) }
            }
            android.util.Log.i("ControlCaptureWork", "control=$variant stage=$next api=${Build.VERSION.SDK_INT} captures=$captures referenceCaptures=$referenceCaptures draws=$draws")
        }
    }
}

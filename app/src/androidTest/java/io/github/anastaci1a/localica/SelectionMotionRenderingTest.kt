package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectionMotionRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun maskedTransitionNeverDoublesTranslucentForeground() {
        var fraction by mutableFloatStateOf(0f)
        var foreground by mutableStateOf(Color.Blue.copy(alpha = .4f))
        compose.setContent { LocalicaTheme {
            Row(Modifier.background(Color.Green)) {
                val base = rememberGraphicsLayer()
                val selected = rememberGraphicsLayer()
                val mask = rememberGraphicsLayer()
                Canvas(Modifier.size(64.dp).testTag("mixed")) {
                    base.record { drawRect(foreground) }
                    selected.record { drawRect(foreground) }
                    mask.record { drawRect(Color.White.copy(alpha = fraction)) }
                    drawSelectionTransition(base, selected, mask)
                }
                Canvas(Modifier.size(64.dp).testTag("reference")) { drawRect(foreground) }
            }
        } }
        for (color in listOf(Color.Blue.copy(alpha = .4f), Color.Red.copy(alpha = .7f))) {
            for (p in listOf(0f, .2f, .5f, .8f, 1f)) {
                compose.runOnIdle { fraction = p; foreground = color }
                val actual = compose.onNodeWithTag("mixed").captureToImage().toPixelMap()
                val expected = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
                val a = actual[actual.width / 2, actual.height / 2]
                val b = expected[expected.width / 2, expected.height / 2]
                assertEquals("Red at $p", b.red, a.red, 2f / 255)
                assertEquals("Green at $p", b.green, a.green, 2f / 255)
                assertEquals("Blue at $p", b.blue, a.blue, 2f / 255)
                assertEquals("Alpha at $p", b.alpha, a.alpha, 2f / 255)
            }
        }
    }

    @Test fun adjacentMixedOpacityMasksKeepTheExistingLensAndUseMaximumAtOverlap() {
        var entering by mutableFloatStateOf(.25f)
        compose.setContent { LocalicaTheme {
            val base = rememberGraphicsLayer()
            val selected = rememberGraphicsLayer()
            val mask = rememberGraphicsLayer()
            Row(Modifier.background(Color.Green)) {
                Canvas(Modifier.size(120.dp, 60.dp).testTag("mixed-entries")) {
                    base.record { drawRect(Color.Blue.copy(alpha = .4f)) }
                    selected.record { drawRect(Color.Red.copy(alpha = .7f)) }
                    mask.record {
                        drawSelectionVisibilityMask(listOf(
                            androidx.compose.ui.geometry.Rect(0f, 0f, size.width * .6f, size.height) to 1f,
                            androidx.compose.ui.geometry.Rect(size.width * .4f, 0f, size.width, size.height) to entering), 0f)
                    }
                    drawSelectionTransition(base, selected, mask)
                }
                Canvas(Modifier.size(120.dp, 60.dp).testTag("mixed-reference")) {
                    val alpha = .4f * (1f - entering) + .7f * entering
                    val mixed = Color(.7f * entering / alpha, 0f, .4f * (1f - entering) / alpha, alpha)
                    drawRect(Color.Red.copy(alpha = .7f), size = androidx.compose.ui.geometry.Size(size.width * .6f, size.height))
                    drawRect(mixed, androidx.compose.ui.geometry.Offset(size.width * .6f, 0f),
                        androidx.compose.ui.geometry.Size(size.width * .4f, size.height))
                }
            }
        } }
        for (p in listOf(.25f, .7f, .3f, 0f, 1f)) {
            compose.runOnIdle { entering = p }
            val actual = compose.onNodeWithTag("mixed-entries").captureToImage().toPixelMap()
            val expected = compose.onNodeWithTag("mixed-reference").captureToImage().toPixelMap()
            for (xFraction in listOf(.2f, .5f, .8f)) {
                val x = (actual.width * xFraction).toInt(); val y = actual.height / 2
                assertEquals("Existing/overlap/entering red at $p/$xFraction", expected[x, y].red, actual[x, y].red, 2f / 255)
                assertEquals("Existing/overlap/entering blue at $p/$xFraction", expected[x, y].blue, actual[x, y].blue, 2f / 255)
                assertEquals("Premultiplied alpha at $p/$xFraction", expected[x, y].alpha, actual[x, y].alpha, 2f / 255)
            }
        }
    }

    @Test fun membershipChangesImmediatelyWhileTheLensRetainsInterruptedVisibility() {
        var chosen by mutableStateOf(false)
        var shown by mutableStateOf(true)
        lateinit var scene: SelectionScene
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(180.dp)) {
                scene = checkNotNull(LocalSelectionScene.current)
                if (shown) Box(Modifier.size(160.dp).selectionLens(chosen, false).testTag("row"))
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { chosen = true }
            compose.onNodeWithTag("row").assertIsSelected()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            val incoming = scene.regions.values.single().visibility()
            assertTrue("Reveal is neither absent nor snapped", incoming > 0f && incoming < 1f)
            compose.runOnIdle { chosen = false }
            compose.onNodeWithTag("row").assertIsNotSelected()
            compose.mainClock.advanceTimeByFrame()
            val departing = scene.regions.values.single().visibility()
            assertTrue("Deselect retains the visible lens", departing > 0f)
            compose.mainClock.advanceTimeBy(48)
            assertTrue(scene.regions.values.single().visibility() < departing)
            compose.runOnIdle { chosen = true }
            compose.mainClock.advanceTimeBy(220)
            assertEquals(1f, scene.regions.values.single().visibility(), 0f)
            compose.runOnIdle { chosen = false; shown = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertTrue("Detached rows cannot leave stale lens entries", scene.regions.isEmpty()) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun selectionRecordingsKeepDensityAndSharpContentDuringInterruptedFrames() {
        var chosen by mutableStateOf(false)
        var density by mutableStateOf(Density(1.5f, 1.25f))
        var direction by mutableStateOf(LayoutDirection.Ltr)
        lateinit var scene: SelectionScene
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides direction) {
                ConnectedSelectionHost(Modifier.size(180.dp, 120.dp).background(Color.Green).testTag("recorded-selection")) {
                    scene = checkNotNull(LocalSelectionScene.current)
                    Canvas(Modifier.padding(20.dp).size(140.dp, 80.dp).selectionLens(chosen, false)) {
                        drawRect(Color.Blue)
                        drawRect(Color.Red, topLeft = androidx.compose.ui.geometry.Offset(40.dp.toPx(), 20.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(60.dp.toPx(), 40.dp.toPx()))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        fun verifyFrame() {
            val pixels = compose.onNodeWithTag("recorded-selection").captureToImage().toPixelMap()
            assertEquals("Recording keeps the current density", (180 * density.density).toInt(), pixels.width)
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertTrue("Selected foreground remains sharp in the recorded scene: $center", center.red > .95f && center.blue < .05f)
            assertEquals("The selection mask leaves the surrounding viewport alone", Color.Green, pixels[2, 2])
        }
        try {
            for (changed in listOf(false, true)) {
                compose.runOnIdle {
                    if (changed) { density = Density(2f, 1.5f); direction = LayoutDirection.Rtl }
                    chosen = true
                }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(48)
                assertTrue("Exercise the procedural transition recording, not only settled drawing",
                    scene.regions.values.single().visibility() in 0.01f..0.99f)
                verifyFrame()
                compose.runOnIdle { chosen = false }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(32)
                assertTrue("The departing scene is still recorded", scene.regions.values.single().visibility() > 0f)
                verifyFrame()
                compose.runOnIdle { chosen = true }
                compose.mainClock.advanceTimeBy(220)
                assertEquals(1f, scene.regions.values.single().visibility(), 0f)
                verifyFrame()
                compose.runOnIdle { chosen = false }
                compose.mainClock.advanceTimeBy(220)
                assertEquals(0f, scene.regions.values.single().visibility(), 0f)
                verifyFrame()
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedMotionNeverKeepsAStaleDepartingLens() {
        var selected by mutableStateOf(false)
        lateinit var scene: SelectionScene
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                ConnectedSelectionHost(Modifier.size(100.dp)) {
                    scene = checkNotNull(LocalSelectionScene.current)
                    Box(Modifier.fillMaxSize().selectionLens(selected, false))
                }
            }
        } }
        compose.runOnIdle { selected = true }
        compose.waitForIdle()
        assertEquals(1f, scene.regions.values.single().visibility(), 0f)
        compose.runOnIdle { selected = false }
        compose.waitForIdle()
        assertEquals(0f, scene.regions.values.single().visibility(), 0f)
    }
    @Test fun currentPositionCircleScalesWithItsExistingEaseAndRevokesInputOnExit() {
        var visible by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            Box(Modifier.size(200.dp)) {
                FloatingReturnToPositionButton(true, "Current position", visible, false) { }
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible = true }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            val entering = compose.onNodeWithContentDescription("Current position").fetchSemanticsNode().boundsInRoot.width
            compose.mainClock.advanceTimeBy(500)
            val settled = compose.onNodeWithContentDescription("Current position").fetchSemanticsNode().boundsInRoot.width
            assertTrue("Appearing lens scales towards its ordinary size", entering > settled * .92f && entering < settled)
            compose.runOnIdle { visible = false }
            compose.onNodeWithContentDescription("Current position").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { visible = true }
            compose.mainClock.advanceTimeBy(500)
            assertEquals(settled, compose.onNodeWithContentDescription("Current position").fetchSemanticsNode().boundsInRoot.width, .5f)
        } finally { compose.mainClock.autoAdvance = true }
    }

}

package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Render the real drawer, with physical clipping owned separately by its window. */
class QueueContourTest {
    @get:Rule val compose = createComposeRule()

    @Test fun intrinsicCornersSurviveWindowChangesProgressReversalAndReducedMotion() {
        val progress = Animatable(0f)
        var hardware by mutableStateOf(DeviceCorners())
        var landscape by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        var radius = 0f
        compose.setContent { LocalicaTheme {
            radius = with(LocalDensity.current) { 28.dp.toPx() }
            BoxWithConstraints(Modifier
                .size(if (landscape) 320.dp else 280.dp, if (landscape) 240.dp else 360.dp)
                .testTag("queue-window").clip(hardware.shape()).background(Color.Magenta)) {
                QueueSurface(progress, InterfaceSettings(reducedMotion = reduced), {}) {
                    Box(Modifier.fillMaxSize().background(Color.Green))
                }
            }
        } }
        // Null/pre-31 insets resolve to the same square physical policy (DeviceCornersTest).
        // Rounded/asymmetric/rotated windows must never become the drawer's contour.
        val windows = listOf(DeviceCorners(), DeviceCorners(16.dp, 16.dp, 16.dp, 16.dp),
            DeviceCorners(8.dp, 0.dp, 16.dp, 0.dp), DeviceCorners(0.dp, 8.dp, 0.dp, 16.dp))
        for (window in windows) for (wide in listOf(false, true)) for (reduce in listOf(false, true)) {
            compose.runOnIdle { hardware = window; landscape = wide; reduced = reduce }
            for (fraction in listOf(0f, .3f, .7f, .4f, 1f, .6f, 0f)) {
                compose.runOnIdle { runBlocking { progress.snapTo(fraction) } }
                assertRoundedHeader(radius, "hardware=$window landscape=$wide reduced=$reduce progress=$fraction")
            }
        }
        // Real contact cancellation and subsequent reopening must retain the same contour.
        compose.onNodeWithContentDescription("Open queue").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40f))
            cancel()
        }
        assertRoundedHeader(radius, "cancelled contact")
        compose.runOnIdle { runBlocking { progress.snapTo(0f) } }
        compose.onNodeWithContentDescription("Open queue").performClick()
        assertRoundedHeader(radius, "reopened with reduced motion")
        compose.runOnIdle { assertEquals(1f, progress.value, .001f) }
    }

    private fun assertRoundedHeader(radius: Float, state: String) {
        val window = compose.onNodeWithTag("queue-window")
        val windowBounds = window.fetchSemanticsNode().boundsInRoot
        val header = compose.onNode(hasContentDescription("Open queue") or hasContentDescription("Close queue"))
            .fetchSemanticsNode().boundsInRoot
        val image = window.captureToImage().toPixelMap()
        val left = (header.left - windowBounds.left).toInt()
        val right = (header.right - windowBounds.left).toInt() - 1
        val top = (header.top - windowBounds.top).toInt()
        // Magenta behind the cutout has no green; neutral frost/rim does. Sample well
        // outside antialiasing and away from the handle/title, at both exposed corners.
        val inset = (radius * .1f).toInt().coerceAtLeast(1)
        val y = (top + inset).coerceIn(0, image.height - 1)
        for (edge in listOf(left + inset, right - inset)) {
            assertTrue("Drawer corner must expose the background: $state", image[edge, y].green < .15f)
        }
        val face = image[(left + radius * 1.5f).toInt(), y]
        assertTrue("Frost and border must remain inside the rounded contour: $state", face.green > .25f)
    }
}

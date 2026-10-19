package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

class QueuePlayerClipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entireQueueLayerRemainsInsidePlayerDuringInterruptedExpansion() {
        val player = Animatable(0f)
        val queue = Animatable(0f)
        var density = 1f
        var navigationInset = 0.dp
        var chrome by mutableStateOf(80.dp)
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            BoxWithConstraints(Modifier.size(280.dp, 400.dp).background(Color.Blue).testTag("scene")) {
                PlayerClippedOverlay({ player.value }, chrome) {
                    QueueSurface(queue, InterfaceSettings(), {}, playerProgress = { player.value }) {
                        Box(Modifier.fillMaxSize().background(Color.Green))
                    }
                }
            }
        } }
        // Reverse midway, reopen, and then cancel back to the compact endpoint.
        // An open Queue also exercises its scrim above the translating drawer.
        for (bottomChrome in listOf(80.dp, 8.dp)) {
            compose.runOnIdle { chrome = bottomChrome }
            for (queueProgress in listOf(0f, .5f, 1f)) {
                compose.runOnIdle { runBlocking { queue.snapTo(queueProgress) } }
                for (p in listOf(.55f, .8f, .65f, .95f, 1f, .7f, 0f)) {
                    compose.runOnIdle { runBlocking { player.snapTo(p) } }
                    val image = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
                    val frame = playerSurfaceFrame(280.dp, 400.dp, navigationInset, chrome, p, DeviceCorners())
                    val left = kotlin.math.round(frame.x.value * density).toInt()
                    val top = kotlin.math.round(frame.y.value * density).toInt()
                    val right = left + kotlin.math.round(frame.width.value * density).toInt()
                    val bottom = top + kotlin.math.round(frame.height.value * density).toInt()
                    var visible = 0
                    for (y in 2 until image.height - 2 step 4) for (x in 2 until image.width - 2 step 4) {
                        val pixel = image[x, y]
                        val outside = x < left - 1 || x > right + 1 || y < top - 1 || y > bottom + 1
                        if (outside) assertTrue("Queue escaped player: p=$p q=$queueProgress chrome=$chrome at $x,$y: $pixel",
                            pixel.red < .02f && pixel.green < .02f && pixel.blue > .98f)
                        else if (pixel.green > .1f) visible++
                    }
                    if (p == 1f) assertTrue("Fully expanded Queue must remain visible", visible > 20)
                    // Before hardware interpolation starts, the player has a 24 dp
                    // corner. An open drawer/scrim must not fill this rounded cutout.
                    if (p in .55f.. .8f) {
                        val x = left + (2 * density).toInt()
                        val y = top + (2 * density).toInt()
                        val pixel = image[x, y]
                        assertTrue("Queue escaped rounded player corner: p=$p q=$queueProgress: $pixel",
                            pixel.red < .02f && pixel.green < .02f && pixel.blue > .98f)
                    }
                }
            }
        }
    }
}

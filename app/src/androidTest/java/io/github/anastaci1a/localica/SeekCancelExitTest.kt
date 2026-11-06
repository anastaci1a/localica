package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Release frames from the actual player and shared settings slider callers (#584). */
class SeekCancelExitTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun playerNormalReleaseFliesDownWithoutMissingFrames() = release(player = true, cancel = false)
    @Test fun playerCancelReleaseFadesInPlaceWithoutMissingFrames() = release(player = true, cancel = true)
    @Test fun sliderNormalReleaseFliesDownWithoutMissingFrames() = release(player = false, cancel = false)
    @Test fun sliderCancelReleaseFadesInPlaceWithoutMissingFrames() = release(player = false, cancel = true)

    private fun release(player: Boolean, cancel: Boolean) {
        var value by mutableFloatStateOf(.2f)
        var renderer = "unmounted"
        var cancelHost: SliderCancelHost? = null
        var fixtureView: android.view.View? = null
        compose.setContent { LocalicaTheme {
            val runtime = LocalGlassRendererRuntime.current
            val view = androidx.compose.ui.platform.LocalView.current
            SideEffect { renderer = runtime?.generation.toString(); fixtureView = view }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (player) PlayerSurface(
                    PlaybackState(connected = true, title = "Exit fixture", positionMs = 20000, durationMs = 100000),
                    true, {}, InterfaceSettings(), remember { Animatable(1f) },
                    {}, {}, {}, {}, {}, {}, visual = PlayerVisual())
                else {
                    val host = rememberSliderCancelHost()
                    SideEffect { cancelHost = host }
                    CompositionLocalProvider(LocalSliderCancelHost provides host) {
                        InterfaceSlider(value, { value = it }, Modifier.width(300.dp).testTag("slider"))
                        SliderCancelLayer(host, 20.dp)
                    }
                }
            }
        } }
        val slider = if (player) compose.onNodeWithContentDescription("Seek") else compose.onNodeWithTag("slider")
        val target = compose.onNodeWithTag(if (player) "cancel-seek" else "cancel-slider")
        compose.mainClock.autoAdvance = false
        try {
            slider.performTouchInput { down(center); moveTo(Offset(width * .8f, centerY)) }
            compose.mainClock.advanceTimeBy(500)
            val at = target.fetchSemanticsNode().boundsInRoot
            if (cancel) {
                val origin = slider.fetchSemanticsNode().boundsInRoot.topLeft
                slider.performTouchInput { moveTo(at.center - origin) }
                compose.mainClock.advanceTimeBy(300)
            }
            slider.performTouchInput { up() }
            val releaseClock = compose.mainClock.currentTime
            var previous = at.top
            // Every presented frame of the early exit must retain the original placement.
            repeat(12) {
                compose.mainClock.advanceTimeByFrame()
                val next = target.fetchSemanticsNode().boundsInRoot
                assertEquals("No horizontal release jump", at.left, next.left, 1f)
                if (cancel) assertEquals("Cancel fades at the release position", at.top, next.top, 1f)
                else assertTrue("Normal exit moves only down: $previous -> ${next.top}", next.top >= previous - 1f)
                previous = next.top
                val requestedClock = compose.mainClock.currentTime
                val wall = android.os.SystemClock.uptimeMillis()
                val before = run {
                    "renderer=$renderer alpha=${cancelHost?.target?.motion?.alpha} " +
                        "active=${cancelHost?.target?.active} hovered=${cancelHost?.hovered} " +
                        "view=${fixtureView?.width}x${fixtureView?.height} focus=${fixtureView?.hasWindowFocus()} " +
                        "drawing=${androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()}"
                }
                val image = target.captureToImage()
                val pixels = image.toPixelMap()
                val captureClock = compose.mainClock.currentTime
                var low = 1f
                var high = 0f
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val c = pixels[x, y]
                    val brightness = (c.red + c.green + c.blue) / 3f
                    low = minOf(low, brightness); high = maxOf(high, brightness)
                }
                val description = "player=$player cancel=$cancel frame=$it clock=$requestedClock " +
                    "elapsed=${requestedClock - releaseClock} capturedClock=$captureClock " +
                    "wallMs=${android.os.SystemClock.uptimeMillis() - wall} bounds=$next " +
                    "pixels=${pixels.width}x${pixels.height} low=$low high=$high $before"
                android.util.Log.i("SeekCancelExitProbe", description)
                if (high - low <= .02f) {
                val directory = nativeEvidenceDirectory("seek-cancel-exit-probe",
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
                java.io.File(directory, "player-$player-cancel-$cancel-frame-$it.png").outputStream().use {
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                runCatching {
                    compose.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                    val redrawn = target.captureToImage()
                    java.io.File(directory, "player-$player-cancel-$cancel-frame-$it-redrawn.png").outputStream().use {
                        redrawn.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    android.util.Log.e("SeekCancelExitProbe", "Failure-only redraw at clock=${compose.mainClock.currentTime} " +
                        "alpha=${cancelHost?.target?.motion?.alpha} bounds=${target.fetchSemanticsNode().boundsInRoot}")
                }.onFailure { android.util.Log.e("SeekCancelExitProbe", "Failure-only redraw diagnostic failed", it) }
                }
                assertEquals("Native capture must hold the requested release frame: $description", requestedClock, captureClock)
                assertTrue("The X remains drawn on release frame $it: $description", high - low > .02f)
            }
            if (!cancel) assertTrue("Normal release reverses the rise", previous > at.top + 1f)
            compose.mainClock.advanceTimeBy(500)
            target.assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun exitAlphaIsMonotonicAndNewDragContinuesFromThePresentedFrame() {
        val target = SeekCancelTargetState()
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            SeekCancelTarget(target, false, 20.dp, false)
        } } }
        compose.mainClock.autoAdvance = false
        try {
            for (cancel in listOf(false, true)) {
                compose.runOnIdle { target.update(true) }
                compose.mainClock.advanceTimeBy(500)
                var lastAlpha = 1f
                compose.runOnIdle { target.update(false, cancel); assertNull(target.bounds) }
                repeat(8) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnIdle {
                        val alpha = target.motion!!.alpha
                        assertTrue("Exit cannot disappear or brighten early", alpha > 0f && alpha <= lastAlpha)
                        lastAlpha = alpha
                    }
                }
                val at = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot
                compose.runOnIdle {
                    target.update(true)
                    assertNull("A fresh gesture cannot reuse the last hit rectangle", target.bounds)
                    assertEquals("Retarget retains alpha synchronously", lastAlpha, target.motion!!.alpha, .001f)
                }
                compose.mainClock.advanceTimeByFrame()
                val resumed = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot
                assertEquals("Reentry does not restart at the bottom", at.top, resumed.top, 2f)
                compose.mainClock.advanceTimeBy(500)
                compose.runOnIdle { assertEquals(1f, target.motion!!.alpha, .001f); assertNotNull(target.bounds) }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedMotionRemovesBothReleasePathsImmediately() {
        val target = SeekCancelTargetState()
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            SeekCancelTarget(target, false, 20.dp, true)
        } } }
        compose.mainClock.autoAdvance = false
        try {
            for (cancel in listOf(false, true)) {
                compose.runOnIdle { target.update(true) }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("cancel-seek").assertExists()
                compose.runOnIdle { target.update(false, cancel); assertNull(target.bounds) }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("cancel-seek").assertDoesNotExist()
            }
        } finally { compose.mainClock.autoAdvance = true }
    }


    @Test fun cancellationDuringEntranceFadesAtThePartiallyRaisedPosition() {
        val target = SeekCancelTargetState()
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            SeekCancelTarget(target, true, 20.dp, false)
        } } }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { target.update(true) }
            compose.mainClock.advanceTimeBy(96)
            val node = compose.onNodeWithTag("cancel-seek")
            val at = node.fetchSemanticsNode().boundsInRoot
            var alpha = 0f
            compose.runOnIdle { alpha = target.motion!!.alpha; target.update(false, true) }
            repeat(8) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals("A partial entrance must not finish rising on cancel", at.top, node.fetchSemanticsNode().boundsInRoot.top, 1f)
                compose.runOnIdle {
                    assertTrue(target.motion!!.alpha > 0f && target.motion!!.alpha <= alpha)
                    alpha = target.motion!!.alpha
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

}
